package com.car.dockbar.dock

import android.content.Context
import android.view.View
import com.car.dockbar.model.DockEntry
import com.car.dockbar.ui.DockIconView
import com.car.dockbar.ui.DockValueView
import com.car.dockbar.util.Prefs

enum class BindKind {
    NONE, TEMP_DRIVER, TEMP_PASSENGER, FAN, MUSIC, LYRIC, RECIRC, VENT, SEAT_HEAT,
    AIR, DEFROST_FRONT, DEFROST_REAR, STEERING_HEAT, VOLUME,
}

data class WidgetSpec(
    val action: String,
    val label: String,
    val span: Int,
    val separatorBefore: Boolean,
    val hint: String,
    val catalogIcon: Int,
    val fixedWidthPx: Int = 0,
    val flexMinPx: Int = 0,
    val flexMaxPx: Int = 0,
    val lockSpan: Boolean = false,
    val preserveColors: Boolean = false,
    val arrowPx: Int = 0,
    /** 箭头点击列宽。0 表示与数值三等分。 */
    val arrowHitPx: Int = 0,
    val bindKind: BindKind = BindKind.NONE,
)

data class MountedEntry(
    val widget: DockWidget,
    val entry: DockEntry,
    val view: View,
    val hits: Map<String, View>,
    val span: Int,
    val separator: Boolean,
    val valueView: DockValueView?,
    val fixedWidthPx: Int,
    val flexMinPx: Int,
    val flexMaxPx: Int,
    val bindKind: BindKind,
)

interface DockWidget {
    val spec: WidgetSpec
    fun defaultGestures(): Map<String, GestureBinding>
    fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry
    fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {}
}

object DockMetrics {
    /** 风速箭头图标边长。 */
    const val FAN_COMBO_ARROW_PX = 40
    /** 风速箭头点击列：图标两侧各留 4px，避免贴边。 */
    const val FAN_COMBO_ARROW_HIT_PX = FAN_COMBO_ARROW_PX + 8
    /** 风速图标及角标的触控列宽。 */
    const val FAN_COMBO_VALUE_PX = 80
    /** 风速组合总宽 = 两侧点击列 + 数值列。 */
    const val FAN_COMBO_FIXED_WIDTH_PX = FAN_COMBO_ARROW_HIT_PX * 2 + FAN_COMBO_VALUE_PX
    /** 主栏档位数字相对于图标边长的字号比例。 */
    internal const val LEVEL_TEXT_SCALE = 18f / 64f
    const val SLOT_FIXED_PX = 98
    const val SPAN3_FIXED_PX = 290
    const val MUSIC_MIN_WIDTH_PX = 295
    const val MUSIC_MAX_WIDTH_PX = 500

    internal fun iconSizePx(context: Context) = (65 * Prefs.iconScale(context)).toInt().coerceAtLeast(12)

    internal fun appIconSizePx(iconPx: Int) = (iconPx * 0.86f).toInt().coerceAtLeast(1)

    fun fixedWidthFor(span: Int): Int = when (span) {
        1 -> SLOT_FIXED_PX
        3 -> SPAN3_FIXED_PX
        else -> (span * SLOT_FIXED_PX).coerceAtLeast(SLOT_FIXED_PX)
    }
}
