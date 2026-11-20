package com.rsmonitor.app.drive

import kotlin.math.roundToInt

/**
 * Engine power in metric horsepower (PS) from torque in Nm and engine speed in rpm.
 * Power in kW is torque × rpm / 9549, and one kW is 1.35962 PS.
 */
fun metricHorsepower(torqueNm: Int, rpm: Int): Int = (torqueNm * rpm / 7023.5).roundToInt()
