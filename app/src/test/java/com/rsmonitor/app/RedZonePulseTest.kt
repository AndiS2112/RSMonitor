package com.rsmonitor.app

import com.rsmonitor.app.car.redZonePulse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedZonePulseTest {
    @Test
    fun threePulsesASecond() {
        // full strength at the start of each third of a second, faintest half way through it
        assertEquals(1f, redZonePulse(0), 0.01f)
        assertEquals(0.25f, redZonePulse(167), 0.01f)
        assertEquals(1f, redZonePulse(333), 0.02f)
        assertEquals(0.25f, redZonePulse(500), 0.01f)
        assertEquals(1f, redZonePulse(667), 0.02f)
        assertEquals(1f, redZonePulse(1000), 0.01f)
    }

    @Test
    fun neverFainterThanAQuarter() {
        for (ms in 0L..2000L) {
            val p = redZonePulse(ms)
            assertTrue(p in 0.249f..1.001f)
        }
    }
}
