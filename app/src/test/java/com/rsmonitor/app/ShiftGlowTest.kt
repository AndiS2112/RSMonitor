package com.rsmonitor.app

import com.rsmonitor.app.car.ShiftGlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftGlowTest {
    // times within one 250 ms flash period: the lights are lit for the first half
    private val midLit = 62L      // close to the middle of the lit half
    private val midDark = 187L    // close to the middle of the dark half

    @Test
    fun pulseIsBrightestMidFlashAndNeverDark() {
        assertEquals(1f, ShiftGlow.pulse(1_000 + midLit), 0.01f)
        assertEquals(ShiftGlow.LOW, ShiftGlow.pulse(1_000 + midDark), 0.01f)
        for (t in 0L until 250L) {
            val p = ShiftGlow.pulse(t)
            assertTrue(p >= ShiftGlow.LOW - 1e-4f && p <= 1f + 1e-4f)
        }
        // the same in every period
        assertEquals(ShiftGlow.pulse(40), ShiftGlow.pulse(40 + 250 * 7), 1e-5f)
    }

    @Test
    fun nothingUntilTheLightsFlash() {
        val g = ShiftGlow()
        for (t in 0L..1_000L step 33) assertEquals(0f, g.level(t, flashing = false), 0f)
    }

    @Test
    fun comesUpQuicklyAndFollowsThePulse() {
        val g = ShiftGlow()
        var t = 0L
        repeat(10) { g.level(t, flashing = true); t += 33 }      // a third of a second into the flash
        // by now it is fully up, so it shows the pulse itself
        val bright = g.level(1_000 + midLit, flashing = true)
        assertEquals(1f, bright, 0.03f)
        val dim = g.level(1_000 + midDark, flashing = true)
        assertEquals(ShiftGlow.LOW, dim, 0.03f)
    }

    @Test
    fun diesAwayToExactlyNothingAfterTheShift() {
        val g = ShiftGlow()
        var t = 0L
        repeat(10) { g.level(t, flashing = true); t += 33 }
        val first = g.level(t, flashing = false)
        assertTrue(first > 0f)                                    // it does not cut out at once
        var last = first
        repeat(60) { t += 33; last = g.level(t, flashing = false) }
        assertEquals(0f, last, 0f)                                // two seconds on: gone, exactly
    }

    @Test
    fun aLongGapBetweenFramesDoesNotOvershoot() {
        val g = ShiftGlow()
        g.level(0, flashing = true)
        val after = g.level(60_000 + midLit, flashing = true)
        assertTrue(after in 0f..1f)
    }
}
