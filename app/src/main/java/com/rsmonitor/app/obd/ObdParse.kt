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
