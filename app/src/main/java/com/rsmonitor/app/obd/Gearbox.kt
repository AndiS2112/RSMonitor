package com.rsmonitor.app.obd

import com.rsmonitor.app.drive.Gear

/*
 * What this car's gearbox control unit (7E1) reports, found by scanning it in P, R and D and
 * by recording drives: up and down through the gears in manual mode, D and S, and a pull to
 * the redline in second. All of these answer without opening a diagnostic session.
 */

/** Engine speed as the gearbox sees it: two bytes, in quarter-rpm. Matches the engine's own to 8,090 rpm. */
const val GEARBOX_SPEED_ID = 0x2102

/** Engine torque as the gearbox receives it: two bytes, in Nm. See [gearboxEngineTorque]. */
const val GEARBOX_TORQUE_ID = 0x2002

/** The engaged gear: one byte. 1 is reverse, 2 is nothing engaged (P and N), 3 to 9 are gears 1 to 7. */
const val GEARBOX_GEAR_ID = 0x210F

/** The selector: one byte. 0 P, 1 R, 2 N, 3 D. S and manual also read 3. */
const val GEARBOX_SELECTOR_ID = 0x210C

/** One byte that reads 0B in P, R, N, D and S, and 03 while the gears are chosen by hand. */
const val GEARBOX_MODE_ID = 0x210B

/** Engine speed in rpm from the two bytes of [GEARBOX_SPEED_ID]. */
fun gearboxEngineSpeed(data: List<Int>?): Int? =
    if (data != null && data.size >= 2) (data[0] * 256 + data[1]) / 4 else null

/**
 * Engine torque in Nm from the two bytes of [GEARBOX_TORQUE_ID], negative on the overrun.
 *
 * The gearbox holds the figure as a 15-bit number in tenths of a Nm and reports a tenth of
 * it, so torque below zero comes out just under 3277: `0C 9B` (3227) is −50 Nm, and the
 * 3181 that shows with the ignition on and the engine stopped is −96 Nm. Seen on the car:
 * 394 Nm on full throttle at 6,100 rpm, the same figure the clutch was asked to carry, and
 * −110 Nm on lifting off at 6,400 rpm.
 */
fun gearboxEngineTorque(data: List<Int>?): Int? {
    if (data == null || data.size < 2) return null
    val raw = data[0] * 256 + data[1]
    return when {
        raw <= 1638 -> raw
        raw <= 3277 -> raw - 3277
        else -> null
    }
}

/**
 * What to show for the gear, from the bytes of [GEARBOX_GEAR_ID], [GEARBOX_SELECTOR_ID] and
 * [GEARBOX_MODE_ID]; any of them may be missing. A forward gear shows as D or, when the
 * gears are chosen by hand, M, with its number. S cannot be told from D with these values
 * and shows as D.
 */
fun gearFrom(engaged: Int?, selector: Int?, mode: Int?): Gear? {
    val byHand = mode != null && (mode and 0x08) == 0
    val forward = if (byHand) 'M' else 'D'
    return when {
        engaged != null && engaged in 3..9 -> Gear(forward, engaged - 2)
        engaged == 1 -> Gear('R')
        selector == 0 -> Gear('P')
        selector == 1 -> Gear('R')
        selector == 2 -> Gear('N')
        selector == 3 -> Gear(forward)
        engaged == 2 -> Gear('N')
        else -> null
    }
}
