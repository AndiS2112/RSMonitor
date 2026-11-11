package com.rsmonitor.app

import androidx.car.app.navigation.model.NavigationTemplate
import com.rsmonitor.app.car.Centre
import com.rsmonitor.app.car.gaugeTemplate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android Auto refuses a screen whose buttons break its rules, and the refusal only shows in
 * the car. Building the template here applies the same rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GaugeTemplateTest {
    private fun titles(live: Boolean, centre: Centre): List<String> {
        val template = gaugeTemplate(live, centre, {}, {}) as NavigationTemplate
        return template.actionStrip!!.actions.map { it.title.toString() }
    }

    @Test
    fun theSwitchIsNamedAfterThePageItLeadsTo() {
        assertEquals(listOf("Power", "Stop live"), titles(live = true, centre = Centre.GMeter))
        assertEquals(listOf("G-meter", "Start live"), titles(live = false, centre = Centre.Power))
    }
}
