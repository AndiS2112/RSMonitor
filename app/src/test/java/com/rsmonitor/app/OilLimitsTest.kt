package com.rsmonitor.app

import com.rsmonitor.app.car.OilLimits
import com.rsmonitor.app.car.OilLimits.Which
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OilLimitsTest {
    private val standard = OilLimits.STANDARD

    @Test
    fun standardLimits() {
        assertEquals(listOf(80, 130, 50, 115), standard.toList())
        assertTrue(standard.isValid())
    }

    @Test
    fun movesOneLimitAndLeavesTheOthers() {
        assertEquals(OilLimits(85, 130, 50, 115), standard.moved(Which.OilCold, 5))
        assertEquals(OilLimits(80, 125, 50, 115), standard.moved(Which.OilHot, -5))
        assertEquals(OilLimits(80, 130, 45, 115), standard.moved(Which.GearCold, -5))
        assertEquals(OilLimits(80, 130, 50, 120), standard.moved(Which.GearHot, 5))
        assertEquals(125, standard.moved(Which.OilHot, -5).value(Which.OilHot))
    }

    @Test
    fun stopsAtTheEndsOfTheScale() {
        assertEquals(50, standard.moved(Which.OilCold, -500).oilCold)
        assertEquals(150, standard.moved(Which.OilHot, 500).oilHot)
        assertEquals(30, standard.moved(Which.GearCold, -500).gearCold)
        assertEquals(130, standard.moved(Which.GearHot, 500).gearHot)
    }

    @Test
    fun coldStaysOneStepBelowHot() {
        assertEquals(125, standard.moved(Which.OilCold, 500).oilCold)
        assertEquals(85, standard.moved(Which.OilHot, -500).oilHot)
        assertEquals(110, standard.moved(Which.GearCold, 500).gearCold)
        assertEquals(55, standard.moved(Which.GearHot, -500).gearHot)
        // however it is moved, it stays usable
        var l = standard
        for (w in Which.values()) for (by in listOf(-500, 500, -5, 5)) {
            l = l.moved(w, by)
            assertTrue(l.isValid())
        }
    }

    @Test
    fun storedListsAreChecked() {
        assertEquals(OilLimits(90, 120, 60, 100), OilLimits.from(listOf(90, 120, 60, 100)))
        assertNull(OilLimits.from(listOf(90, 120, 60)))            // one missing
        assertNull(OilLimits.from(listOf(130, 80, 50, 115)))        // cold above hot
        assertNull(OilLimits.from(listOf(80, 160, 50, 115)))        // off the scale
        assertNull(OilLimits.from(listOf(80, 130, 20, 115)))
        assertFalse(OilLimits(80, 80, 50, 115).isValid())
    }
}
