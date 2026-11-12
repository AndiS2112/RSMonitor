package com.rsmonitor.app.car

/**
 * The five-stage shift light. One green light from 3,500 rpm, three yellow from 4,000, 5,000
 * and 6,000, one red from 7,500. From 8,000 rpm all five flash red.
 * Colours are ARGB ints so this file needs no Android types and can be unit-tested.
 */
object ShiftLights {
    val ON_AT = intArrayOf(3500, 4000, 5000, 6000, 7500)
    const val FLASH_AT = 8000

    /** Flash period: on for half, off for half. Four flashes a second. */
    const val FLASH_PERIOD_MS = 250L

    val GREEN = 0xFF2FD158.toInt()
    val YELLOW = 0xFFFFD60A.toInt()
    val RED = 0xFFFF1A1A.toInt()

    private val COLOURS = intArrayOf(GREEN, YELLOW, YELLOW, YELLOW, RED)

    fun isFlashing(rpm: Int): Boolean = rpm >= FLASH_AT

    /** The colour of each light, left to right, or null where a light is off. */
    fun colours(rpm: Int): List<Int?> =
        if (isFlashing(rpm)) List(ON_AT.size) { RED }
        else ON_AT.indices.map { i -> if (rpm >= ON_AT[i]) COLOURS[i] else null }
}
