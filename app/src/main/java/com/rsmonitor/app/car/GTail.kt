package com.rsmonitor.app.car

/**
 * The path of the G-meter's dot over the last few seconds, for the tail drawn behind it.
 * Plain state, no Android types.
 */
class GTail(private val seconds: Float = SECONDS) {
    private val time = LongArray(CAPACITY)
    private val lat = FloatArray(CAPACITY)
    private val lon = FloatArray(CAPACITY)
    private var next = 0
    private var count = 0

    /** [sideways] is positive to the right and [foreAft] positive when accelerating, both in g. */
    fun add(atMs: Long, sideways: Float, foreAft: Float) {
        time[next] = atMs
        lat[next] = sideways
        lon[next] = foreAft
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    fun clear() {
        count = 0
    }

    /** The readings no older than the tail is long, newest first. */
    fun points(nowMs: Long): GTailPoints {
        val limitMs = (seconds * 1000f).toLong()
        var n = 0
        while (n < count && nowMs - time[slot(n)] <= limitMs) n++
        if (n == 0) return GTailPoints.NONE
        val values = FloatArray(n * 3)
        for (i in 0 until n) {
            val s = slot(i)
            values[i * 3] = lat[s]
            values[i * 3 + 1] = lon[s]
            values[i * 3 + 2] = (nowMs - time[s]) / 1000f
        }
        return GTailPoints(values)
    }

    /** Where the reading [back] places before the newest is kept. */
    private fun slot(back: Int) = (next - 1 - back + 2 * CAPACITY) % CAPACITY

    companion object {
        /** How far back the tail reaches. */
        const val SECONDS = 3f

        // room for more than [SECONDS] of readings at 30 a second
        private const val CAPACITY = 128
    }
}

/**
 * A tail as it stands at one moment: for each point, newest first, sideways g, fore-aft g
 * and its age in seconds. Two of these are equal when they hold the same numbers, so a
 * screen that has not changed is not repainted.
 */
class GTailPoints(private val values: FloatArray) {
    val size: Int get() = values.size / 3

    fun lat(i: Int) = values[i * 3]
    fun lon(i: Int) = values[i * 3 + 1]
    fun age(i: Int) = values[i * 3 + 2]

    override fun equals(other: Any?) = other is GTailPoints && values.contentEquals(other.values)
    override fun hashCode() = values.contentHashCode()

    companion object {
        val NONE = GTailPoints(FloatArray(0))
    }
}
