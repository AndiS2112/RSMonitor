package com.rsmonitor.app

import android.graphics.Rect
import android.graphics.RectF
import androidx.car.app.navigation.model.NavigationTemplate
import com.rsmonitor.app.car.gaugeArea
import com.rsmonitor.app.car.gaugeTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Android Auto refuses a screen whose buttons break its rules, and the refusal only shows in
 * the car. Building the template here applies the same rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GaugeTemplateTest {
    @Test
    fun theCarScreenKeepsOneIconButton() {
        val template = gaugeTemplate(RuntimeEnvironment.getApplication()) {} as NavigationTemplate
        val actions = template.actionStrip!!.actions
        assertEquals(1, actions.size)
        assertNotNull(actions[0].icon)
        assertNull(actions[0].title)
    }

    @Test
    fun theGaugesTakeTheStripHeldBackForButtons() {
        // Android Auto leaves a margin all round and holds the top back for the buttons
        assertEquals(RectF(12f, 12f, 1168f, 618f), gaugeArea(Rect(12, 108, 1168, 618), 1180, 630))
    }

    @Test
    fun theTopIsNeverPushedDown() {
        assertEquals(RectF(12f, 4f, 1168f, 618f), gaugeArea(Rect(12, 4, 1168, 618), 1180, 630))
        assertEquals(RectF(0f, 0f, 1180f, 600f), gaugeArea(Rect(0, 90, 1180, 600), 1180, 630))
    }

    @Test
    fun withoutAReportedAreaTheWholeSurfaceIsUsed() {
        assertEquals(RectF(0f, 0f, 1180f, 630f), gaugeArea(null, 1180, 630))
        assertEquals(RectF(0f, 0f, 1180f, 630f), gaugeArea(Rect(), 1180, 630))
    }
}
