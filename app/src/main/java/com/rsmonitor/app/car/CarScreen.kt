package com.rsmonitor.app.car

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

/**
 * What the phone and the car screen share about the car screen: which page the centre dial
 * shows, chosen on the phone (or with the one button Android Auto insists on) and remembered
 * between drives, and the screen area Android Auto last reported, shown on the phone so a
 * layout problem in the car can be read off there.
 */
object CarScreen {
    private const val CENTRE_KEY = "centre"

    private var settings: SharedPreferences? = null
    private val _centre = MutableStateFlow(Centre.GMeter)
    private val _areaNote = MutableStateFlow<String?>(null)

    val centre: StateFlow<Centre> = _centre

    /** For example "1180×630, clear 12,96 to 1168,618"; null until the car screen has been up. */
    val areaNote: StateFlow<String?> = _areaNote

    /** Reads the remembered page. Safe to call from both the phone screen and the car screen. */
    @Synchronized
    fun load(context: Context) {
        if (settings != null) return
        val stored = context.applicationContext.getSharedPreferences("gauges", Context.MODE_PRIVATE)
        settings = stored
        _centre.value = if (stored.getString(CENTRE_KEY, null) == Centre.Power.name) Centre.Power else Centre.GMeter
    }

    fun show(page: Centre) {
        _centre.value = page
        settings?.edit()?.putString(CENTRE_KEY, page.name)?.apply()
    }

    fun switchCentre() = show(if (_centre.value == Centre.Power) Centre.GMeter else Centre.Power)

    internal fun report(width: Int, height: Int, visible: Rect?) {
        _areaNote.value = if (visible == null || visible.isEmpty) {
            "$width×$height"
        } else {
            "$width×$height, clear ${visible.left},${visible.top} to ${visible.right},${visible.bottom}"
        }
    }
}

/**
 * The part of the car's surface the gauges are painted in. Android Auto reports the area its
 * own furniture leaves clear, and holds a strip along the top back for the template's
 * buttons. The gauges take that strip as well, down to the same margin as at the left edge:
 * the one button left on this screen shows only for a few seconds after the screen is
 * touched, and would otherwise cost a fifth of the height all the time.
 */
fun gaugeArea(visible: Rect?, width: Int, height: Int): RectF {
    if (visible == null || visible.isEmpty) return RectF(0f, 0f, width.toFloat(), height.toFloat())
    val top = min(visible.top, visible.left)
    return RectF(visible.left.toFloat(), top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat())
}
