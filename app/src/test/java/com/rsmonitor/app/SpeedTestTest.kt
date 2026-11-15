package com.rsmonitor.app

import com.rsmonitor.app.obd.SpeedTest
import com.rsmonitor.app.obd.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {
    private fun timing(name: String, vararg ms: Float, failed: Int = 0) = Timing(name, ms.toList(), failed)

    @Test
    fun middleFastestAndNineInTen() {
        val t = timing("x", 50f, 10f, 30f, 20f, 40f, 60f, 70f, 80f, 90f, 100f, 110f)
        assertEquals(60f, t.median!!, 0f)
        assertEquals(10f, t.fastest!!, 0f)
        assertEquals(100f, t.slow!!, 0f)
        assertEquals(1000f / 60f, t.perSecond!!, 1e-4f)
    }

    @Test
    fun nothingMeasuredGivesNoNumbers() {
        val t = timing("x", failed = 40)
        assertNull(t.median)
        assertNull(t.fastest)
        assertNull(t.slow)
        assertNull(t.perSecond)
        assertEquals("x: no answer", SpeedTest.line(t))
    }

    @Test
    fun verdictNamesTheSplit() {
        val car = timing(SpeedTest.GEARBOX, 55f, 55f, 55f)
        // adapter and Bluetooth are most of a reading
        val slowAdapter = SpeedTest.verdict(timing(SpeedTest.ADAPTER, 40f, 40f, 40f), car)!!
        assertTrue(slowAdapter.startsWith("A reading from the car takes 55 ms. The adapter and Bluetooth alone take 40 ms of that; the other 15 ms"))
        assertTrue(slowAdapter.endsWith("a faster adapter should help a lot."))
        // split
        assertTrue(SpeedTest.verdict(timing(SpeedTest.ADAPTER, 25f), car)!!.endsWith("but not double the rate."))
        // the adapter alone is quick
        assertTrue(SpeedTest.verdict(timing(SpeedTest.ADAPTER, 8f), car)!!.endsWith("cannot tell from the car being slow to answer."))
        // an adapter-only time above the car's is not a negative remainder
        assertTrue(SpeedTest.verdict(timing(SpeedTest.ADAPTER, 70f), car)!!.contains("the other 0 ms"))
    }

    @Test
    fun noVerdictWithoutBothTimings() {
        assertNull(SpeedTest.verdict(timing(SpeedTest.ADAPTER), timing(SpeedTest.GEARBOX, 55f)))
        assertNull(SpeedTest.verdict(timing(SpeedTest.ADAPTER, 20f), timing(SpeedTest.GEARBOX, failed = 40)))
    }

    @Test
    fun reportListsEveryTimingAndTheVerdict() {
        val report = SpeedTest.report(
            listOf(
                timing(SpeedTest.ADAPTER, 30f, 32f, 34f),
                timing(SpeedTest.GEARBOX, 54f, 55f, 58f, failed = 1),
                timing(SpeedTest.ENGINE, 60f, 61f, 65f),
            ),
            "OBDII",
            null,
        )
        val lines = report.trim().lines()
        assertEquals("RS Monitor speed test · OBDII · 40 tries each", lines[0])
        assertEquals("Adapter alone: 32 ms (fastest 30, 9 in 10 under 34; 31 a second)", lines[1])
        assertEquals("Engine speed from the gearbox: 55 ms (fastest 54, 9 in 10 under 58; 18 a second, 1 without an answer)", lines[2])
        assertTrue(lines[3].startsWith("Engine speed from the engine: 61 ms"))
        assertTrue(lines[4].startsWith("A reading from the car takes 55 ms."))
        assertEquals(5, lines.size)
    }

    @Test
    fun reportFallsBackToTheEngineWhenTheGearboxIsSilent() {
        val report = SpeedTest.report(
            listOf(timing(SpeedTest.ADAPTER, 30f), timing(SpeedTest.GEARBOX, failed = 40), timing(SpeedTest.ENGINE, 60f)),
            "",
            "A note.",
        )
        assertTrue(report.startsWith("RS Monitor speed test · adapter · "))
        assertTrue(report.contains("Engine speed from the gearbox: no answer"))
        assertTrue(report.contains("A reading from the car takes 60 ms."))
        assertTrue(report.trim().endsWith("A note."))
    }
}
