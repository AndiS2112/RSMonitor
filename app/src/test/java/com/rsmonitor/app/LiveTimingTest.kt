package com.rsmonitor.app

import com.rsmonitor.app.obd.LiveTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTimingTest {
    @Test
    fun emptyUntilARoundWithAReading() {
        val t = LiveTiming()
        assertTrue(t.isEmpty)
        assertEquals("no readings", t.summary())
    }

    @Test
    fun sumsUpEngineSpeedOnly() {
        val t = LiveTiming()
        // twenty rounds of 50 ms: a 30 ms request for engine speed and 20 ms of everything else
        repeat(20) {
            t.speedRequest(30f)
            t.round(50f)
        }
        assertEquals("20/s · engine speed 30 ms each (fastest 30, slowest 30) · the rest 40% of the time", t.summary())
    }

    @Test
    fun sumsUpTorqueAndTheSpread() {
        val t = LiveTiming()
        for (ms in listOf(28f, 52f, 96f, 40f)) {
            t.speedRequest(ms)
            t.torqueRequest(31f)
            t.round(ms + 31f + 19f)
        }
        // 4 rounds, 416 ms: 216 on engine speed, 124 on torque, 76 on the rest
        assertEquals("10/s · engine speed 40 ms each (fastest 28, slowest 96) · torque 10/s at 31 ms · the rest 18% of the time", t.summary())
    }

    @Test
    fun resetStartsAfresh() {
        val t = LiveTiming()
        t.speedRequest(30f)
        t.torqueRequest(30f)
        t.round(70f)
        t.reset()
        assertTrue(t.isEmpty)
    }
}
