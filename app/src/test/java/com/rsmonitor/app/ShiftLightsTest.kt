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

    @Test
    fun aStepMovesByTheAmountAsked() {
        val steps = ShiftLights.STANDARD
        assertEquals(listOf(3300, 3800, 4700, 5800, 7300, 7800), ShiftLights.moved(steps, 2, -100))
        assertEquals(listOf(3300, 3800, 4800, 5800, 7300, 7900), ShiftLights.moved(steps, 5, 100))
    }

    @Test
    fun aStepStopsAtItsNeighbours() {
        val steps = listOf(3300, 3400, 4800, 5800, 7300, 7800)
        // the second light cannot go below the first, nor the first above the second
        assertEquals(listOf(3300, 3300, 4800, 5800, 7300, 7800), ShiftLights.moved(steps, 1, -500))
        assertEquals(listOf(3400, 3400, 4800, 5800, 7300, 7800), ShiftLights.moved(steps, 0, 500))
        // and the ends stop at the range
        assertEquals(ShiftLights.LOWEST, ShiftLights.moved(steps, 0, -9000)[0])
        assertEquals(ShiftLights.HIGHEST, ShiftLights.moved(steps, 5, 9000)[5])
    }

    @Test
    fun onlySixRisingSpeedsInRangeAreValid() {
        assertTrue(ShiftLights.isValid(ShiftLights.STANDARD))
        assertTrue(ShiftLights.isValid(listOf(3000, 3000, 4000, 5000, 6000, 6000)))
        assertFalse(ShiftLights.isValid(listOf(3300, 3800, 4800, 5800, 7300)))
        assertFalse(ShiftLights.isValid(listOf(3800, 3300, 4800, 5800, 7300, 7800)))
        assertFalse(ShiftLights.isValid(listOf(300, 3800, 4800, 5800, 7300, 7800)))
    }

    @Test
    fun newStepsTakeEffectAtOnce() {
        try {
            ShiftLights.use(listOf(3000, 3500, 4500, 5500, 7000, 7500))
            assertEquals(listOf(g, null, null, null, null), ShiftLights.colours(3000))
            assertEquals(listOf<Int?>(g, y, y, y, r), ShiftLights.colours(7000))
            assertTrue(ShiftLights.isFlashing(7500))
            // nonsense is ignored
            ShiftLights.use(listOf(1, 2, 3))
            assertTrue(ShiftLights.isFlashing(7500))
            assertFalse(ShiftLights.isFlashing(7499))
        } finally {
            ShiftLights.use(ShiftLights.STANDARD)
        }
    }
}
