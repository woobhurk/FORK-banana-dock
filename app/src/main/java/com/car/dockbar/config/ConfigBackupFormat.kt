package com.car.dockbar.config

import android.util.JsonReader
import android.util.JsonToken
import com.car.dockbar.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

internal enum class BackupGroup(val id: String, val title: String) {
    LAYOUT("layout", "Dock 布局"),
    QUICK_APPS("quickApps", "快捷应用"),
    VISIBILITY("visibility", "显示与隐藏"),
    APPEARANCE("appearance", "外观"),
    INTERACTION("interaction", "交互"),
    LYRICS("lyrics", "歌词显示"),
}

internal class ConfigBackupException(message: String) : IOException(message)

internal object ConfigBackupFormat {
    const val ID = "banana-dock-config"
    const val VERSION = 5
    const val MAX_BYTES = 256 * 1024
    private const val FIRST_VERSION = 5
    private const val MAX_DEPTH = 16

    // 每项恰好迁移一个版本，只修改文件中的字段，保留缺失与显式值的区别。
    private val migrations = listOf<(JSONObject) -> Unit>()

    data class Field(
        val group: BackupGroup,
        val name: String,
        val key: String,
        val title: String,
        val default: Any,
        val parse: (Any) -> Any?,
        val encode: (Any) -> Any = { it },
    )

    private fun bool(group: BackupGroup, name: String, key: String, title: String, default: Boolean) =
        Field(group, name, key, title, default, { it as? Boolean })

    private fun int(group: BackupGroup, name: String, key: String, title: String, default: Int, range: IntRange) =
        Field(group, name, key, title, default, { integer(it)?.takeIf { n -> n in range }?.toInt() })

    private fun millis(group: BackupGroup, name: String, key: String, title: String, default: Long, range: LongRange) =
        Field(group, name, key, title, default, { integer(it)?.takeIf { n -> n in range } })

    private fun percent(group: BackupGroup, name: String, key: String, title: String, default: Int) =
        int(group, name, key, title, default, 0..100)

    private fun scale(name: String, key: String, title: String) = Field(
        BackupGroup.APPEARANCE, name, key, title, 1f,
        { (it as? Number)?.toDouble()?.takeIf { n -> n in 0.5..1.5 }?.toFloat() },
    )

    private val argb = Regex("#[0-9A-Fa-f]{8}")
    private fun color(name: String, key: String, title: String, default: Int) = Field(
        BackupGroup.LYRICS, name, key, title, default,
        { (it as? String)?.takeIf(argb::matches)?.substring(1)?.toLong(16)?.toInt() },
        { "#" + (it as Int).toUInt().toString(16).padStart(8, '0').uppercase() },
    )

    private val backgroundModes = setOf("solid", "transparent", "gradient", "blur")
    private val appearance = BackupGroup.APPEARANCE
    private val lyrics = BackupGroup.LYRICS
    private val visibility = BackupGroup.VISIBILITY
    private val interaction = BackupGroup.INTERACTION
    val fields = listOf(
        bool(visibility, "swipeShowEnabled", "swipe_show_dock", "底部上滑临时显示 Dock", true),
        millis(visibility, "swipeShowDurationMs", "swipe_show_dock_ms", "上滑后显示时长", 5000L, 0L..30000L),
        Field(appearance, "backgroundMode", "bg_mode", "背景模式", "gradient", { it.takeIf { v -> v in backgroundModes } }),
        bool(appearance, "followNight", "bg_follow_night", "跟随系统日夜", true),
        percent(appearance, "dayTransparency", "bg_transparency_day", "白天底色透明度", 30),
        percent(appearance, "nightTransparency", "bg_transparency_night", "夜间底色透明度", 15),
        percent(appearance, "overlayGrayLevel", "overlay_gray_level", "叠层灰度", 50),
        percent(appearance, "overlayTransparency", "overlay_transparency", "叠层透明度", 50),
        scale("iconScale", "icon_scale", "图标缩放"),
        scale("textScale", "text_scale", "文字缩放"),
        bool(appearance, "showSeparators", "show_separators", "显示分隔线", true),
        millis(interaction, "longPressDelayMs", "dock_long_press_delay_ms", "长按延迟", 350L,
            Prefs.DOCK_LONG_PRESS_MIN_MS..Prefs.DOCK_LONG_PRESS_MAX_MS),
        millis(interaction, "fadeDurationMs", "fade_duration_ms", "淡入淡出时长", 350L, 0L..1000L),
        millis(interaction, "musicScrollHoldMs", "music_scroll_hold_ms", "音乐标题首尾停顿", 2000L,
            0L..Prefs.MUSIC_SCROLL_HOLD_MAX_MS),
        percent(lyrics, "backgroundOpacity", "lyric_alpha", "背景不透明度", 40),
        int(lyrics, "textSizeSp", "lyric_text_size", "歌词字号", 26, 14..40),
        color("textColor", "lyric_text_color", "普通行颜色", 0xFFE6E6E6.toInt()),
        color("highlightColor", "lyric_hl_color", "当前行颜色", 0xFFFFD54F.toInt()),
        int(lyrics, "lineCount", "lyric_line_count", "显示行数", 10, 1..10),
        int(lyrics, "offsetMs", "lyric_offset_ms", "歌词偏移", 0, -3000..3000),
        bool(lyrics, "locked", "lyric_locked", "锁定歌词窗口", false),
    ).groupBy(Field::group)

    fun readGroups(input: InputStream): Pair<String?, JSONObject> {
        val document = readDocument(input)
        if (document.opt("format") != ID) fail("所选文件不是 Banana Dock 配置。")
        var version = integer(document.opt("schemaVersion")) ?: fail("配置文件缺少有效的版本号。")
        if (version > VERSION) fail("此文件使用 V$version，请升级 Banana Dock 后重试。")
        if (version < FIRST_VERSION) fail("此版本支持从 V$FIRST_VERSION 起的配置文件。")
        while (version < VERSION) {
            val migrate = migrations.getOrNull((version - FIRST_VERSION).toInt())
                ?: fail("配置文件 V$version 缺少迁移规则。")
            migrate(document)
            document.put("schemaVersion", ++version)
        }
        val source = (document.opt("sourceAppVersion") as? String)?.take(80)
        val groups = document.optJSONObject("groups") ?: fail("配置文件缺少 groups 对象。")
        return source to groups
    }

    private fun readDocument(input: InputStream): JSONObject {
        var remaining = MAX_BYTES
        val limited = object : FilterInputStream(input) {
            override fun read(): Int = `in`.read().also { if (it != -1 && --remaining < 0) tooLarge() }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int =
                `in`.read(bytes, offset, minOf(count, remaining + 1)).also {
                    if (it > 0) {
                        remaining -= it
                        if (remaining < 0) tooLarge()
                    }
                }
        }
        return try {
            JsonReader(InputStreamReader(limited, Charsets.UTF_8.newDecoder())).use { reader ->
                val document = readValue(reader, 0) as? JSONObject ?: fail("配置文件必须是 JSON 对象。")
                if (reader.peek() != JsonToken.END_DOCUMENT) fail("JSON 对象后包含多余内容。")
                document
            }
        } catch (e: ConfigBackupException) {
            throw e
        } catch (_: IOException) {
            fail("无法读取配置文件，请检查文件是否完整且为 UTF-8 JSON。")
        } catch (_: IllegalStateException) {
            fail("配置文件的 JSON 格式有误。")
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): Any {
        if (depth > MAX_DEPTH) fail("配置文件的 JSON 嵌套过深。")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (has(name)) fail("配置文件包含重复的 JSON 字段。")
                    put(name, readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) put(readValue(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.NUMBER -> reader.nextString().let { number ->
                number.toLongOrNull() ?: number.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: fail("配置文件包含无效的数字。")
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.STRING -> reader.nextString()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> fail("配置文件的 JSON 格式有误。")
        }
    }

    fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    fun tooLarge(): Nothing = fail("配置文件超过 256 KiB。")
    fun fail(message: String): Nothing = throw ConfigBackupException(message)
}
