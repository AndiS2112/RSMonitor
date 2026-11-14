package com.rsmonitor.app.drive

/**
 * The highest readings since the last reset: g-force in each of the four directions, the
 * two oil temperatures, engine power and torque, and engine speed.
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

    /** [lat] is sideways g, positive to the right; [lon] is fore-aft g, positive when accelerating. */
    fun onG(lat: Float, lon: Float) {
        if (lon > accel) accel = lon
        if (-lon > brake) brake = -lon
        if (lat > right) right = lat
        if (-lat > left) left = -lat
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

    fun resetG() {
        accel = 0f
        right = 0f
        brake = 0f
        left = 0f
    }

    fun resetAll() {
        resetG()
        oil = null
        gear = null
        power = null
        torque = null
        rpm = null
    }
}
