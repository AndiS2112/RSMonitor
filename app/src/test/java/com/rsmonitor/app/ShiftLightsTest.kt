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
    fun allOffBelowThirtyThreeHundred() {
        assertEquals(listOf<Int?>(null, null, null, null, null), ShiftLights.colours(800))
        assertEquals(listOf<Int?>(null, null, null, null, null), ShiftLights.colours(3299))
    }

    @Test
    fun fillsLeftToRight() {
        assertEquals(listOf(g, null, null, null, null), ShiftLights.colours(3300))
        assertEquals(listOf(g, null, null, null, null), ShiftLights.colours(3799))
        assertEquals(listOf(g, y, null, null, null), ShiftLights.colours(3800))
        assertEquals(listOf(g, y, y, null, null), ShiftLights.colours(4800))
        assertEquals(listOf(g, y, y, y, null), ShiftLights.colours(5800))
        assertEquals(listOf(g, y, y, y, null), ShiftLights.colours(7299))
        assertEquals(listOf<Int?>(g, y, y, y, r), ShiftLights.colours(7300))
        assertEquals(listOf<Int?>(g, y, y, y, r), ShiftLights.colours(7799))
    }

    @Test
    fun allRedAndFlashingFromSeventyEightHundred() {
        assertFalse(ShiftLights.isFlashing(7799))
        assertTrue(ShiftLights.isFlashing(7800))
        assertEquals(listOf<Int?>(r, r, r, r, r), ShiftLights.colours(7800))
        assertEquals(listOf<Int?>(r, r, r, r, r), ShiftLights.colours(8600))
    }
}
