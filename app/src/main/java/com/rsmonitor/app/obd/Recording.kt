package com.rsmonitor.app.obd

/**
 * What a drive recording asks the gearbox for, and how a row of it is written. The point of
 * a recording is to see which values follow the gear and the load while the car is driven,
 * without anyone reading a phone.
 *
 * Every row holds the five values that matter most. They are asked for in two requests when
 * the gearbox answers several IDs at once (the adapter can send at most three IDs in one
 * request), otherwise one by one. One further ID is added per row, taking turns, and every
 * few rows the engine is asked for its speed so the gearbox's figure can be checked against
 * it.
 */
object RecordPlan {
    /** In every row: engine speed (quarter-rpm), engine torque (Nm), selector, two gear candidates. */
    val FAST = listOf(0x2102, 0x2002, 0x210C, 0x210E, 0x210F)

    /** The fast values as they are asked for together, each group with the size of every value in bytes. */
    val GROUPS = listOf(
        Group(listOf(0x2102, 0x2002, 0x210C), listOf(2, 2, 1)),
        Group(listOf(0x210E, 0x210F), listOf(1, 1)),
    )

    class Group(val ids: List<Int>, val sizes: List<Int>) {
        val request = "22" + ids.joinToString("") { "%04X".format(it) }
    }

    /** One of these per row, in turn: everything else the gearbox answered in the scans. */
    val ROTATING = listOf(
        0x2142, 0x2143, 0x214C, 0x214D, 0x214E, 0x2105, 0x2106, 0x2103,
        0x2108, 0x210B, 0x2110, 0x2111, 0x2112, 0x2113, 0x2114,
        0x2144, 0x2145, 0x2146, 0x2147, 0x2148, 0x2149, 0x214A, 0x214B,
    )

    /** The engine is asked for its speed in every row whose number divides by this. */
    const val ENGINE_EVERY = 10

    /** A recording stops by itself here; over an hour at the faster pace. */
    const val MAX_ROWS = 20_000

    const val COLUMNS = "t_ms,2102,2002,210C,210E,210F,extra,rpm_010C"
}

private fun hex(bytes: List<Int>?): String = bytes?.joinToString("") { "%02X".format(it) } ?: ""

/**
 * One row: milliseconds since the start, the five fast values as hex (empty when one did not
 * answer), the extra ID as `2142=00D4`, and the engine's own speed in rpm when it was asked.
 */
fun recordRow(millis: Long, fast: List<List<Int>?>, extraId: Int, extra: List<Int>?, engineRpm: Int?): String =
    buildString {
        append(millis)
        for (value in fast) append(',').append(hex(value))
        append(',').append("%04X".format(extraId)).append('=').append(hex(extra))
        append(',')
        if (engineRpm != null) append(engineRpm)
    }
