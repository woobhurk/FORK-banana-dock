package com.car.dockbar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockBlacklistRulesTest {

    @Test
    fun blankPackageIsNeverHidden() {
        assertFalse(DockBlacklistRules.shouldHide("", setOf("")))
        assertFalse(DockBlacklistRules.shouldHide("   ", setOf("   ")))
    }

    @Test
    fun nonBlacklistedPackageIsShown() {
        assertFalse(
            DockBlacklistRules.shouldHide(
                "com.example.shown",
                setOf("com.example.hidden")
            )
        )
    }

    @Test
    fun blacklistedPackageIsHidden() {
        assertTrue(
            DockBlacklistRules.shouldHide(
                "com.example.hidden",
                setOf("com.example.hidden")
            )
        )
    }
}
