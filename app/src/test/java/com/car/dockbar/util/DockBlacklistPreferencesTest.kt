package com.car.dockbar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DockBlacklistPreferencesTest {
    @Test
    fun freshInstallReadsCurrentDefaultsWithoutWriting() {
        val memory = MemoryPreferences()

        assertEquals(DockBlacklistDefaults.packages, DockBlacklistPreferences.read(memory.prefs))
        assertEquals(29, DockBlacklistPreferences.read(memory.prefs).size)
        assertTrue(memory.writes.isEmpty())
    }

    @Test
    fun firstWriteStoresNormalizedUserSelection() {
        val memory = MemoryPreferences()

        DockBlacklistPreferences.write(memory.prefs, setOf(" app.a ", "app.a", ""))

        assertEquals("app.a", memory.values[DOCK_HIDE_APPS_KEY])
        assertEquals(setOf("app.a"), DockBlacklistPreferences.read(memory.prefs))
    }

    @Test
    fun removingDefaultAndAddingCustomAppSurvivesRepeatedReads() {
        val memory = MemoryPreferences()
        val selected = DockBlacklistPreferences.read(memory.prefs).toMutableSet()
        selected.remove("com.quark")
        selected.add("com.example.custom")
        DockBlacklistPreferences.write(memory.prefs, selected)

        repeat(2) {
            assertEquals(
                (DockBlacklistDefaults.packages - "com.quark") + "com.example.custom",
                DockBlacklistPreferences.read(memory.prefs)
            )
        }
    }

    @Test
    fun emptyUserSelectionIsNeverRepopulated() {
        val memory = MemoryPreferences()

        DockBlacklistPreferences.write(memory.prefs, emptySet())

        repeat(2) { assertTrue(DockBlacklistPreferences.read(memory.prefs).isEmpty()) }
        assertEquals(1, memory.writes.size)
    }

    @Test
    fun commaSeparatedValuesAreTrimmedAndDeduplicated() {
        val memory = MemoryPreferences(mapOf(DOCK_HIDE_APPS_KEY to " app.a, ,app.b,app.a, "))

        assertEquals(setOf("app.a", "app.b"), DockBlacklistPreferences.read(memory.prefs))
    }

    @Test
    fun prefsDefaultCompatibilityEntryForwardsToTheSameCurrentList() {
        assertSame(DockBlacklistDefaults.apps, Prefs.DEFAULT_DOCK_HIDE)
        assertEquals(DockBlacklistDefaults.apps.map { it.first }.toSet(), DockBlacklistDefaults.packages)
        assertEquals("夸克网盘", Prefs.dockHideLabel("com.quark"))
        assertEquals("unknown.package", Prefs.dockHideLabel("unknown.package"))
    }
}
