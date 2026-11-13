package com.rsmonitor.app

import com.rsmonitor.app.drive.Gear
import com.rsmonitor.app.obd.gearFrom
import com.rsmonitor.app.obd.gearboxEngineSpeed
import com.rsmonitor.app.obd.gearboxEngineTorque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gearbox's values as they were recorded on the car. */
class GearboxTest {
    @Test
    fun engineSpeedInQuarterRpm() {
        assertEquals(675, gearboxEngineSpeed(listOf(0x0A, 0x8C)))   // idle, rev counter at about 700
        assertEquals(8087, gearboxEngineSpeed(listOf(0x7E, 0x5C)))  // top of the pull in second
        assertNull(gearboxEngineSpeed(listOf(0x0A)))
        assertNull(gearboxEngineSpeed(null))
    }

    @Test
    fun torqueUnderLoad() {
        assertEquals(4, gearboxEngineTorque(listOf(0x00, 0x04)))     // idling in P
        assertEquals(14, gearboxEngineTorque(listOf(0x00, 0x0E)))    // D, brake held
        assertEquals(394, gearboxEngineTorque(listOf(0x01, 0x8A)))   // full throttle in third
    }

    @Test
    fun torqueOnTheOverrun() {
        assertEquals(-50, gearboxEngineTorque(listOf(0x0C, 0x9B)))   // lifted off at 1,500 rpm
        assertEquals(-110, gearboxEngineTorque(listOf(0x0C, 0x5F)))  // lifted off at 6,400 rpm
        assertEquals(-96, gearboxEngineTorque(listOf(0x0C, 0x6D)))   // ignition on, engine stopped
        assertEquals(0, gearboxEngineTorque(listOf(0x0C, 0xCD)))
    }

    @Test
    fun noTorqueFromNonsense() {
        assertNull(gearboxEngineTorque(listOf(0xFF, 0xFF)))
        assertNull(gearboxEngineTorque(listOf(0x01)))
        assertNull(gearboxEngineTorque(null))
    }

    @Test
    fun parkedAndStanding() {
        assertEquals(Gear('P'), gearFrom(engaged = 2, selector = 0, mode = 0x0B))
        assertEquals(Gear('R'), gearFrom(engaged = 1, selector = 1, mode = 0x0B))
        assertEquals(Gear('N'), gearFrom(engaged = 2, selector = 2, mode = 0x0B))
        assertEquals(Gear('D', 1), gearFrom(engaged = 3, selector = 3, mode = 0x0B))
    }

    @Test
    fun forwardGears() {
        assertEquals(Gear('D', 2), gearFrom(engaged = 4, selector = 3, mode = 0x0B))
        assertEquals(Gear('D', 7), gearFrom(engaged = 9, selector = 3, mode = 0x0B))
        // chosen by hand
        assertEquals(Gear('M', 3), gearFrom(engaged = 5, selector = 3, mode = 0x03))
    }

    @Test
    fun withValuesMissing() {
        // the gear alone is enough for a forward gear or reverse
        assertEquals(Gear('D', 4), gearFrom(engaged = 6, selector = null, mode = null))
        assertEquals(Gear('R'), gearFrom(engaged = 1, selector = null, mode = null))
        // nothing engaged and no selector: neutral is all that can be said
        assertEquals(Gear('N'), gearFrom(engaged = 2, selector = null, mode = null))
        // the selector alone, while the gear has not answered yet
        assertEquals(Gear('P'), gearFrom(engaged = null, selector = 0, mode = null))
        assertEquals(Gear('D'), gearFrom(engaged = null, selector = 3, mode = null))
        assertNull(gearFrom(null, null, null))
    }
}
