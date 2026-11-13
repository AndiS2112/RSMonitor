package com.rsmonitor.app

import com.rsmonitor.app.drive.PeakCallout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakCalloutTest {
    @Test
    fun hiddenUntilAskedFor() {
        val callout = PeakCallout()
        assertFalse(callout.isShown(0))
        assertFalse(callout.isShown(1_000_000))
        assertEquals(PeakCallout.HIDDEN, callout.until)
    }

    @Test
    fun showsForTenSecondsThenGoesAway() {
        val callout = PeakCallout()
        assertEquals(15_000L, callout.toggle(5_000))
        assertTrue(callout.isShown(5_000))
        assertTrue(callout.isShown(14_999))
        assertFalse(callout.isShown(15_000))
    }

    @Test
    fun askingAgainWhileShownHidesAtOnce() {
        val callout = PeakCallout()
        callout.toggle(5_000)
        assertEquals(PeakCallout.HIDDEN, callout.toggle(8_000))
        assertFalse(callout.isShown(8_000))
    }

    @Test
    fun askingAgainAfterItWentAwayShowsAFreshTenSeconds() {
        val callout = PeakCallout()
        callout.toggle(5_000)
        assertEquals(40_000L, callout.toggle(30_000))
        assertTrue(callout.isShown(39_999))
    }
}
