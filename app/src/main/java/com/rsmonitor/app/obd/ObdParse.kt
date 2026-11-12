package com.rsmonitor.app.obd

/** Pure helpers for reading adapter replies. No Android types, so they are unit-tested. */

private val ADAPTER_ERRORS = listOf("NO DATA", "ERROR", "UNABLE TO CONNECT", "STOPPED", "BUS BUSY", "BUS INIT", "BUFFER FULL")

private fun String.isHex() = isNotEmpty() && all { it in '0'..'9' || it in 'A'..'F' }

/**
 * Engine speed from a reply to "010C", or null if the reply holds none.
 * The reply carries two bytes A and B after "41 0C"; rpm is (256·A + B) / 4.
 */
fun parseRpm(lines: List<String>): Int? {
    for (line in lines) {
        val s = line.replace(" ", "")
        val at = s.indexOf("410C")
        if (at >= 0 && s.length >= at + 8) {
            val raw = s.substring(at + 4, at + 8).toIntOrNull(16) ?: continue
            return raw / 4
        }
    }
    return null
}

/** The data ID of the gearbox oil temperature on this car's gearbox control unit. */
const val GEARBOX_OIL_ID = 0x2104

/**
 * Gearbox oil temperature in °C from the reply to "22 21 04": one byte, read as it is.
 *
 * A first version subtracted 40, as engine control units often do. Driven, the gauge then sat
 * at 40 to 50 °C, where this gearbox runs at 80 to 90, so the byte is the temperature itself.
 * Still to be compared once with the value VCDS shows.
 */
fun gearboxOilCelsius(lines: List<String>): Int? = parseUdsData(lines, GEARBOX_OIL_ID)?.firstOrNull()

/** How many data bytes each standard value the app asks for carries. */
private val PID_BYTES = mapOf(0x05 to 1, 0x0C to 2, 0x61 to 1, 0x62 to 1, 0x63 to 2)

/**
 * The values in a reply to a standard "01 ..." request, by PID. One request may ask for
 * several values ("010C62"); the reply then lists them one after another:
 * "7E8 06 41 0C 1A F8 62 9B". Reading stops at the first value whose length is not known.
 */
fun parsePids(lines: List<String>): Map<Int, List<Int>> {
    val found = mutableMapOf<Int, List<Int>>()
    for (line in lines) {
        val s = line.replace(" ", "")
        if (!s.isHex()) continue
        // with a header: three characters, a length byte, then 41. Without: 41 straight away.
        val body = when {
            s.length >= 7 && (s.length - 3) % 2 == 0 && s.startsWith("41", 5) -> {
                // the length byte says how much of the frame is the answer; the rest is padding
                val length = s.substring(3, 5).toInt(16)
                if (length !in 2..7) continue
                s.substring(7, minOf(s.length, 5 + length * 2))
            }
            s.length % 2 == 0 && s.startsWith("41") -> s.substring(2)
            else -> continue
        }
        val bytes = body.chunked(2).map { it.toInt(16) }
        var i = 0
        while (i < bytes.size) {
            val size = PID_BYTES[bytes[i]] ?: break
            if (i + size >= bytes.size) break
            found[bytes[i]] = bytes.subList(i + 1, i + 1 + size)
            i += 1 + size
        }
    }
    return found
}

/** Engine torque right now as a percentage of the reference torque (PID 62). Negative when coasting. */
fun torquePercent(pids: Map<Int, List<Int>>): Int? = pids[0x62]?.let { it[0] - 125 }

/** The engine's reference torque in Nm (PID 63), the figure the percentage refers to. */
fun referenceTorque(pids: Map<Int, List<Int>>): Int? = pids[0x63]?.let { it[0] * 256 + it[1] }

/** Engine speed in rpm (PID 0C). */
fun rpmOf(pids: Map<Int, List<Int>>): Int? = pids[0x0C]?.let { (it[0] * 256 + it[1]) / 4 }

/**
 * Makes a CAN reply readable: "7E804410C1AF8" becomes "7E8 04 41 0C 1A F8".
 * Anything that is not a three-character header followed by whole bytes is returned unchanged.
 */
fun prettyReply(line: String): String {
    val s = line.replace(" ", "")
    if (s.length < 5 || (s.length - 3) % 2 != 0 || !s.isHex()) return line
    return s.substring(0, 3) + " " + s.substring(3).chunked(2).joinToString(" ")
}

/** True when the adapter reports a problem, or the control unit refuses the request (UDS 7F). */
fun isFailedReply(line: String): Boolean {
    if (line == "?" || ADAPTER_ERRORS.any { line.contains(it) }) return true
    val s = line.replace(" ", "")
    // header (3) + length byte (2), then the service byte: 7F means a negative response
    return s.length >= 7 && s.isHex() && s.substring(5, 7) == "7F"
}

/**
 * True when at least one line is a frame from a control unit (a header followed by whole
 * bytes). A refusal (7F) counts: the car is there and answering.
 */
fun isCarReply(lines: List<String>): Boolean = lines.any {
    val s = it.replace(" ", "")
    s.length >= 5 && (s.length - 3) % 2 == 0 && s.isHex()
}

/**
 * The data bytes of a positive reply to "22 <id>" (read data by identifier), or null when the
 * control unit refused, did not answer, or answered for a different ID.
 * Handles a single CAN frame and a reply split over several frames.
 */
fun parseUdsData(lines: List<String>, id: Int): List<Int>? {
    val frames = lines
        .map { it.replace(" ", "") }
        .filter { it.length >= 5 && (it.length - 3) % 2 == 0 && it.isHex() }
        .map { line -> line.substring(3).chunked(2).map { it.toInt(16) } }
    val first = frames.firstOrNull() ?: return null
    val payload: List<Int> = when (first[0] shr 4) {
        0 -> first.drop(1).take(first[0] and 0x0F)
        1 -> {
            if (first.size < 2) return null
            val length = ((first[0] and 0x0F) shl 8) or first[1]
            val all = first.drop(2).toMutableList()
            for (frame in frames.drop(1)) {
                if (frame[0] shr 4 == 2) all += frame.drop(1)
            }
            all.take(length)
        }
        else -> return null
    }
    if (payload.size < 3 || payload[0] != 0x62) return null
    if (payload[1] != ((id shr 8) and 0xFF) || payload[2] != (id and 0xFF)) return null
    return payload.drop(3)
}

/**
 * True when a reply to any "22 …" request is a positive one, whichever IDs it carries.
 * Used when several IDs are asked for at once and only "did anything answer" matters.
 */
fun isPositiveRead(lines: List<String>): Boolean {
    val first = lines
        .map { it.replace(" ", "") }
        .firstOrNull { it.length >= 7 && (it.length - 3) % 2 == 0 && it.isHex() }
        ?.let { line -> line.substring(3).chunked(2).map { it.toInt(16) } }
        ?: return false
    return when (first[0] shr 4) {
        0 -> first[1] == 0x62
        1 -> first.size >= 3 && first[2] == 0x62
        else -> false
    }
}

/** Bytes as spaced hex, for example "7B 01". */
fun hexString(bytes: List<Int>): String = bytes.joinToString(" ") { "%02X".format(it) }
