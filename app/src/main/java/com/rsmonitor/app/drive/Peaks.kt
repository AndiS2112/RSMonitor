package com.rsmonitor.app.drive

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The highest readings since the last reset: g-force in each of the four directions, the
 * outline of every g-force seen, the two oil temperatures, engine power and torque, and
 * engine speed.
 * Plain state, no Android types.
 */
class Peaks {
    var accel = 0f; private set
    var right = 0f; private set
    var brake = 0f; private set
    var left = 0f; private set
    var oil: Int? = null; private set
    var gear: Int? = null; private set
    var power: Int? = null; private set
    var torque: Int? = null; private set
    var rpm: Int? = null; private set

    private val envelope = FloatArray(BINS)

    /** [lat] is sideways g, positive to the right; [lon] is fore-aft g, positive when accelerating. */
    fun onG(lat: Float, lon: Float) {
        if (lon > accel) accel = lon
        if (-lon > brake) brake = -lon
        if (lat > right) right = lat
        if (-lat > left) left = -lat

        // Each reading pushes the outline out towards itself: full strength in its own
        // direction, fading to nothing at right angles. The outline never exceeds a reading.
        val size = hypot(lat, lon)
        if (size < 0.01f) return
        val direction = atan2(lat, lon)   // clockwise from "accelerating"
        for (i in 0 until BINS) {
            val share = cos(i * STEP - direction)
            if (share > 0f) {
                val reach = size * share
                if (reach > envelope[i]) envelope[i] = reach
            }
        }
    }

    fun onOil(celsius: Int) {
        val now = oil
        if (now == null || celsius > now) oil = celsius
    }

    fun onGear(celsius: Int) {
        val now = gear
        if (now == null || celsius > now) gear = celsius
    }

    /** Power in metric horsepower and torque in Nm, read at the same moment. */
    fun onPower(horsepower: Int, newtonMetres: Int) {
        val p = power
        if (p == null || horsepower > p) power = horsepower
        val t = torque
        if (t == null || newtonMetres > t) torque = newtonMetres
    }

    fun onEngineSpeed(revs: Int) {
        val now = rpm
        if (now == null || revs > now) rpm = revs
    }

    /** The outline as g per direction, [BINS] values clockwise from "accelerating". Empty if nothing was recorded. */
    fun trace(): List<Float> = if (envelope.any { it > 0f }) envelope.toList() else emptyList()

    fun resetG() {
        accel = 0f
        right = 0f
        brake = 0f
        left = 0f
        envelope.fill(0f)
    }

    fun resetAll() {
        resetG()
        oil = null
        gear = null
        power = null
        torque = null
        rpm = null
    }

    companion object {
        const val BINS = 72
        private val STEP = (2 * PI / BINS).toFloat()
    }
}
