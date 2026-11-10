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

/** Bytes as spaced hex, for example "7B 01". */
fun hexString(bytes: List<Int>): String = bytes.joinToString(" ") { "%02X".format(it) }
