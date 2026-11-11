package com.rsmonitor.app.drive

import kotlin.math.sqrt

/**
 * Turns raw phone accelerometer readings into the car's sideways and fore-aft g-force,
 * however the phone happens to be lying. Plain maths, no Android types.
 *
 * Calibration has two steps, because a phone at rest can feel which way is down but not
 * which way the car points:
 *  1. Parked: the reading is averaged to find "down" (and to remember gravity).
 *  2. Pulling away in a straight line: the first steady push is taken as "forward".
 */
class GTracker {
    enum class Stage { Uncalibrated, Levelling, AwaitingPullAway, Ready }

    var stage = Stage.Uncalibrated
        private set

    /** Sideways g, positive to the right. Zero until [Stage.Ready]. */
    var lat = 0f
        private set

    /** Fore-aft g, positive when accelerating. Zero until [Stage.Ready]. */
    var lon = 0f
        private set

    private val rest = FloatArray(3)      // the reading when parked: gravity, in phone axes
    private val up = FloatArray(3)
    private val forward = FloatArray(3)
    private val rightward = FloatArray(3)
    private val smooth = FloatArray(3)    // car acceleration in the road plane, low-passed, m/s²
    private val pull = FloatArray(3)      // direction of the push being timed in step 2
    private val sum = FloatArray(3)
    private var count = 0
    private var stepStart = 0L
    private var pullSince = 0L
    private var lastNanos = 0L

    /** Starts step 1. Call with the car parked and the phone already in its place. */
    fun calibrate() {
        stage = Stage.Levelling
        sum.fill(0f)
        count = 0
        stepStart = 0L
        lat = 0f
        lon = 0f
    }

    /** Swaps front and back, for when step 2 happened while reversing. */
    fun flip() {
        if (stage != Stage.Ready) return
        for (i in 0..2) {
            forward[i] = -forward[i]
            rightward[i] = -rightward[i]
        }
        lat = -lat
        lon = -lon
    }

    /** One accelerometer reading in m/s² with its timestamp in nanoseconds. */
    fun onSample(x: Float, y: Float, z: Float, nanos: Long) {
        when (stage) {
            Stage.Uncalibrated -> return
            Stage.Levelling -> {
                if (stepStart == 0L) stepStart = nanos
                val elapsed = nanos - stepStart
                if (elapsed < SETTLE_NANOS) return   // let the phone settle after the tap
                sum[0] += x; sum[1] += y; sum[2] += z
                count++
                if (elapsed >= SETTLE_NANOS + LEVEL_NANOS && count >= 10) {
                    for (i in 0..2) rest[i] = sum[i] / count
                    val size = length(rest)
                    if (size < 1f) {          // no gravity in the reading: not a usable sensor value
                        calibrate()
                        return
                    }
                    for (i in 0..2) up[i] = rest[i] / size
                    smooth.fill(0f)
                    pullSince = 0L
                    lastNanos = nanos
                    stage = Stage.AwaitingPullAway
                }
                return
            }
            else -> Unit
        }

        // what the car is doing = reading minus gravity, with any up-down part removed
        var dx = x - rest[0]
        var dy = y - rest[1]
        var dz = z - rest[2]
        val vertical = dx * up[0] + dy * up[1] + dz * up[2]
        dx -= vertical * up[0]
        dy -= vertical * up[1]
        dz -= vertical * up[2]

        val dt = (nanos - lastNanos).coerceIn(0L, 200_000_000L) / 1e9f
        lastNanos = nanos
        val k = dt / (SMOOTHING_SECONDS + dt)
        smooth[0] += k * (dx - smooth[0])
        smooth[1] += k * (dy - smooth[1])
        smooth[2] += k * (dz - smooth[2])

        if (stage == Stage.AwaitingPullAway) {
            val size = length(smooth)
            if (size < PULL_MIN) {
                pullSince = 0L
                return
            }
            val steady = pullSince != 0L && dot(smooth, pull) / (size * length(pull)) >= 0.9f
            if (!steady) {
                pullSince = nanos
                smooth.copyInto(pull)
                return
            }
            if (nanos - pullSince >= PULL_NANOS) {
                for (i in 0..2) forward[i] = smooth[i] / size
                // right = forward × up
                rightward[0] = forward[1] * up[2] - forward[2] * up[1]
                rightward[1] = forward[2] * up[0] - forward[0] * up[2]
                rightward[2] = forward[0] * up[1] - forward[1] * up[0]
                stage = Stage.Ready
            }
            return
        }

        lon = dot(smooth, forward) / GRAVITY
        lat = dot(smooth, rightward) / GRAVITY
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun length(a: FloatArray) = sqrt(dot(a, a))

    companion object {
        const val GRAVITY = 9.80665f
        private const val SETTLE_NANOS = 1_000_000_000L
        private const val LEVEL_NANOS = 1_500_000_000L
        private const val SMOOTHING_SECONDS = 0.2f
        /** A push of at least 0.08 g … */
        private const val PULL_MIN = 0.08f * GRAVITY
        /** … held in one direction for a second counts as pulling away. */
        private const val PULL_NANOS = 1_000_000_000L
    }
}
