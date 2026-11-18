package com.car.dockbar.util

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.car.dockbar.vehicle.HvacRefreshPolicy
import com.car.dockbar.vehicle.VehicleRefreshGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HvacEventSubscriptionTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Prefs.sp(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Prefs.sp(context).edit().clear().commit()
    }

    @Test
    fun freshPreferencesSubscribeToValidatedClimateChannelsByDefault() {
        assertEquals(
            setOf("fan_manual", "fan_auto", "ac", "recirc", "air_direction"),
            Prefs.hvacEventKeys(context)
        )
    }

    @Test
    fun climatePollingRemainsFallbackUntilAllConfiguredEventsAreProven() {
        val configured = HvacRefreshPolicy.mask(
            setOf("fan_manual", "fan_auto", "ac", "recirc", "air_direction", "temp_dual")
        )
        val climateSeen = HvacRefreshPolicy.mask(setOf("ac", "recirc"))

        assertEquals(
            VehicleRefreshGroup.CLIMATE.intervalMs,
            HvacRefreshPolicy.intervalMs(
                VehicleRefreshGroup.CLIMATE,
                configured,
                climateSeen,
                registered = true
            )
        )
        assertEquals(
            2_500L,
            HvacRefreshPolicy.intervalMs(
                VehicleRefreshGroup.CLIMATE,
                configured,
                configured,
                registered = true
            )
        )

    }

    @Test
    fun dualTemperatureIsIncludedInClimatePollingProof() {
        val configured = HvacRefreshPolicy.mask(setOf("ac", "recirc", "air_direction", "temp_dual"))

        assertTrue(HvacRefreshPolicy.bit("temp_dual") != 0)
        assertEquals(
            2_500L,
            HvacRefreshPolicy.intervalMs(
                VehicleRefreshGroup.CLIMATE,
                configured,
                configured,
                registered = true
            )
        )
    }
    @Test
    fun explicitEmptySelectionDoesNotRegainDefaultClimateSubscriptions() {
        Prefs.setHvacEventKeys(context, emptySet())

        assertTrue(Prefs.hvacEventKeys(context).isEmpty())
    }

    @Test
    fun savedCustomSelectionDoesNotGainDefaultClimateSubscriptions() {
        Prefs.setHvacEventKeys(context, setOf("ac"))

        assertEquals(setOf("ac"), Prefs.hvacEventKeys(context))
    }
}
