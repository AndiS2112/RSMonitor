package com.rsmonitor.app.drive

/**
 * What the gearbox is doing: the selector position (P, R, N, D, S or M) and, when a forward
 * gear is engaged, which one (1 to 7).
 */
data class Gear(val mode: Char, val number: Int? = null)
