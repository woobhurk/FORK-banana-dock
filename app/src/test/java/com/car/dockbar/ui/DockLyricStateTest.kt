package com.car.dockbar.ui

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import com.car.dockbar.dock.BindKind
import com.car.dockbar.dock.DockSnapshot
import com.car.dockbar.dock.DockWidget
import com.car.dockbar.dock.GestureBinding
import com.car.dockbar.dock.MountedEntry
import com.car.dockbar.dock.WidgetSpec
import com.car.dockbar.model.DockEntry
import com.car.dockbar.util.Prefs
import java.lang.reflect.Field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DockLyricStateTest {
    private lateinit var context: Context
    private var controller: ServiceController<DockOverlayService>? = null
    private var originalGlobals = emptyMap<Field, Any?>()
    private var originalListeners = emptyList<(Boolean) -> Unit>()
    private var originalOverlayPermission = false

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        originalOverlayPermission = Settings.canDrawOverlays(context)
        ShadowSettings.setCanDrawOverlays(false)
        Prefs.sp(context).edit().clear().commit()
        originalGlobals = listOf(
            field(LyricOverlayService::class.java, "alive"),
            field(LyricOverlayService::class.java, "attached"),
            field(DockOverlayService::class.java, "alive"),
            field(DockOverlayService::class.java, "attached"),
            field(DockOverlayService::class.java, "isRunning"),
            field(DockOverlayService::class.java, "lastError"),
        ).associateWith { it.get(null) }
        originalListeners = stateListeners().toList()
        stateListeners().clear()
        setLyricState(alive = false, attached = false)
    }

    @After
    fun tearDown() {
        val dock = controller?.get()
        try {
            destroyDock()
        } finally {
            // 清掉本服务排队的刷新，再排空当前主线程任务，不推进延迟轮询。
            dock?.let { (field(DockOverlayService::class.java, "main").get(it) as Handler)
                .removeCallbacksAndMessages(null) }
            try {
                idleMain()
            } finally {
                stateListeners().clear()
                stateListeners().addAll(originalListeners)
                originalGlobals.forEach { (field, value) -> field.set(null, value) }
                Prefs.sp(context).edit().clear().commit()
                ShadowSettings.setCanDrawOverlays(originalOverlayPermission)
            }
        }
    }

    @Test
    fun onCreateRegistersListenerAndRepeatedTeardownRemovesOnlyItsListener() {
        val observedStates = mutableListOf<Boolean>()
        val observer: (Boolean) -> Unit = { observedStates += it }
        LyricOverlayService.addStateListener(observer)
        val dock = createDock()
        val listener = lyricListener(dock)

        assertTrue(stateListeners().contains(listener))
        assertEquals(2, stateListeners().size)
        assertTrue(DockOverlayService.alive)
        assertFalse(DockOverlayService.attached)
        assertFalse(DockOverlayService.isRunning)

        destroyDock()
        assertFalse(stateListeners().contains(listener))
        // 重复清理不能重新注册监听，也不能误删其他订阅者。
        DockOverlayService::class.java.getDeclaredMethod("teardown").apply {
            isAccessible = true
        }.invoke(dock)
        assertEquals(setOf(observer), stateListeners())
        assertFalse(DockOverlayService.alive)
        observedStates.clear()
        publishLyricState(alive = true, attached = true)
        idleMain()
        assertEquals(listOf(true), observedStates)
        assertEquals(setOf(observer), stateListeners())
    }

    @Test
    fun lyricNotificationsBindOnAndOffOnMainWithoutWaitingForPolling() {
        val dock = createDock()
        val widget = RecordingWidget()
        mount(dock, widget, "lyric")

        assertNotificationBinds(widget, alive = true, attached = true, expected = true)
        assertNotificationBinds(widget, alive = false, attached = false, expected = false)
    }

    @Test
    fun unattachedOrFailedLyricWindowNeverLightsTheWidget() {
        val dock = createDock()
        val widget = RecordingWidget()
        mount(dock, widget, "lyric")

        assertNotificationBinds(widget, alive = true, attached = false, expected = false)
        assertNotificationBinds(widget, alive = true, attached = true, expected = true)
        // 服务仍存活但窗口挂载失败或已摘除时，必须立即熄灭。
        assertNotificationBinds(widget, alive = true, attached = false, expected = false)
        // 实际亮灭状态未变时不重复绑定，但控件必须继续保持熄灭。
        val before = SystemClock.uptimeMillis()
        publishLyricState(alive = false, attached = true)
        idleMain()
        assertEquals(before, SystemClock.uptimeMillis())
        assertEquals(1, widget.bindings.size)
        assertFalse(widget.bindings.single().snapshot.lyricOn)
    }

    @Test
    fun oneNotificationUpdatesEveryDuplicateMountedEntry() {
        val dock = createDock()
        val widget = RecordingWidget()
        val first = mount(dock, widget, "lyric-first")
        val second = mount(dock, widget, "lyric-second")

        for (on in listOf(true, false)) {
            widget.bindings.clear()
            publishLyricState(alive = on, attached = on)
            assertTrue(widget.bindings.isEmpty())
            val before = SystemClock.uptimeMillis()
            idleMain()

            assertEquals(before, SystemClock.uptimeMillis())
            assertEquals(2, widget.bindings.size)
            assertSame(first, widget.bindings[0].mounted)
            assertSame(second, widget.bindings[1].mounted)
            widget.bindings.forEach {
                assertEquals(on, it.snapshot.lyricOn)
                assertSame(Looper.getMainLooper(), it.looper)
            }
        }
    }

    private fun prepareDock(): DockOverlayService {
        return Robolectric.buildService(DockOverlayService::class.java).also {
            controller = it
        }.get()
    }

    private fun createDock(): DockOverlayService {
        val dock = prepareDock()
        controller!!.create()
        // 先排空车辆、媒体和歌词注册时的初始通知，后续绑定只能来自新通知。
        idleMain()
        assertTrue(stateListeners().contains(lyricListener(dock)))
        return dock
    }

    private fun destroyDock() {
        controller?.destroy()
        controller = null
    }

    private fun assertNotificationBinds(
        widget: RecordingWidget,
        alive: Boolean,
        attached: Boolean,
        expected: Boolean,
    ) {
        widget.bindings.clear()
        publishLyricState(alive, attached)
        // 暂停的 Looper 尚未运行，防止绕过主线程直接绑定的实现蒙混通过。
        assertTrue(widget.bindings.isEmpty())
        val before = SystemClock.uptimeMillis()
        idleMain()

        assertEquals(before, SystemClock.uptimeMillis())
        assertEquals(1, widget.bindings.size)
        assertEquals(expected, widget.bindings.single().snapshot.lyricOn)
        assertSame(Looper.getMainLooper(), widget.bindings.single().looper)
    }

    private fun publishLyricState(alive: Boolean, attached: Boolean) {
        setLyricState(alive, attached)
        // 使用真实状态通知分发链，不直接调用 Dock 的监听闭包或快照公式。
        LyricOverlayService.Companion.javaClass.getDeclaredMethod("notifyState").apply {
            isAccessible = true
        }.invoke(LyricOverlayService.Companion)
    }

    private fun setLyricState(alive: Boolean, attached: Boolean) {
        field(LyricOverlayService::class.java, "alive").setBoolean(null, alive)
        field(LyricOverlayService::class.java, "attached").setBoolean(null, attached)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateListeners(): MutableSet<(Boolean) -> Unit> =
        field(LyricOverlayService::class.java, "stateListeners").get(null) as MutableSet<(Boolean) -> Unit>

    @Suppress("UNCHECKED_CAST")
    private fun lyricListener(dock: DockOverlayService): (Boolean) -> Unit =
        field(DockOverlayService::class.java, "lyricStateListener").get(dock) as (Boolean) -> Unit

    @Suppress("UNCHECKED_CAST")
    private fun mount(dock: DockOverlayService, widget: RecordingWidget, id: String): MountedEntry {
        val entry = DockEntry(id = id, kind = "builtin", action = "lyric", label = "歌词")
        val mounted = widget.create(context, entry, 32)
        val entries = field(DockOverlayService::class.java, "mountedEntries").get(dock)
            as MutableList<MountedEntry>
        entries += mounted
        return mounted
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun field(owner: Class<*>, name: String): Field =
        owner.getDeclaredField(name).apply { isAccessible = true }

    private data class Binding(
        val mounted: MountedEntry,
        val snapshot: DockSnapshot,
        val looper: Looper?,
    )

    private class RecordingWidget : DockWidget {
        val bindings = mutableListOf<Binding>()
        override val spec = WidgetSpec(
            action = "lyric", label = "歌词", span = 1, separatorBefore = false,
            hint = "", hidden = false, catalogIcon = 0, bindKind = BindKind.LYRIC,
        )

        override fun defaultGestures(): Map<String, GestureBinding> = emptyMap()

        override fun create(ctx: Context, entry: DockEntry, iconPx: Int) = MountedEntry(
            widget = this, entry = entry, view = View(ctx), hits = emptyMap(), span = 1,
            separator = false, valueView = null, fixedWidthPx = 0, flexMinPx = 0,
            flexMaxPx = 0, bindKind = BindKind.LYRIC,
        )

        override fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {
            bindings += Binding(mounted, snapshot, Looper.myLooper())
        }
    }
}
