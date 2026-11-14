package com.rsmonitor.app

import com.rsmonitor.app.drive.Peaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeaksTest {
    @Test
    fun keepsTheHighestInEachDirection() {
        val p = Peaks()
        p.onG(lat = 0.5f, lon = 0.2f)
        p.onG(lat = -0.3f, lon = -0.8f)
        p.onG(lat = 0.1f, lon = 0.1f)
        assertEquals(0.2f, p.accel, 1e-6f)
        assertEquals(0.8f, p.brake, 1e-6f)
        assertEquals(0.5f, p.right, 1e-6f)
        assertEquals(0.3f, p.left, 1e-6f)
    }

    @Test
    fun oilPeaksOnlyRise() {
        val p = Peaks()
        assertNull(p.oil)
        p.onOil(90); p.onOil(104); p.onOil(97)
        p.onGear(70); p.onGear(66)
        assertEquals(104, p.oil)
        assertEquals(70, p.gear)
    }

    @Test
    fun powerAndTorquePeakSeparately() {
        val p = Peaks()
        assertNull(p.power)
        p.onPower(306, 430)   // peak torque, mid power
        p.onPower(450, 383)   // peak power, torque already falling
        p.onPower(38, 112)
        assertEquals(450, p.power)
        assertEquals(430, p.torque)
        p.resetG()
        assertEquals(450, p.power)
        p.resetAll()
        assertNull(p.power)
        assertNull(p.torque)
    }

    @Test
    fun resetPeakGLeavesTheOilPeaks() {
        val p = Peaks()
        p.onG(0.4f, 0.4f)
        p.onOil(110)
        p.resetG()
        assertEquals(0f, p.accel, 0f)
        assertEquals(0f, p.right, 0f)
        assertEquals(110, p.oil)
    }

    @Test
    fun carOffClearsEverything() {
        val p = Peaks()
        p.onG(0.4f, -0.4f)
        p.onOil(110)
        p.onGear(95)
        p.resetAll()
        assertEquals(0f, p.brake, 0f)
        assertNull(p.oil)
        assertNull(p.gear)
    }

    @Test
    fun engineSpeedPeakOnlyRisesAndClearsWithTheRest() {
        val p = Peaks()
        assertNull(p.rpm)
        p.onEngineSpeed(2400); p.onEngineSpeed(8090); p.onEngineSpeed(6400)
        assertEquals(8090, p.rpm)
        p.resetG()
        assertEquals(8090, p.rpm)
        p.resetAll()
        assertNull(p.rpm)
    }
}
