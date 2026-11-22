package com.car.dockbar.dock

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Toast
import com.car.dockbar.hvac.HvacApp
import com.car.dockbar.media.MediaBridge
import com.car.dockbar.model.Builtin
import com.car.dockbar.ui.LyricWindows
import com.car.dockbar.ui.MainActivity
import com.car.dockbar.util.IntentLauncher
import com.car.dockbar.util.Logx
import com.car.dockbar.vehicle.VehicleController
import com.car.dockbar.vehicle.VehicleController.PowerTapResult

object DockActions {
    fun perform(
        action: DockAction,
        ctx: Context,
        host: DockHost,
        anchor: View,
        entry: com.car.dockbar.model.DockEntry,
    ) {
        when (action) {
            DockAction.GoHome -> IntentLauncher.goHome(ctx)
            DockAction.OpenRecents -> IntentLauncher.openRecents(ctx)
            DockAction.OpenDockSettings -> ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            DockAction.OpenThisApp -> ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            DockAction.OpenAppDrawerSettings -> ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PICK_APP_DRAWER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            DockAction.OpenCarSettings -> IntentLauncher.launch(
                ctx, "车机设置",
                IntentLauncher.settingsTarget(ctx),
                IntentLauncher.SETTINGS_CANDIDATES,
            )
            DockAction.LaunchThisEntry -> IntentLauncher.launch(
                ctx, entry.label,
                IntentLauncher.Target(pkg = entry.action, cls = entry.cls),
                emptyList(),
            )
            is DockAction.ShowPopup -> host.showPopup(action.popup, anchor)
            DockAction.ToggleHvacWindow -> host.toggleHvacWindow()
            DockAction.ToggleAc -> VehicleController.toggleAc(ctx)
            DockAction.ToggleAcFromDock -> host.toggleAcFromDock()
            DockAction.ToggleDualSyncFromDock -> host.toggleDualSyncFromDock()
            is DockAction.TempStep -> VehicleController.tempStep(ctx, action.driver, action.delta)
            is DockAction.FanStep -> VehicleController.fanStep(ctx, action.delta)
            DockAction.ToggleFanAuto -> VehicleController.toggleFanAuto(ctx)
            DockAction.FanOff -> VehicleController.setFanOff(ctx)
            DockAction.ToggleAirflow -> toggleAirflow(ctx, entry.kind == "builtin" && entry.action == Builtin.AIR.action)
            DockAction.ToggleRecirc -> VehicleController.toggleRecirc(ctx)
            DockAction.ToggleDefrostFront -> VehicleController.toggleDefrostFront(ctx)
            DockAction.ToggleDefrostRear -> VehicleController.toggleDefrostRear(ctx)
            is DockAction.LevelBump -> bump(ctx, action.channel, action.direction)
            DockAction.ToggleSeatVent -> VehicleController.setSeatVentLevel(
                ctx, if (VehicleController.state.seatVent > 0) 0 else VehicleController.SEAT_LEVEL_MAX,
            )
            DockAction.OpenAirPanel -> openAirPanel(ctx)
            DockAction.ToggleLyric -> LyricWindows.toggle(ctx)
            DockAction.OpenPlayer -> {
                if (!MediaBridge.openPlayer(ctx)) {
                    Logx.d("媒体：没有可打开的播放器")
                    Toast.makeText(ctx.applicationContext, "当前没有可打开的播放器", Toast.LENGTH_SHORT).show()
                }
            }
            DockAction.PlayPause -> MediaBridge.playPause()
            DockAction.MediaNext -> MediaBridge.next()
        }
    }

    private fun toggleAirflow(ctx: Context, airEntry: Boolean) {
        val result = if (airEntry) VehicleController.togglePowerForValidation(ctx) else PowerTapResult.INACTIVE
        val message = when (result) {
            PowerTapResult.INACTIVE -> { VehicleController.toggleAirflow(ctx); return }
            PowerTapResult.QUEUED -> return
            PowerTapResult.UNKNOWN -> "尚未读到原厂空调电源状态，未切换"
            PowerTapResult.WAITING -> "等待上次电源操作读回，未重复写入"
            PowerTapResult.UNAVAILABLE -> "空调电源试写未提交，请查看验证报告"
        }
        Toast.makeText(ctx.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun bump(ctx: Context, channel: String, direction: String) {
        val state = VehicleController.state
        val current = when (channel) {
            "seatHeat" -> state.seatHeat
            "steeringHeat" -> state.steeringHeat
            else -> state.seatVent
        }
        val next = bumpLevel(current, direction)
        when (channel) {
            "seatHeat" -> VehicleController.setSeatHeatLevel(ctx, next)
            "steeringHeat" -> VehicleController.setSteeringHeatLevel(ctx, next)
            else -> VehicleController.setSeatVentLevel(ctx, next)
        }
    }

    private fun openAirPanel(ctx: Context) {
        val target = IntentLauncher.airDirTarget(ctx)
        if (IntentLauncher.isConfigured(target)) {
            IntentLauncher.launch(ctx, "空调风向", target, IntentLauncher.HVAC_CANDIDATES)
        } else {
            HvacApp.openPanel(ctx, HvacApp.PAGE_MAIN)
        }
    }
}
