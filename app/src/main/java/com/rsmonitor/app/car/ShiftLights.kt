package com.rsmonitor.app.car

/**
 * The five-stage shift light: one green light, three yellow, one red, each coming on at its
 * own engine speed, and from a sixth speed all five flashing red.
 *
 * The six speeds can be changed from the phone while driving; see [steps]. As built they are
 * 3,300, 3,800, 4,800, 5,800 and 7,300 rpm, flashing from 7,800: each 200 rpm below a round
 * figure, to allow for the time a reading takes to reach the car screen.
 *
 * Colours are ARGB ints so this file needs no Android types and can be unit-tested.
 */
object ShiftLights {
    const val LIGHTS = 5

    /** The speeds as built: where each of the five lights comes on, then where all flash. */
    val STANDARD: List<Int> = listOf(3300, 3800, 4800, 5800, 7300, 7800)

    /** One tap on the phone moves a speed by this much. */
    const val STEP = 100
    const val LOWEST = 1000
    const val HIGHEST = 9000

    /** Flash period: on for half, off for half. Four flashes a second. */
    const val FLASH_PERIOD_MS = 250L

    val GREEN = 0xFF2FD158.toInt()
    val YELLOW = 0xFFFFD60A.toInt()
    val RED = 0xFFFF1A1A.toInt()

    private val COLOURS = intArrayOf(GREEN, YELLOW, YELLOW, YELLOW, RED)

    @Volatile
    private var current: List<Int> = STANDARD

    /** The speeds in use: [LIGHTS] values for the lights, left to right, then the flash. */
    val steps: List<Int> get() = current

    /** Puts new speeds into use at once. Ignored unless they are [isValid]. */
    fun use(steps: List<Int>) {
        if (isValid(steps)) current = steps.toList()
    }

    /** Six speeds within range, none below the one before it. */
    fun isValid(steps: List<Int>): Boolean =
        steps.size == LIGHTS + 1 && steps.all { it in LOWEST..HIGHEST } && steps.zipWithNext().all { (a, b) -> a <= b }

    /**
     * [steps] with the speed at [index] moved by [by] rpm. It stops at its neighbours: a
     * light never comes on below the one before it or above the one after it.
     */
    fun moved(steps: List<Int>, index: Int, by: Int): List<Int> {
        val lower = if (index == 0) LOWEST else steps[index - 1]
        val upper = if (index == steps.lastIndex) HIGHEST else steps[index + 1]
        return steps.toMutableList().also { it[index] = (it[index] + by).coerceIn(lower, upper) }
    }

    fun isFlashing(rpm: Int): Boolean = rpm >= current[LIGHTS]

    /** The colour of each light, left to right, or null where a light is off. */
    fun colours(rpm: Int): List<Int?> {
        val at = current
        return if (rpm >= at[LIGHTS]) List(LIGHTS) { RED } else List(LIGHTS) { i -> if (rpm >= at[i]) COLOURS[i] else null }
    }
}
