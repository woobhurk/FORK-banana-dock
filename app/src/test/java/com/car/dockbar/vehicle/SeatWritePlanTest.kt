package com.car.dockbar.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatWritePlanTest {
    @Test
    fun directModeWritesOnlyRequestedActiveChannel() {
        assertEquals(
            listOf(HvacWriteActions.SeatWriteStep("seat_heat", 3)),
            HvacWriteActions.seatWritePlan(true, true, 3, SeatWriteValidationMode.ECARX_DIRECT)
        )
    }

    @Test
    fun combinedModeClosesOppositeChannelBeforeRequestedChannel() {
        assertEquals(
            listOf(
                HvacWriteActions.SeatWriteStep("seat_vent_passenger", 0),
                HvacWriteActions.SeatWriteStep("seat_heat_passenger", 2)
            ),
            HvacWriteActions.seatWritePlan(false, true, 2, SeatWriteValidationMode.ECARX_COMBINED)
        )
    }
}
