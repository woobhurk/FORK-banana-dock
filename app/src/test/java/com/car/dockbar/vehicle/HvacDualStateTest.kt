package com.car.dockbar.vehicle

import com.car.dockbar.vehicle.VehicleController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HvacDualStateTest {
    @Test
    fun dualTemperatureRawValuesBecomeObservedState() {
        val initial = State()

        assertTrue(HvacChannels.validRaw("temp_dual", 1))
        assertTrue(HvacChannels.validRaw("temp_dual", 0))
        assertFalse(HvacChannels.validRaw("temp_dual", 2))
        assertTrue(HvacChannels.merge(initial, "temp_dual", 1).tempDualOn == true)
        assertTrue(HvacChannels.merge(initial, "temp_dual", 0).tempDualOn == false)
    }

    @Test
    fun dualTemperatureObservedStateIsDisplayedByStateLedger() {
        val ledger = HvacState(State())
        val observed = HvacChannels.merge(State(), "temp_dual", 1)
        val properties = HvacChannels.observationProperties("temp_dual", 1)

        ledger.observe(observed, properties, nowMs = 1L, connection = 1L)

        assertEquals(true, ledger.display(nowMs = 1L, connection = 1L).tempDualOn)
    }
}
