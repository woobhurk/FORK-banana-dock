package com.car.dockbar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentAppListTest {
    private val systemApp = ResidentAppItem("com.system.maps", "系统导航", true)
    private val personalApp = ResidentAppItem("com.personal.music", "Music Player", false)
    private val otherApp = ResidentAppItem("com.other.video", "视频", false)
    private val installedApps = listOf(systemApp, personalApp, otherApp)

    @Test
    fun listContainsOnlyBlacklistedInstalledApps() {
        assertEquals(
            listOf(systemApp, personalApp),
            ResidentAppList.blacklistedApps(
                installedApps,
                setOf(systemApp.pkg, personalApp.pkg, "com.not.installed")
            )
        )
    }

    @Test
    fun emptyBlacklistDoesNotShowInstalledApps() {
        assertTrue(ResidentAppList.blacklistedApps(installedApps, emptySet()).isEmpty())
    }

    @Test
    fun removingLastAppLeavesAnEmptyListWithoutChangingTheCatalog() {
        val blacklist = mutableSetOf(personalApp.pkg)
        assertEquals(listOf(personalApp), ResidentAppList.blacklistedApps(installedApps, blacklist))

        blacklist.remove(personalApp.pkg)

        assertTrue(ResidentAppList.blacklistedApps(installedApps, blacklist).isEmpty())
        assertEquals(listOf(systemApp, personalApp, otherApp), installedApps)
    }

    @Test
    fun addingAnAppMakesItVisibleAndPreservesCatalogOrder() {
        val blacklist = mutableSetOf(personalApp.pkg)
        blacklist.add(systemApp.pkg)
        blacklist.add(personalApp.pkg)

        assertEquals(
            listOf(systemApp, personalApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist)
        )
    }

    @Test
    fun searchMatchesBlacklistedNamesAndPackagesIgnoringCaseAndWhitespace() {
        val blacklist = setOf(systemApp.pkg, personalApp.pkg)

        assertEquals(
            listOf(personalApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist, "  MUSIC  ")
        )
        assertEquals(
            listOf(systemApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist, "COM.SYSTEM")
        )
        assertEquals(
            listOf(systemApp),
            ResidentAppList.blacklistedApps(installedApps, blacklist, "导航")
        )
    }

    @Test
    fun searchNeverBringsUnlistedAppsIntoTheBlacklist() {
        assertTrue(
            ResidentAppList.blacklistedApps(installedApps, setOf(systemApp.pkg), otherApp.pkg).isEmpty()
        )
    }

    @Test
    fun pickerIncludesAllInstalledAppsAndMarksOnlyBlacklistMembers() {
        val choices = ResidentAppList.choices(installedApps, setOf(systemApp.pkg, "com.not.installed"))

        assertEquals(installedApps, choices.map { it.app })
        assertEquals(listOf(true, false, false), choices.map { it.selected })
    }

    @Test
    fun pickerPlacesSelectedAppsFirstAndPreservesRelativeOrderWithinEachGroup() {
        val anotherSystemApp = ResidentAppItem("com.system.radio", "Radio", true)
        val apps = installedApps + anotherSystemApp
        val choices = ResidentAppList.choices(apps, setOf(anotherSystemApp.pkg, personalApp.pkg))

        assertEquals(
            listOf(personalApp, anotherSystemApp, systemApp, otherApp),
            choices.map { it.app }
        )
        assertEquals(listOf(true, true, false, false), choices.map { it.selected })
        assertEquals(listOf(systemApp, personalApp, otherApp, anotherSystemApp), apps)
    }

    @Test
    fun pickerPlacesSelectedAppsFirstWithinSystemAndPersonalColumns() {
        val anotherSystemApp = ResidentAppItem("com.system.radio", "Radio", true)
        val choices = ResidentAppList.choices(
            installedApps + anotherSystemApp,
            setOf(otherApp.pkg, anotherSystemApp.pkg)
        )

        assertEquals(
            listOf(anotherSystemApp, systemApp),
            choices.filter { it.app.system }.map { it.app }
        )
        assertEquals(
            listOf(otherApp, personalApp),
            choices.filterNot { it.app.system }.map { it.app }
        )
    }

    @Test
    fun pickerPreservesCatalogOrderWhenAllAppsAreSelected() {
        val choices = ResidentAppList.choices(installedApps, installedApps.map { it.pkg }.toSet())

        assertEquals(installedApps, choices.map { it.app })
        assertTrue(choices.all { it.selected })
    }

    @Test
    fun removedSystemAppRemainsAvailableForAddingAgain() {
        val blacklist = mutableSetOf(systemApp.pkg)
        assertTrue(ResidentAppList.choices(installedApps, blacklist).first().selected)

        blacklist.remove(systemApp.pkg)

        val choices = ResidentAppList.choices(installedApps, blacklist)
        assertEquals(installedApps, choices.map { it.app })
        assertEquals(listOf(false, false, false), choices.map { it.selected })
    }
}
