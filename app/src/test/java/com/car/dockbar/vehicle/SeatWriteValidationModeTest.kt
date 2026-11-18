package com.car.dockbar.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatWriteValidationModeTest {
    @Test
    fun seatValidationModesExposeDistinctWriteStrategies() {
        assertEquals(
            listOf("ECarX 直接目标写入", "ECarX 先关闭相反模式", "GlyCar 属性直接写入"),
            SeatWriteValidationMode.entries.map { it.label }
        )
        assertTrue(HvacValidationMode.SEAT_ECARX_DIRECT.seatWriteMode == SeatWriteValidationMode.ECARX_DIRECT)
        assertTrue(HvacValidationMode.SEAT_ECARX_COMBINED.seatWriteMode == SeatWriteValidationMode.ECARX_COMBINED)
        assertTrue(HvacValidationMode.SEAT_GLYCAR_DIRECT.seatWriteMode == SeatWriteValidationMode.GLYCAR_DIRECT)
    }
}
