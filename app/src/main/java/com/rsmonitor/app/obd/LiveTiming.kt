package com.rsmonitor.app.obd

import kotlin.math.roundToInt

/**
 * Where live data's time goes over a stretch of rounds, summed up as one line for the
 * console. It answers "why so few readings a second?" from a real drive: slow requests, or
 * time spent on everything else. Times are in milliseconds.
 */
class LiveTiming {
    private val speed = ArrayList<Float>()
    private var torqueMs = 0f
    private var torqueCount = 0
    private var roundsMs = 0f

    /** One request for engine speed took [ms], answered or not. */
    fun speedRequest(ms: Float) {
        speed += ms
    }

    fun torqueRequest(ms: Float) {
        torqueMs += ms
        torqueCount++
    }

    /** One whole round of the live loop took [ms]. */
    fun round(ms: Float) {
        roundsMs += ms
    }

    val isEmpty: Boolean get() = speed.isEmpty() || roundsMs <= 0f

    fun reset() {
        speed.clear()
        torqueMs = 0f
        torqueCount = 0
        roundsMs = 0f
    }

    /**
     * For example "17/s · engine speed 52 ms each (fastest 28, slowest 96) · torque 9/s at
     * 31 ms · the rest 14% of the time". "The rest" is every other request (gear, selector,
     * the two oil temperatures, switching between gearbox and engine) and the loop itself.
     */
    fun summary(): String {
        if (isEmpty) return "no readings"
        val sorted = speed.sorted()
        val typical = sorted[(sorted.size - 1) / 2]
        val seconds = roundsMs / 1000f
        val rest = ((roundsMs - speed.sum() - torqueMs) / roundsMs * 100f).coerceIn(0f, 100f)
        val torque = if (torqueCount == 0) "" else
            " · torque ${(torqueCount / seconds).roundToInt()}/s at ${(torqueMs / torqueCount).roundToInt()} ms"
        return "${(speed.size / seconds).roundToInt()}/s · engine speed ${typical.roundToInt()} ms each " +
            "(fastest ${sorted.first().roundToInt()}, slowest ${sorted.last().roundToInt()})$torque · the rest ${rest.roundToInt()}% of the time"
    }
}
