package com.car.dockbar.util

internal object DockBlacklistRules {
    fun shouldHide(pkg: String, blacklist: Set<String>): Boolean =
        pkg.isNotBlank() && pkg in blacklist
}
