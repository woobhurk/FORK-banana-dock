package com.car.dockbar.dock

import android.view.View

interface DockHost {
    fun onGesture()
    fun showPopup(popup: String, anchor: View)
    fun toggleAcFromDock()
    fun toggleDualSyncFromDock()
}

object SilentDockHost : DockHost {
    override fun onGesture() = Unit
    override fun showPopup(popup: String, anchor: View) = Unit
    override fun toggleAcFromDock() = Unit
    override fun toggleDualSyncFromDock() = Unit
}
