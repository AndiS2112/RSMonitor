package com.rsmonitor.app.car

/**
 * Where each oil counts as cold and as hot, in °C. Below "cold" the oil has not reached
 * operating temperature and its icon is blue; from "hot" it is in the red zone: the scale,
 * the icon and the number turn red.
 */
data class OilLimits(val oilCold: Int, val oilHot: Int, val gearCold: Int, val gearHot: Int) {
    enum class Which { OilCold, OilHot, GearCold, GearHot }

    fun value(which: Which) = when (which) {
        Which.OilCold -> oilCold
        Which.OilHot -> oilHot
        Which.GearCold -> gearCold
        Which.GearHot -> gearHot
    }

    /** Each limit lies on its gauge's scale, and "cold" is at least one step below "hot". */
    fun isValid() =
        oilCold >= OIL_LOW && oilHot <= OIL_HIGH && oilHot - oilCold >= STEP &&
            gearCold >= GEAR_LOW && gearHot <= GEAR_HIGH && gearHot - gearCold >= STEP

    /** [which] moved by [by] °C. It stops at the ends of its scale and one step short of its partner. */
    fun moved(which: Which, by: Int): OilLimits = when (which) {
        Which.OilCold -> copy(oilCold = (oilCold + by).coerceIn(OIL_LOW, oilHot - STEP))
        Which.OilHot -> copy(oilHot = (oilHot + by).coerceIn(oilCold + STEP, OIL_HIGH))
        Which.GearCold -> copy(gearCold = (gearCold + by).coerceIn(GEAR_LOW, gearHot - STEP))
        Which.GearHot -> copy(gearHot = (gearHot + by).coerceIn(gearCold + STEP, GEAR_HIGH))
    }

    fun toList() = listOf(oilCold, oilHot, gearCold, gearHot)

    companion object {
        /** The gearbox runs cooler than the engine: 70 to 75 °C on the move. */
        val STANDARD = OilLimits(oilCold = 80, oilHot = 130, gearCold = 50, gearHot = 115)

        /** One tap on the phone: the scales carry a mark every 5 °C. */
        const val STEP = 5

        // the ends of the two scales
        const val OIL_LOW = 50
        const val OIL_HIGH = 150
        const val GEAR_LOW = 30
        const val GEAR_HIGH = 130

        /** The limits from a stored list, or null if it does not hold four usable values. */
        fun from(values: List<Int>): OilLimits? =
            if (values.size != 4) null else OilLimits(values[0], values[1], values[2], values[3]).takeIf { it.isValid() }
    }
}
