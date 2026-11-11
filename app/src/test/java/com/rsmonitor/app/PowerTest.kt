package com.rsmonitor.app

import com.rsmonitor.app.drive.metricHorsepower
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerTest {
    @Test
    fun theCarsOwnFigures() {
        // RS5 4.2 FSI: 430 Nm from 4,000 to 6,000 rpm, 450 PS at 8,250 rpm
        assertEquals(306, metricHorsepower(430, 5000))
        assertEquals(450, metricHorsepower(383, 8250))
    }

    @Test
    fun nothingAtRestOrOnTheOverrun() {
        assertEquals(0, metricHorsepower(0, 3000))
        assertEquals(0, metricHorsepower(120, 0))
    }
}
