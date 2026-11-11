package com.rsmonitor.app

import com.rsmonitor.app.drive.GTracker
import com.rsmonitor.app.drive.GTracker.Stage
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Feeds the tracker what a phone lying at an angle in a cupholder would feel. */
class GTrackerTest {
    private val g = GTracker.GRAVITY

    // The car's axes as the tilted phone sees them: up, forward, and right = forward × up.
    private val up = unit(0.2f, 0.3f, 0.93f)
    private val forward = unit(cross(up, floatArrayOf(1f, 0f, 0f)))
    private val right = cross(forward, up)

    private val tracker = GTracker()
    private var nanos = 1_000_000L

    /** [seconds] of driving at 50 readings a second, with the car accelerating by the given g. */
    private fun drive(seconds: Float, forwardG: Float = 0f, rightG: Float = 0f) {
        repeat((seconds * 50).toInt()) {
            nanos += 20_000_000L
            tracker.onSample(
                g * (up[0] + forwardG * forward[0] + rightG * right[0]),
                g * (up[1] + forwardG * forward[1] + rightG * right[1]),
                g * (up[2] + forwardG * forward[2] + rightG * right[2]),
                nanos,
            )
        }
    }

    private fun calibrated() {
        tracker.calibrate()
        drive(3f)                      // parked
        assertEquals(Stage.AwaitingPullAway, tracker.stage)
        drive(2f, forwardG = 0.3f)     // pulling away
        assertEquals(Stage.Ready, tracker.stage)
    }

    @Test
    fun ignoresEverythingUntilCalibrated() {
        drive(2f, forwardG = 0.5f)
        assertEquals(Stage.Uncalibrated, tracker.stage)
        assertEquals(0f, tracker.lon, 0f)
    }

    @Test
    fun waitsParkedUntilTheCarPullsAway() {
        tracker.calibrate()
        assertEquals(Stage.Levelling, tracker.stage)
        drive(10f)
        assertEquals(Stage.AwaitingPullAway, tracker.stage)
        drive(0.5f, forwardG = 0.3f)   // too short to count
        drive(1f)
        assertEquals(Stage.AwaitingPullAway, tracker.stage)
    }

    @Test
    fun acceleratingIsPositiveForeAft() {
        calibrated()
        drive(2f, forwardG = 0.3f)
        assertEquals(0.3f, tracker.lon, 0.02f)
        assertEquals(0f, tracker.lat, 0.02f)
    }

    @Test
    fun brakingIsNegativeForeAft() {
        calibrated()
        drive(2f, forwardG = -0.5f)
        assertEquals(-0.5f, tracker.lon, 0.02f)
        assertEquals(0f, tracker.lat, 0.02f)
    }

    @Test
    fun turningRightIsPositiveSideways() {
        calibrated()
        drive(2f, rightG = 0.4f)
        assertEquals(0.4f, tracker.lat, 0.02f)
        assertEquals(0f, tracker.lon, 0.02f)
    }

    @Test
    fun bumpsAndDipsAreIgnored() {
        calibrated()
        drive(2f)
        // straight up and down: the reading grows along "up" only
        repeat(50) {
            nanos += 20_000_000L
            tracker.onSample(g * up[0] * 1.4f, g * up[1] * 1.4f, g * up[2] * 1.4f, nanos)
        }
        assertEquals(0f, tracker.lon, 0.02f)
        assertEquals(0f, tracker.lat, 0.02f)
    }

    @Test
    fun flipSwapsFrontAndBack() {
        calibrated()
        tracker.flip()
        drive(2f, forwardG = 0.3f, rightG = 0.2f)
        assertEquals(-0.3f, tracker.lon, 0.02f)
        assertEquals(-0.2f, tracker.lat, 0.02f)
    }

    private fun unit(x: Float, y: Float, z: Float) = unit(floatArrayOf(x, y, z))

    private fun unit(v: FloatArray): FloatArray {
        val size = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return floatArrayOf(v[0] / size, v[1] / size, v[2] / size)
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )
}
