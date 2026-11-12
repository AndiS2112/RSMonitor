package com.rsmonitor.app

import com.rsmonitor.app.car.ShiftLights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftLightsTest {
    private val g = ShiftLights.GREEN
    private val y = ShiftLights.YELLOW
    private val r = ShiftLights.RED

    @Test
    fun allOffBelowThirtyFiveHundred() {
        assertEquals(listOf<Int?>(null, null, null, null, null), ShiftLights.colours(800))
        assertEquals(listOf<Int?>(null, null, null, null, null), ShiftLights.colours(3499))
    }

    @Test
    fun fillsLeftToRight() {
        assertEquals(listOf(g, null, null, null, null), ShiftLights.colours(3500))
        assertEquals(listOf(g, null, null, null, null), ShiftLights.colours(3999))
        assertEquals(listOf(g, y, null, null, null), ShiftLights.colours(4000))
        assertEquals(listOf(g, y, y, null, null), ShiftLights.colours(5000))
        assertEquals(listOf(g, y, y, y, null), ShiftLights.colours(6000))
        assertEquals(listOf(g, y, y, y, null), ShiftLights.colours(7499))
        assertEquals(listOf<Int?>(g, y, y, y, r), ShiftLights.colours(7500))
        assertEquals(listOf<Int?>(g, y, y, y, r), ShiftLights.colours(7999))
    }

    @Test
    fun allRedAndFlashingFromEightThousand() {
        assertFalse(ShiftLights.isFlashing(7999))
        assertTrue(ShiftLights.isFlashing(8000))
        assertEquals(listOf<Int?>(r, r, r, r, r), ShiftLights.colours(8000))
        assertEquals(listOf<Int?>(r, r, r, r, r), ShiftLights.colours(8600))
    }
}
