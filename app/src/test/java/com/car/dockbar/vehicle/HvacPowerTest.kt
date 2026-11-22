package com.car.dockbar.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HvacPowerTest {
    @Test
    fun validPowerSamplesProduceTheOppositeBareTarget() {
        assertEquals(1, HvacChannels.powerToggleTarget(0))
        assertEquals(0, HvacChannels.powerToggleTarget(1))
    }

    @Test
    fun unknownPowerSamplesDoNotProduceAWriteTarget() {
        assertNull(HvacChannels.powerToggleTarget(-1))
        assertNull(HvacChannels.powerToggleTarget(2))
        assertNull(HvacChannels.powerToggleTarget(0x10010100))
    }
}
