package com.rsmonitor.app.obd

import kotlin.math.roundToInt

/** How long one kind of request took over a run of tries, in milliseconds. */
data class Timing(val name: String, val times: List<Float>, val failed: Int) {
    /** The middle time: half the tries were quicker, half slower. */
    val median: Float? get() = at(0.5f)
    val fastest: Float? get() = times.minOrNull()

    /** Nine tries in ten were quicker than this. */
    val slow: Float? get() = at(0.9f)

    /** How many such requests fit into a second, one after another. */
    val perSecond: Float? get() = median?.let { if (it > 0f) 1000f / it else null }

    private fun at(share: Float): Float? {
        if (times.isEmpty()) return null
        val sorted = times.sorted()
        return sorted[((sorted.size - 1) * share).roundToInt()]
    }
}

/**
 * The speed test: the same request many times over, timed from sending it to having the
 * whole answer. A command the adapter answers by itself shows what the adapter and Bluetooth
 * cost; a request to the car costs that plus the car's answer.
 */
object SpeedTest {
    /** Timed tries per kind of request, after [WARM_UP] untimed ones. */
    const val TRIES = 40
    const val WARM_UP = 3

    const val ADAPTER = "Adapter alone"
    const val GEARBOX = "Engine speed from the gearbox"
    const val ENGINE = "Engine speed from the engine"

    /** What the two timings say, in plain words; null unless both were measured. */
    fun verdict(adapter: Timing, car: Timing): String? {
        val alone = adapter.median ?: return null
        val whole = car.median ?: return null
        if (whole <= 0f) return null
        val rest = (whole - alone).coerceAtLeast(0f)
        val share = alone / whole
        val split = "A reading from the car takes ${whole.roundToInt()} ms. The adapter and Bluetooth alone take " +
            "${alone.roundToInt()} ms of that; the other ${rest.roundToInt()} ms are the car answering and the adapter passing the answer on."
        val meaning = when {
            share >= 0.6f -> "Most of the time is the adapter and Bluetooth: a faster adapter should help a lot."
            share >= 0.35f -> "The time is split between the two: a faster adapter should help, but not double the rate."
            else -> "The adapter and Bluetooth are the smaller part. A faster adapter helps only if this one is slow at " +
                "passing the car's answer on, which this test cannot tell from the car being slow to answer."
        }
        return "$split $meaning"
    }

    /** One line per kind of request. */
    fun line(t: Timing): String {
        val median = t.median ?: return "${t.name}: no answer"
        val failed = if (t.failed > 0) ", ${t.failed} without an answer" else ""
        return "${t.name}: ${median.roundToInt()} ms (fastest ${t.fastest!!.roundToInt()}, 9 in 10 under ${t.slow!!.roundToInt()}; " +
            "${t.perSecond!!.roundToInt()} a second$failed)"
    }

    /** The whole result as text, for sharing. */
    fun report(timings: List<Timing>, adapterName: String, note: String?): String = buildString {
        append("RS Monitor speed test · ").append(adapterName.ifEmpty { "adapter" }).append(" · $TRIES tries each\n")
        for (t in timings) append(line(t)).append('\n')
        val adapter = timings.firstOrNull { it.name == ADAPTER }
        val car = timings.firstOrNull { it.name == GEARBOX && it.median != null } ?: timings.firstOrNull { it.name == ENGINE }
        if (adapter != null && car != null) verdict(adapter, car)?.let { append(it).append('\n') }
        if (note != null) append(note).append('\n')
    }
}
