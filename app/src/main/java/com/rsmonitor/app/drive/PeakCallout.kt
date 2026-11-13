package com.rsmonitor.app.drive

/**
 * The car screen marks each oil's peak with an amber hand, but the exact peak temperatures
 * stay off it until they are asked for from the phone. Asking shows them for [showForMillis]; asking again while they show puts them away at once.
 * Plain state, no Android types; times are any steadily rising clock, in milliseconds.
 */
class PeakCallout(private val showForMillis: Long = SHOW_FOR_MS) {
    /** The moment the peaks go away again, or [HIDDEN]. */
    @Volatile
    var until = HIDDEN
        private set

    fun isShown(now: Long): Boolean = now < until

    /** Shows the peaks, or hides them if they are showing. Returns the new [until]. */
    @Synchronized
    fun toggle(now: Long): Long {
        until = if (isShown(now)) HIDDEN else now + showForMillis
        return until
    }

    companion object {
        const val SHOW_FOR_MS = 10_000L
        const val HIDDEN = Long.MIN_VALUE
    }
}
