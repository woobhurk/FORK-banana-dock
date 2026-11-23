package com.car.dockbar.dock.widgets

import com.car.dockbar.dock.BindKind
import com.car.dockbar.dock.DockAction
import com.car.dockbar.dock.DockMetrics
import com.car.dockbar.dock.DockSnapshot
import com.car.dockbar.dock.DockWidget
import com.car.dockbar.dock.GestureBinding
import com.car.dockbar.dock.HitTargets
import com.car.dockbar.dock.MountedEntry
import com.car.dockbar.dock.WidgetSpec
import com.car.dockbar.dock.airDirectionIcon
import com.car.dockbar.dock.levelIcon

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.car.dockbar.R
import com.car.dockbar.model.Builtin
import com.car.dockbar.model.DockEntry
import com.car.dockbar.ui.DockIconView
import com.car.dockbar.ui.DockMusicView
import com.car.dockbar.ui.DockValueView
import com.car.dockbar.ui.VolumeDockButton
import com.car.dockbar.ui.FanDockButton
import com.car.dockbar.util.Prefs
import com.car.dockbar.vehicle.VehicleController.PowerState

private fun spec(
    builtin: Builtin,
    catalogIcon: Int,
    bindKind: BindKind = BindKind.NONE,
    lockSpan: Boolean = false,
    fixedWidthPx: Int = 0,
    flexMinPx: Int = 0,
    flexMaxPx: Int = 0,
    preserveColors: Boolean = false,
    arrowPx: Int = 0,
    arrowHitPx: Int = 0,
) = WidgetSpec(
    action = builtin.action,
    label = builtin.label,
    span = builtin.span,
    separatorBefore = builtin.separatorBefore,
    hint = builtin.hint,
    hidden = builtin.hidden,
    catalogIcon = catalogIcon,
    bindKind = bindKind,
    lockSpan = lockSpan,
    fixedWidthPx = fixedWidthPx,
    flexMinPx = flexMinPx,
    flexMaxPx = flexMaxPx,
    preserveColors = preserveColors,
    arrowPx = arrowPx,
    arrowHitPx = arrowHitPx,
)

private fun root(binding: GestureBinding) = linkedMapOf(HitTargets.ROOT to binding)

private fun tap(action: DockAction) = GestureBinding(tap = action)

private fun tapLong(tap: DockAction, longPress: DockAction) =
    GestureBinding(tap = tap, longPress = longPress)

private fun iconFor(action: String): Int = when (action) {
    "home" -> R.drawable.ic_dock_home
    "music" -> R.drawable.ic_music_note
    "lyric" -> R.drawable.ic_dock_lyrics
    "volume" -> R.drawable.ic_volume
    "settings" -> R.drawable.ic_dock_car
    "app_drawer" -> R.drawable.ic_dock_apps
    "air" -> R.drawable.ic_blowing_facefoot
    "fan_combo" -> R.drawable.ic_dock_fan
    "vent" -> R.drawable.ic_seat_feng_03
    "seat" -> R.drawable.ic_seat_open
    "seat_heat" -> R.drawable.ic_seat_hot_03
    "recirc" -> R.drawable.ic_ac_cycle_inner
    "defrost_front" -> R.drawable.ic_defrost
    "defrost_rear" -> R.drawable.ic_rear_defrost
    "steering_heat" -> R.drawable.ic_sw_heat_03
    else -> R.drawable.ic_dock_car
}

private fun iconWidget(
    builtin: Builtin,
    gestures: Map<String, GestureBinding>,
    bindKind: BindKind = BindKind.NONE,
    lockSpan: Boolean = false,
    preserveColors: Boolean = false,
    inactiveAlpha: Float = 1f,
): DockWidget = object : DockWidget {
    override val spec = spec(
        builtin, iconFor(builtin.action), bindKind, lockSpan,
        preserveColors = preserveColors,
    )
    override fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {
        (mounted.view as? DockIconView)?.let { paintIcon(it, spec.bindKind, snapshot) }
    }
    override fun defaultGestures() = gestures
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val view = DockIconView(ctx, spec.catalogIcon, iconPx.coerceAtLeast(1), spec.preserveColors, inactiveAlpha)
        return mounted(this, entry, view, linkedMapOf(HitTargets.ROOT to view), null)
    }
}

private fun valueWidget(
    builtin: Builtin,
    gestures: Map<String, GestureBinding>,
    bindKind: BindKind,
): DockWidget = object : DockWidget {
    override val spec = spec(builtin, iconFor(builtin.action), bindKind)
    override fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {
        mounted.valueView?.let { paintValue(it, spec.bindKind, snapshot) }
    }
    override fun defaultGestures() = gestures
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val text = Prefs.textScale(ctx)
        val view = DockValueView(ctx, 25f * text, 16f * text, 12f * text)
        return mounted(this, entry, view, linkedMapOf(HitTargets.ROOT to view), view)
    }
}

private fun comboWidget(
    builtin: Builtin,
    minus: DockAction,
    plus: DockAction,
    valueTap: DockAction,
    valueLong: DockAction,
    bindKind: BindKind,
    fixedWidthPx: Int = 0,
    arrowPx: Int = 0,
    arrowHitPx: Int = 0,
): DockWidget = object : DockWidget {
    override val spec = spec(
        builtin, iconFor(builtin.action), bindKind,
        fixedWidthPx = fixedWidthPx, arrowPx = arrowPx, arrowHitPx = arrowHitPx,
    )
    override fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {
        when (val value = mounted.hits[HitTargets.VALUE]) {
            is FanDockButton -> value.render(snapshot.fanLevel, snapshot.fanAuto, snapshot.darkIcons)
            is DockValueView -> paintValue(value, spec.bindKind, snapshot)
        }
    }
    override fun defaultGestures() = linkedMapOf(
        HitTargets.MINUS to tap(minus),
        HitTargets.PLUS to tap(plus),
        HitTargets.VALUE to tapLong(valueTap, valueLong),
    )
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val value = if (bindKind == BindKind.FAN) FanDockButton(ctx, iconPx) else {
            val text = Prefs.textScale(ctx)
            DockValueView(ctx, 25f * text, 16f * text, 12f * text)
        }
        val arrow = if (spec.arrowPx > 0) spec.arrowPx else iconPx
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minusView = DockIconView(ctx, R.drawable.ic_chevron_left, arrow)
        val plusView = DockIconView(ctx, R.drawable.ic_chevron_right, arrow)
        if (spec.arrowHitPx > 0) {
            // 风速：箭头列按点击宽度固定，数值列吃掉剩余空间，避免三等分把两侧撑空。
            val side = LinearLayout.LayoutParams(spec.arrowHitPx, LinearLayout.LayoutParams.MATCH_PARENT)
            val middle = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            box.addView(minusView, side)
            box.addView(value, middle)
            box.addView(plusView, LinearLayout.LayoutParams(side))
        } else {
            val cell = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            box.addView(minusView, LinearLayout.LayoutParams(cell))
            box.addView(value, LinearLayout.LayoutParams(cell))
            box.addView(plusView, LinearLayout.LayoutParams(cell))
        }
        return mounted(
            this, entry, box,
            linkedMapOf(
                HitTargets.MINUS to minusView,
                HitTargets.VALUE to value,
                HitTargets.PLUS to plusView,
            ),
            value as? DockValueView,
        )
    }
}

private fun mounted(
    widget: DockWidget,
    entry: DockEntry,
    view: android.view.View,
    hits: Map<String, android.view.View>,
    valueView: DockValueView?,
) = MountedEntry(
    widget = widget,
    entry = entry,
    view = view,
    hits = hits,
    span = widget.spec.span,
    separator = widget.spec.separatorBefore,
    valueView = valueView,
    fixedWidthPx = widget.spec.fixedWidthPx,
    flexMinPx = widget.spec.flexMinPx,
    flexMaxPx = widget.spec.flexMaxPx,
    bindKind = widget.spec.bindKind,
)

private object VolumeWidget : DockWidget {
    override val spec = spec(Builtin.VOLUME, R.drawable.ic_volume, BindKind.VOLUME)
    override fun defaultGestures() = root(tap(DockAction.ShowPopup("volume")))
    override fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {
        (mounted.view as? VolumeDockButton)?.setLevel(snapshot.volumeLevel)
    }
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val view = VolumeDockButton(ctx, iconPx)
        return mounted(this, entry, view, linkedMapOf(HitTargets.ROOT to view), null)
    }
}

private object MusicWidget : DockWidget {
    override val spec = spec(
        Builtin.MUSIC, iconFor("music"), BindKind.MUSIC,
        flexMinPx = DockMetrics.MUSIC_MIN_WIDTH_PX,
        flexMaxPx = DockMetrics.MUSIC_MAX_WIDTH_PX,
    )
    override fun defaultGestures() = linkedMapOf(
        HitTargets.ROOT to tapLong(DockAction.OpenPlayer, DockAction.ToggleLyric),
        HitTargets.PLAY to tap(DockAction.PlayPause),
        HitTargets.NEXT to tap(DockAction.MediaNext),
    )
    override fun bind(mounted: MountedEntry, snapshot: DockSnapshot) {
        val music = mounted.view as? DockMusicView ?: return
        if (snapshot.mediaHasSession) {
            music.render(snapshot.mediaTitle, snapshot.mediaArtist, snapshot.mediaArt, snapshot.mediaPlaying)
        } else {
            music.renderEmpty()
        }
    }
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val view = DockMusicView(
            ctx, Prefs.dockHeightPx(ctx), Prefs.textScale(ctx), Prefs.iconScale(ctx),
        )
        return mounted(
            this, entry, view,
            linkedMapOf(
                HitTargets.ROOT to view,
                HitTargets.PLAY to view.btnPlay,
                HitTargets.NEXT to view.btnNext,
            ),
            null,
        )
    }
}

internal object AppWidget : DockWidget {
    override val spec = WidgetSpec(
        action = "app", label = "", span = 1, separatorBefore = false, hint = "",
        hidden = true, catalogIcon = R.drawable.ic_apps,
    )
    override fun defaultGestures() = root(tap(DockAction.LaunchThisEntry))
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val view = DockIconView(ctx, 0, DockMetrics.appIconSizePx(iconPx))
        runCatching { view.setDynamic(ctx.packageManager.getApplicationIcon(entry.action)) }
            .onFailure { runCatching { view.setIcon(R.drawable.ic_dock_car) } }
        return mounted(this, entry, view, linkedMapOf(HitTargets.ROOT to view), null)
            .copy(span = entry.span.coerceIn(1, 6), separator = false)
    }
}

internal class MissingWidget(action: String) : DockWidget {
    override val spec = WidgetSpec(
        action = action, label = "", span = 1, separatorBefore = false, hint = "",
        hidden = true, catalogIcon = R.drawable.ic_dock_car,
    )
    override fun defaultGestures() = emptyMap<String, GestureBinding>()
    override fun create(ctx: Context, entry: DockEntry, iconPx: Int): MountedEntry {
        val view = DockIconView(ctx, R.drawable.ic_dock_car, iconPx)
        return mounted(this, entry, view, emptyMap(), null)
    }
}

internal object BuiltinWidgets {
    val all: List<DockWidget> = listOf(
        iconWidget(Builtin.HOME, root(GestureBinding(tap = DockAction.GoHome, longPress = DockAction.OpenRecents))),
        MusicWidget,
        iconWidget(Builtin.LYRIC, root(tapLong(DockAction.ToggleLyric, DockAction.OpenThisApp)), BindKind.LYRIC, lockSpan = true),
        VolumeWidget,
        iconWidget(Builtin.SETTINGS, root(tapLong(DockAction.OpenCarSettings, DockAction.OpenDockSettings))),
        iconWidget(Builtin.APP_DRAWER, root(tapLong(DockAction.ShowPopup("appDrawer"), DockAction.OpenAppDrawerSettings))),
        comboWidget(
            Builtin.TEMP_DRIVER_COMBO,
            DockAction.TempStep(true, -1), DockAction.TempStep(true, 1),
            DockAction.ToggleAcFromDock, DockAction.ShowPopup("temperatureDriver"),
            BindKind.TEMP_DRIVER,
        ),
        comboWidget(
            Builtin.TEMP_PASSENGER_COMBO,
            DockAction.TempStep(false, -1), DockAction.TempStep(false, 1),
            DockAction.ToggleAcFromDock, DockAction.ShowPopup("temperaturePassenger"),
            BindKind.TEMP_PASSENGER,
        ),
        comboWidget(
            Builtin.FAN_COMBO,
            DockAction.FanStep(-1), DockAction.FanStep(1),
            DockAction.ToggleFanAuto, DockAction.OpenAirPanel,
            BindKind.FAN,
            fixedWidthPx = DockMetrics.FAN_COMBO_FIXED_WIDTH_PX,
            arrowPx = DockMetrics.FAN_COMBO_ARROW_PX,
            arrowHitPx = DockMetrics.FAN_COMBO_ARROW_HIT_PX,
        ),
        iconWidget(Builtin.TEMP_DOWN_DRIVER, root(tap(DockAction.TempStep(true, -1)))),
        valueWidget(Builtin.TEMP_DRIVER, root(tapLong(DockAction.ToggleHvacWindow, DockAction.ToggleAc)), BindKind.TEMP_DRIVER),
        iconWidget(Builtin.TEMP_UP_DRIVER, root(tap(DockAction.TempStep(true, 1)))),
        iconWidget(Builtin.AIR, root(tapLong(DockAction.ToggleAirflow, DockAction.ShowPopup("airDirection"))), BindKind.AIR),
        iconWidget(Builtin.VENT, root(tapLong(DockAction.ToggleSeatVent, DockAction.ShowPopup("seatClimate"))), BindKind.VENT, preserveColors = true),
        iconWidget(Builtin.SEAT_HEAT, root(tapLong(DockAction.LevelBump("seatHeat", "down"), DockAction.LevelBump("seatHeat", "up"))), BindKind.SEAT_HEAT, preserveColors = true),
        iconWidget(Builtin.FAN_DOWN, root(tap(DockAction.FanStep(-1)))),
        valueWidget(Builtin.FAN, root(tapLong(DockAction.ToggleHvacWindow, DockAction.FanOff)), BindKind.FAN),
        iconWidget(Builtin.FAN_UP, root(tap(DockAction.FanStep(1)))),
        iconWidget(Builtin.RECIRC, root(tap(DockAction.ToggleRecirc)), BindKind.RECIRC),
        iconWidget(Builtin.SEAT, root(tap(DockAction.ShowPopup("seat")))),
        iconWidget(Builtin.TEMP_DOWN_PASSENGER, root(tap(DockAction.TempStep(false, -1)))),
        valueWidget(Builtin.TEMP_PASSENGER, root(tapLong(DockAction.ToggleHvacWindow, DockAction.ToggleAc)), BindKind.TEMP_PASSENGER),
        iconWidget(Builtin.TEMP_UP_PASSENGER, root(tap(DockAction.TempStep(false, 1)))),
        iconWidget(Builtin.DEFROST_FRONT, root(tap(DockAction.ToggleDefrostFront)), BindKind.DEFROST_FRONT),
        iconWidget(Builtin.DEFROST_REAR, root(tap(DockAction.ToggleDefrostRear)), BindKind.DEFROST_REAR),
        iconWidget(Builtin.STEERING_HEAT, root(tapLong(DockAction.LevelBump("steeringHeat", "down"), DockAction.LevelBump("steeringHeat", "up"))), BindKind.STEERING_HEAT, preserveColors = true, inactiveAlpha = 0.72f),
    )
}

private fun paintIcon(view: DockIconView, kind: BindKind, snapshot: DockSnapshot) {
    when (kind) {
        BindKind.RECIRC -> view.setIcon(
            if (snapshot.recircInside) R.drawable.ic_ac_cycle_inner else R.drawable.ic_ac_cycle_outer
        )
        BindKind.AIR -> {
            val power = snapshot.powerState
            val stopped = power == PowerState.OFF
            view.setIcon(if (stopped) R.drawable.ic_blowing_stopped else airDirectionIcon(snapshot.airDirection))
            view.contentDescription = when (power) {
                PowerState.ON -> "空调电源已开，短按关闭，长按调风向"
                PowerState.OFF -> "空调电源已关，短按开启，长按调风向"
                PowerState.UNKNOWN -> "空调电源状态未知，短按不猜状态，长按调风向"
            }
        }
        BindKind.DEFROST_FRONT -> view.setActive(snapshot.defrostFront)
        BindKind.DEFROST_REAR -> view.setActive(snapshot.defrostRear)
        BindKind.LYRIC -> view.setActive(snapshot.lyricOn)
        BindKind.VENT -> {
            view.visibility = if (snapshot.driverVentVisible) View.VISIBLE else View.GONE
            if (snapshot.seatHeat > 0) {
                view.setIcon(levelIcon("seatHeat", snapshot.seatHeat, snapshot.darkIcons))
                view.contentDescription = "主驾座椅加热 ${snapshot.seatHeat} 档"
            } else {
                view.setIcon(levelIcon("vent", snapshot.seatVent, snapshot.darkIcons))
                view.contentDescription = if (snapshot.seatVent > 0) "主驾座椅通风 ${snapshot.seatVent} 档"
                    else "主驾座椅通风和加热关闭"
            }
        }
        BindKind.SEAT_HEAT -> {
            view.setIcon(levelIcon("seatHeat", snapshot.seatHeat, snapshot.darkIcons))
        }
        BindKind.STEERING_HEAT -> {
            view.visibility = if (snapshot.steeringHeatVisible) View.VISIBLE else View.GONE
            view.setIcon(levelIcon("steeringHeat", snapshot.steeringHeat, snapshot.darkIcons))
            view.setActive(snapshot.steeringHeat > 0)
        }
        else -> Unit
    }
}

private fun paintValue(view: DockValueView, kind: BindKind, snapshot: DockSnapshot) {
    when (kind) {
        BindKind.TEMP_DRIVER -> view.renderTemp(snapshot.driverTempC, muted = !snapshot.acOn)
        BindKind.TEMP_PASSENGER -> view.renderTemp(snapshot.passengerTempC, muted = !snapshot.acOn)
        BindKind.FAN -> view.renderFan(snapshot.fanLevel, snapshot.fanAuto)
        else -> Unit
    }
}
