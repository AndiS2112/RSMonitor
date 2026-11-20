package com.rsmonitor.app.car

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * The red glow behind the dials while the shift lights flash. It comes up quickly when the
 * flash starts, pulses in step with the lights without ever going fully dark, and dies away
 * a little slower after the shift. Plain state, no Android types.
 */
class ShiftGlow {
    private var envelope = 0f
    private var last = -1L

    /**
     * The glow's strength now, from 0 (none) to 1. Call once for every frame, with the same
     * clock the lights' flash is timed by, so the two stay in step.
     */
    fun level(nowMs: Long, flashing: Boolean): Float {
        val dt = if (last < 0) FIRST_FRAME_MS else (nowMs - last).coerceIn(0L, LONGEST_STEP_MS)
        last = nowMs
        val target = if (flashing) 1f else 0f
        envelope += (target - envelope) * min(1f, dt / (if (flashing) RISE_MS else FALL_MS))
        // settle at exactly nothing, so a screen at rest is not repainted for it
        if (!flashing && envelope < 0.01f) envelope = 0f
        return envelope * pulse(nowMs)
    }

    companion object {
        /** The dimmest the pulse gets, as a share of its brightest. */
        const val LOW = 0.30f

        private const val RISE_MS = 70f
        private const val FALL_MS = 200f
        private const val FIRST_FRAME_MS = 33L
        private const val LONGEST_STEP_MS = 100L

        /**
         * The pulse on its own: brightest in the middle of the half-period the lights are lit
         * (the first half of every flash period) and at [LOW] in the middle of the dark half.
         */
        fun pulse(nowMs: Long): Float {
            val phase = (nowMs % ShiftLights.FLASH_PERIOD_MS) / ShiftLights.FLASH_PERIOD_MS.toFloat()
            return LOW + (1f - LOW) * (0.5f + 0.5f * cos(2.0 * PI * (phase - 0.25)).toFloat())
        }
    }
}
