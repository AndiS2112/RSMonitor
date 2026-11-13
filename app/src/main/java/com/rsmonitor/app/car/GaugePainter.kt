package com.rsmonitor.app.car

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.rsmonitor.app.drive.GTracker
import com.rsmonitor.app.drive.Gear
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How strongly a red oil icon is drawn at [millis] on a running clock: three pulses a second,
 * each swinging smoothly between full strength and a quarter of it.
 */
fun redZonePulse(millis: Long): Float {
    val turns = (millis % 1000L) * 3 / 1000.0
    return (0.625 + 0.375 * cos(2 * Math.PI * turns)).toFloat()
}

/** What the large dial in the middle shows. */
enum class Centre { GMeter, Power }

/** Everything the gauge screen shows. A null reading means "no data yet". */
data class GaugeFrame(
    val connected: Boolean = false,
    val live: Boolean = false,
    /** Engine-speed readings per second. */
    val rate: Float = 0f,
    val rpm: Int? = null,
    val oilTemp: Int? = null,
    val gearTemp: Int? = null,
    /** Selector position and engaged gear; nothing is drawn while it is not known. */
    val gear: Gear? = null,
    /** Highest oil temperatures since the car was last switched off. */
    val oilPeak: Int? = null,
    val gearPeak: Int? = null,
    /**
     * True while the exact peaks have been called up from the phone. The amber peak hands are
     * always drawn; the peak numbers appear only then.
     */
    val showPeaks: Boolean = false,
    /** Where the G-meter's calibration stands; readings exist only once it is ready. */
    val gStage: GTracker.Stage = GTracker.Stage.Uncalibrated,
    /** Sideways force in g, positive to the right. */
    val gLat: Float? = null,
    /** Fore-aft force in g, positive when accelerating. */
    val gLon: Float? = null,
    val peakAccel: Float = 0f,
    val peakRight: Float = 0f,
    val peakBrake: Float = 0f,
    val peakLeft: Float = 0f,
    /** Peak g by direction, evenly spaced clockwise starting at "accelerating". */
    val trace: List<Float> = emptyList(),
    val centre: Centre = Centre.GMeter,
    /** Engine power in metric horsepower and torque in Nm, with their peaks. */
    val power: Int? = null,
    val torque: Int? = null,
    val powerPeak: Int? = null,
    val torquePeak: Int? = null,
    /**
     * How strongly a red oil icon is drawn right now, from 0 to 1. The screen moves it up and
     * down three times a second while an oil is in its red zone, and leaves it at 1 otherwise.
     */
    val pulse: Float = 1f,
    /** False during the dark half of the upshift flash. */
    val flashOn: Boolean = true,
)

/**
 * Draws the gauge screen from the design: engine oil left, the centre dial (G-meter, or power
 * and torque), gearbox oil right, the gear and shift lights in the top row. The three dials
 * run from edge to edge of the car screen. There is no text notice: each oil's icon is blue
 * below operating temperature and red, pulsing, in the red zone. An amber hand marks each
 * oil's peak at all times; the peak as a number shows only while it is called up from the
 * phone. All geometry is in the design's own
 * units (an 844×442 box) and scaled to whatever area the car gives. Uses only
 * android.graphics, so it can be rendered to a bitmap in tests.
 */
class GaugePainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val barCorners = CornerPathEffect(2.5f)

    /** Erases what is under it inside a layer: cuts the gap around an icon's thermometer. */
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val plain = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val numerals = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)

    fun paint(c: Canvas, area: RectF, f: GaugeFrame, footnote: String? = null) {
        c.drawColor(SCREEN)
        val scale = min(area.width() / DESIGN_W, area.height() / DESIGN_H)
        c.save()
        c.translate(area.left + (area.width() - DESIGN_W * scale) / 2f, area.top + (area.height() - DESIGN_H * scale) / 2f)
        c.scale(scale, scale)

        header(c, f)
        gear(c, f)
        shiftLights(c, f)
        // the three dials are the original drawing, enlarged together about the centre dial
        c.save()
        c.scale(DIAL_SCALE, DIAL_SCALE, CENTRE_X, CENTRE_Y)
        sideGauge(c, LEFT_X, -1, 50, 150, intArrayOf(50, 100, 150), OIL_COLD, OIL_HOT, f.oilTemp, f.oilPeak, f.showPeaks, f.pulse, "ENGINE OIL", engineIcon = true)
        sideGauge(c, RIGHT_X, 1, 30, 130, intArrayOf(30, 80, 130), GEAR_COLD, GEAR_HOT, f.gearTemp, f.gearPeak, f.showPeaks, f.pulse, "GEARBOX OIL", engineIcon = false)
        if (f.centre == Centre.Power) powerMeter(c, f) else gMeter(c, f)
        c.restore()

        if (footnote != null) {
            style(plain, 9f, MUTED, Paint.Align.LEFT)
            c.drawText(footnote, 14f, DESIGN_H - 4f, text)
        }
        c.restore()
    }

    // ---- header -----------------------------------------------------------------

    private fun header(c: Canvas, f: GaugeFrame) {
        val running = f.connected && f.live
        style(bold, 14f, if (running) INK else MUTED, Paint.Align.LEFT, 0.16f)
        c.drawText(if (running) "LIVE" else "PAUSED", 14f, LIGHTS_Y + 5f, text)

        style(plain, 13f, MUTED, Paint.Align.RIGHT, 0.06f)
        val status = if (running) "OBD · ${"%.0f".format(f.rate)} Hz" else if (f.connected) "OBD idle" else "OBD off"
        c.drawText(status, DESIGN_W - 14f, LIGHTS_Y + 5f, text)
        fill.shader = null
        fill.color = if (running) INK else LINE
        c.drawCircle(DESIGN_W - 14f - text.measureText(status) - 11f, LIGHTS_Y + 0.5f, 3.5f, fill)
    }

    // ---- shift lights -----------------------------------------------------------

    /**
     * The gear, just left of the shift lights so the two read together: a small mode letter
     * and a large gear number ("D 4"), or one large letter for P, R and N.
     */
    private fun gear(c: Canvas, f: GaugeFrame) {
        val g = f.gear ?: return
        val right = CENTRE_X - LIGHTS_HALF_WIDTH - 22f
        val baseline = LIGHTS_Y + 15f
        val large = g.number?.toString() ?: g.mode.toString()
        style(numerals, 43f, INK, Paint.Align.RIGHT)
        val largeW = text.measureText(large)
        c.drawText(large, right, baseline, text)
        if (g.number != null) {
            style(bold, 17f, MUTED, Paint.Align.RIGHT, 0.16f)
            c.drawText(g.mode.toString(), right - largeW - 5f, baseline, text)
        }
    }

    private fun shiftLights(c: Canvas, f: GaugeFrame) {
        val rpm = f.rpm
        val colours = if (rpm != null) ShiftLights.colours(rpm) else List(ShiftLights.ON_AT.size) { null }
        val dark = rpm != null && ShiftLights.isFlashing(rpm) && !f.flashOn
        // five bars leaning to the right, together as wide as the centre dial
        val top = LIGHTS_Y - BAR_H / 2f
        val bottom = LIGHTS_Y + BAR_H / 2f
        var left = CENTRE_X - (colours.size * BAR_W + (colours.size - 1) * BAR_GAP) / 2f
        fill.shader = null
        fill.pathEffect = barCorners
        for (colour in colours) {
            path.rewind()
            path.moveTo(left + BAR_LEAN, top)
            path.lineTo(left + BAR_W + BAR_LEAN, top)
            path.lineTo(left + BAR_W - BAR_LEAN, bottom)
            path.lineTo(left - BAR_LEAN, bottom)
            path.close()
            if (colour != null && !dark) {
                fill.color = colour
                fill.setShadowLayer(8f, 0f, 0f, colour)
                c.drawPath(path, fill)
                fill.clearShadowLayer()
            } else {
                fill.color = SOCKET
                c.drawPath(path, fill)
                stroke(LINE, 1f)
                line.pathEffect = barCorners
                c.drawPath(path, line)
                line.pathEffect = null
            }
            left += BAR_W + BAR_GAP
        }
        fill.pathEffect = null
    }

    // ---- oil gauges ---------------------------------------------------------------

    private var iconInk = ICON

    /**
     * Needle angle in degrees from straight up. The scale covers 160°, from almost straight
     * down to almost straight up, in two halves that meet at [mid], the middle numeral, which
     * sits level with the hub.
     */
    private fun angle(v: Float, lo: Int, mid: Int, hi: Int, side: Int): Float {
        val t = if (v <= mid) 170f - 80f * (v - lo) / (mid - lo) else 90f - 80f * (v - mid) / (hi - mid)
        return side * t
    }

    private fun sideGauge(
        c: Canvas, cx: Float, side: Int, lo: Int, hi: Int, labels: IntArray, coldBelow: Int, hotFrom: Int,
        value: Int?, peak: Int?, showPeak: Boolean, pulse: Float, label: String, engineIcon: Boolean,
    ) {
        val cy = CENTRE_Y
        val mid = labels[1]

        // the dial is the original drawing, enlarged as a whole about its centre
        c.save()
        c.scale(SIDE_SCALE, SIDE_SCALE, cx, cy)

        // face and chrome ring
        fill.shader = null
        fill.color = FACE
        c.drawCircle(cx, cy, SIDE_R - 3f, fill)
        line.shader = LinearGradient(cx - SIDE_R, cy - SIDE_R, cx - SIDE_R + 0.6f * SIDE_R, cy + SIDE_R, CHROME, CHROME_AT, Shader.TileMode.CLAMP)
        line.strokeWidth = 5f
        line.strokeCap = Paint.Cap.BUTT
        c.drawCircle(cx, cy, SIDE_R - 3f, line)

        // black gloss band behind the dots
        line.shader = LinearGradient(0f, cy - SIDE_R, 0f, cy + SIDE_R, GLOSS, GLOSS_AT, Shader.TileMode.CLAMP)
        line.strokeWidth = 29f
        c.drawCircle(cx, cy, SIDE_R - 20.5f, line)
        stroke(LINE, 1f)
        c.drawCircle(cx, cy, BAND_R, line)

        // scale: a mark every 5 °C. A bar at each numeral, a large dot at the other tens and a
        // small dot at the fives; red from the hot zone.
        var v = lo
        while (v <= hi) {
            val a = angle(v.toFloat(), lo, mid, hi, side)
            val colour = if (v >= hotFrom) HOT else INK
            if (v in labels) {
                stroke(colour, 5f, Paint.Cap.ROUND)
                c.drawLine(px(cx, SIDE_R - 14f, a), py(cy, SIDE_R - 14f, a), px(cx, SIDE_R - 28f, a), py(cy, SIDE_R - 28f, a), line)
            } else {
                fill.color = colour
                c.drawCircle(px(cx, SIDE_R - 20f, a), py(cy, SIDE_R - 20f, a), if (v % 10 == 0) 2.645f else 1.725f, fill)
            }
            v += 5
        }

        // numerals, each kept clear of the gloss band
        for (l in labels) {
            val s = l.toString()
            val a = angle(l.toFloat(), lo, mid, hi, side)
            style(numerals, NUMERAL, if (l >= hotFrom) HOT else INK, Paint.Align.CENTER)
            val r = labelRadius(a, text.measureText(s) / 2f)
            c.drawText(s, px(cx, r, a), py(cy, r, a) + NUMERAL * 0.35f, text)
        }

        // hub: chrome ring and recessed disc
        fill.shader = LinearGradient(cx - 12f, cy - 21f, cx + 12f, cy + 21f, HUB_RING, HUB_RING_AT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, HUB_R, fill)
        fill.shader = RadialGradient(cx, cy - 3.5f, 21.5f, HUB_DISC, HUB_DISC_AT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 21.5f, fill)
        fill.shader = null

        // Highest temperature reached: a thin amber hand across the dot band, under the needle,
        // there at all times. It is never shown below the current reading.
        val peakShown = if (peak != null && value != null) max(peak, value) else peak
        if (peakShown != null) {
            val a = angle(peakShown.coerceIn(lo, hi).toFloat(), lo, mid, hi, side)
            stroke(PEAK, 2.5f, Paint.Cap.ROUND)
            c.drawLine(px(cx, 85.25f, a), py(cy, 85.25f, a), px(cx, 106.75f, a), py(cy, 106.75f, a), line)
        }

        // needle and its ribbed cap, turned together
        val shown = (value ?: lo).coerceIn(lo, hi)
        c.save()
        c.translate(cx, cy)
        c.rotate(angle(shown.toFloat(), lo, mid, hi, side))
        needle(c)
        c.restore()
        fill.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, 2f, fill)

        // icon below the hub, on the centre dial's side of the lowest numeral. It carries the
        // oil's state: blue below operating temperature, red and pulsing in the red zone.
        val iconLeft = cx - side * 27f - 16f
        val iconTop = cy + 50f - 16f
        val inRedZone = value != null && value >= hotFrom
        iconInk = when {
            inRedZone -> HOT
            value != null && value < coldBelow -> COLD
            else -> ICON
        }
        // Drawn in a layer of its own: gaps can then be cut out of it around the thermometer,
        // and in the red zone it fades as a whole, so overlapping strokes do not show through.
        val alpha = if (inRedZone) (255 * pulse.coerceIn(0f, 1f)).toInt() else 255
        c.saveLayerAlpha(iconLeft - 6f, iconTop - 4f, iconLeft + 40f, iconTop + 38f, alpha)
        c.translate(iconLeft, iconTop)
        if (engineIcon) oilIcon(c) else gearboxIcon(c)
        c.restore()

        c.restore()

        // Name and precise reading under the gauge; the reading keeps a fixed width. While the
        // exact peak is called up from the phone it takes this place: "PEAK TEMP" and the peak,
        // in amber.
        val name = if (showPeak) "PEAK TEMP" else label
        val number = if (showPeak) peakShown ?: value else value
        val colour = when {
            number == null -> MUTED
            showPeak -> PEAK
            number >= hotFrom -> HOT
            else -> INK
        }
        style(bold, 13f, LABEL, Paint.Align.LEFT, 0.16f)
        val labelW = text.measureText(name)
        val rowLeft = cx - (labelW + 10f + READOUT_W) / 2f
        val baseline = cy + SIDE_R * SIDE_SCALE + 10f + 17f
        if (side < 0) {
            c.drawText(name, rowLeft, baseline, text)
            readout(c, number, colour, rowLeft + labelW + 10f, baseline, alignRight = false)
        } else {
            readout(c, number, colour, rowLeft + READOUT_W, baseline, alignRight = true)
            style(bold, 13f, LABEL, Paint.Align.LEFT, 0.16f)
            c.drawText(name, rowLeft + READOUT_W + 10f, baseline, text)
        }
    }

    private fun readout(c: Canvas, value: Int?, colour: Int, x: Float, baseline: Float, alignRight: Boolean) {
        val number = value?.toString() ?: "–"
        style(numerals, 21f, colour, Paint.Align.LEFT)
        val numberW = text.measureText(number)
        style(bold, 13f, colour, Paint.Align.LEFT)
        val unitW = text.measureText("°C")
        val start = if (alignRight) x - (numberW + 5f + unitW) else x
        style(numerals, 21f, colour, Paint.Align.LEFT)
        c.drawText(number, start, baseline, text)
        style(bold, 13f, colour, Paint.Align.LEFT)
        c.drawText("°C", start + numberW + 5f, baseline, text)
    }

    /** The largest radius that keeps a numeral 7 units clear of the gloss band. */
    private fun labelRadius(angleDeg: Float, halfWidth: Float): Float {
        val a = Math.toRadians(abs(angleDeg).toDouble())
        val halfHeight = NUMERAL * 0.375f
        var r = 64f
        while (r > HUB_R + halfWidth) {
            val x = r * sin(a) + halfWidth
            val y = abs(r * cos(a)) + halfHeight
            if (hypot(x, y) <= BAND_R - 7f) return r
            r -= 0.5f
        }
        return r
    }

    /** Drawn pointing straight up from the origin; the caller rotates it. */
    private fun needle(c: Canvas) {
        // whole blade once, for its shadow on the dial
        path.rewind()
        path.moveTo(-1.2f, -95f); path.lineTo(1.2f, -95f); path.lineTo(6f, 6f); path.lineTo(-6f, 6f); path.close()
        fill.shader = null
        fill.color = NEEDLE_DARK
        fill.setShadowLayer(3f, 1f, 2f, 0xA6000000.toInt())
        c.drawPath(path, fill)
        fill.clearShadowLayer()
        // two faces: bright and dark, with a light ridge between
        path.rewind()
        path.moveTo(-1.2f, -95f); path.lineTo(0f, -95f); path.lineTo(0f, 6f); path.lineTo(-6f, 6f); path.close()
        fill.color = NEEDLE_LIGHT
        c.drawPath(path, fill)
        stroke(NEEDLE_RIDGE, 0.8f)
        c.drawLine(0f, -95f, 0f, 6f, line)

        // ribbed black cap over the root of the blade
        path.rewind()
        for ((i, p) in CAP_OUTLINE.withIndex()) {
            val x = p.first / 100f * 18f - 9f
            val y = p.second / 100f * 61f - 42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fill.shader = LinearGradient(-9f, 0f, 9f, 0f, CAP, CAP_AT, Shader.TileMode.CLAMP)
        fill.setShadowLayer(2f, 0f, 2f, 0xBF000000.toInt())
        c.drawPath(path, fill)
        fill.clearShadowLayer()
        fill.shader = null
    }

    /**
     * Oil can with a thermometer standing in it, drawn in a 32-unit box at the origin. The
     * can's spout and drip point right, its handle is on the left. The thermometer's bulb sits
     * in the can with a gap cut around it; the stem and three marks, facing right, stand above.
     */
    private fun oilIcon(c: Canvas) {
        c.save()
        // a 31.2-unit window on the 36-unit drawing, a little larger than the gearbox icon
        c.scale(32f / 31.2f, 32f / 31.2f)
        c.translate(-2.4f, -0.4f)
        // drawn spout-left, then mirrored
        c.translate(36f, 0f)
        c.scale(-1f, 1f)

        fill.shader = null
        fill.color = iconInk
        line.strokeJoin = Paint.Join.ROUND
        // can
        path.rewind()
        path.moveTo(10.5f, 18.5f); path.lineTo(25f, 18.5f); path.lineTo(25f, 27.5f); path.lineTo(12.8f, 27.5f); path.close()
        c.drawPath(path, fill)
        stroke(iconInk, 1.4f, Paint.Cap.ROUND)
        c.drawPath(path, line)
        // spout
        path.rewind()
        path.moveTo(11.2f, 19.2f); path.lineTo(3.6f, 15.2f); path.lineTo(2.7f, 17f); path.lineTo(11.6f, 22.6f); path.close()
        c.drawPath(path, fill)
        stroke(iconInk, 1.2f, Paint.Cap.ROUND)
        c.drawPath(path, line)
        // handle
        stroke(iconInk, 2.4f, Paint.Cap.ROUND)
        path.rewind()
        path.moveTo(25f, 20.4f); path.lineTo(31.2f, 16.6f); path.lineTo(33.4f, 19.4f); path.lineTo(25f, 24.6f)
        c.drawPath(path, line)
        // the gap around the thermometer
        c.drawCircle(17.6f, 19.4f, 4.8f, cut)
        c.drawRect(14.5f, 4f, 20.7f, 19f, cut)
        // drip
        path.rewind()
        path.moveTo(3.3f, 19.6f); path.rQuadTo(-1.8f, 2.8f, 0f, 3.9f); path.rQuadTo(1.8f, -1.1f, 0f, -3.9f); path.close()
        c.drawPath(path, fill)
        // thermometer, shrunk towards its bulb so that it stays seated in the can
        c.scale(0.8f, 0.8f, 17.6f, 19.4f)
        rect.set(16f, 3f, 19.2f, 18f)
        c.drawRoundRect(rect, 1.6f, 1.6f, fill)
        c.drawCircle(17.6f, 19.4f, 3.7f, fill)
        stroke(iconInk, 1.6f, Paint.Cap.ROUND)
        for (y in floatArrayOf(5.2f, 8.7f, 12.2f)) c.drawLine(10.6f, y, 13.6f, y, line)
        c.restore()
    }

    /**
     * A solid gear with a thermometer's bulb seated in its middle, a gap cut around it; the
     * stem and three marks, facing right, stand above the gear, and a small gear sits in a
     * notch at its right. Drawn in a 32-unit box at the origin.
     */
    private fun gearboxIcon(c: Canvas) {
        c.save()
        c.scale(32f / 36f, 32f / 36f)
        c.translate(-1.3f, -0.2f)

        fill.shader = null
        fill.color = iconInk
        // large gear
        c.drawCircle(14f, 23f, 8.8f, fill)
        teeth(c, 14f, 23f, 8f, 11.6f, 3.8f, 22.5f)
        // the gaps: around the bulb and stem, and the notch for the small gear
        c.drawCircle(14f, 23f, 5.2f, cut)
        c.drawRect(11.2f, 2f, 16.8f, 23f, cut)
        c.drawCircle(30f, 15.5f, 7.7f, cut)
        // thermometer
        rect.set(12.7f, 1.8f, 15.3f, 22.3f)
        c.drawRoundRect(rect, 1.3f, 1.3f, fill)
        c.drawCircle(14f, 23f, 3.5f, fill)
        stroke(iconInk, 1.4f, Paint.Cap.ROUND)
        for (y in floatArrayOf(3.6f, 6.3f, 9f)) c.drawLine(17.2f, y, 19.8f, y, line)
        // small gear
        stroke(iconInk, 2.2f)
        c.drawCircle(30f, 15.5f, 3f, line)
        teeth(c, 30f, 15.5f, 3.9f, 6.2f, 2.2f, 0f)
        c.restore()
    }

    private fun teeth(c: Canvas, cx: Float, cy: Float, r0: Float, r1: Float, width: Float, offsetDeg: Float) {
        stroke(iconInk, width)
        for (i in 0 until 8) {
            val a = offsetDeg + i * 45f
            c.drawLine(px(cx, r0, a), py(cy, r0, a), px(cx, r1, a), py(cy, r1, a), line)
        }
    }

    // ---- G-meter ------------------------------------------------------------------

    /** The centre dial's body, shared by both of its pages. */
    private fun centreDial(c: Canvas) {
        val cx = CENTRE_X
        val cy = CENTRE_Y

        // a ring of screen colour separates it from the oil gauges it sits in front of
        fill.shader = null
        fill.color = SCREEN
        fill.setShadowLayer(18f, 0f, 8f, 0x80000000.toInt())
        c.drawCircle(cx, cy, G_R + 6f, fill)
        fill.clearShadowLayer()

        // face and chrome bezel
        fill.color = FACE
        c.drawCircle(cx, cy, G_R - 4f, fill)
        line.shader = LinearGradient(cx - G_R, cy - G_R, cx - G_R + 0.6f * G_R, cy + G_R, CHROME, CHROME_AT, Shader.TileMode.CLAMP)
        line.strokeWidth = 7f
        line.strokeCap = Paint.Cap.BUTT
        c.drawCircle(cx, cy, G_R - 4f, line)
    }

    private fun gMeter(c: Canvas, f: GaugeFrame) {
        val cx = CENTRE_X
        val cy = CENTRE_Y

        centreDial(c)
        stroke(LINE, 1f)
        c.drawCircle(cx, cy, G_R - 12f, line)

        // dots round the bezel, none at the four points where the peak numbers sit
        fill.color = INK
        var d = 0
        while (d < 360) {
            if (d % 90 != 0) {
                c.drawCircle(px(cx, G_R - 20f, d.toFloat()), py(cy, G_R - 20f, d.toFloat()), if (d % 45 == 0) 2.53f else 1.61f, fill)
            }
            d += 15
        }

        // silver band and plot
        val plot = G_SCALE * 1.5f
        fill.shader = LinearGradient(cx - plot, cy - plot, cx - plot + 0.5f * plot, cy + plot, SILVER, SILVER_AT, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, plot + 7f, fill)
        fill.shader = null
        fill.color = PLOT
        c.drawCircle(cx, cy, plot + 2f, fill)
        stroke(RING, 1.5f)
        for (g in floatArrayOf(1.5f, 1f, 0.5f)) c.drawCircle(cx, cy, G_SCALE * g, line)

        // peak trace
        if (f.trace.size >= 3) {
            path.rewind()
            for ((i, g) in f.trace.withIndex()) {
                val a = i * 360f / f.trace.size
                val r = g.coerceIn(0f, 1.5f) * G_SCALE
                if (i == 0) path.moveTo(px(cx, r, a), py(cy, r, a)) else path.lineTo(px(cx, r, a), py(cy, r, a))
            }
            path.close()
            fill.color = HOT
            fill.alpha = 82
            c.drawPath(path, fill)
            fill.alpha = 255
            stroke(HOT, 2f)
            line.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, line)
        }

        // crosshair
        stroke(CROSSHAIR, 1f)
        c.drawLine(cx - plot, cy, cx + plot, cy, line)
        c.drawLine(cx, cy - plot, cx, cy + plot, line)

        // current reading
        val lat = f.gLat
        val lon = f.gLon
        if (lat != null && lon != null) {
            var x = lat * G_SCALE
            var y = -lon * G_SCALE
            val len = hypot(x, y)
            if (len > plot) {
                x *= plot / len
                y *= plot / len
            }
            // see-through, so the peak outline shows under it, with a fine solid edge
            fill.color = G_DOT
            c.drawCircle(cx + x, cy + y, G_DOT_R, fill)
            stroke(G_DOT_EDGE, 1.2f)
            c.drawCircle(cx + x, cy + y, G_DOT_R, line)
        }

        // until the phone is calibrated there is no reading: say what is needed instead
        when (f.gStage) {
            GTracker.Stage.Uncalibrated -> plotHint(c, "NOT CALIBRATED", "Tap Calibrate on the phone")
            GTracker.Stage.Levelling -> plotHint(c, "CALIBRATING", "Keep the car still")
            GTracker.Stage.AwaitingPullAway -> plotHint(c, "CALIBRATING", "Pull away in a straight line")
            GTracker.Stage.Ready -> Unit
        }

        // four peak numbers, midway between the silver band and the bezel
        val mid = (plot + 7f + G_R - 12f) / 2f
        val known = lat != null && lon != null
        style(numerals, 18f, if (known) PEAK else MUTED, Paint.Align.CENTER)
        val dy = 18f * 0.35f
        c.drawText(g2(f.peakAccel, known), cx, cy - mid + dy, text)
        c.drawText(g2(f.peakBrake, known), cx, cy + mid + dy, text)
        c.drawText(g2(f.peakLeft, known), cx - mid, cy + dy, text)
        c.drawText(g2(f.peakRight, known), cx + mid, cy + dy, text)
    }

    private fun g2(v: Float, known: Boolean) = if (known) "%.2f".format(v) else "–"

    /** Two centred lines in the plot, each on a patch of plot colour so the crosshair does not cut through them. */
    private fun plotHint(c: Canvas, title: String, detail: String) {
        style(bold, 12f, INK, Paint.Align.CENTER, 0.16f)
        patch(c, text.measureText(title), CENTRE_Y - 27f, CENTRE_Y - 7f)
        c.drawText(title, CENTRE_X, CENTRE_Y - 12f, text)
        style(plain, 12f, LABEL, Paint.Align.CENTER)
        patch(c, text.measureText(detail), CENTRE_Y + 7f, CENTRE_Y + 27f)
        c.drawText(detail, CENTRE_X, CENTRE_Y + 21f, text)
    }

    private fun patch(c: Canvas, textWidth: Float, top: Float, bottom: Float) {
        rect.set(CENTRE_X - textWidth / 2f - 7f, top, CENTRE_X + textWidth / 2f + 7f, bottom)
        fill.shader = null
        fill.color = PLOT
        c.drawRoundRect(rect, 6f, 6f, fill)
    }

    // ---- power and torque ---------------------------------------------------------------

    /** Scale angle in degrees clockwise from straight up: a 270° sweep, open at the bottom. */
    private fun sweep(v: Float, top: Int) = -135f + 270f * v.coerceIn(0f, top.toFloat()) / top

    /**
     * Two rings in one dial: power on the outer ring, torque on the inner one. Each has its
     * own needle, solid at its scale and fading out towards the middle of the dial.
     */
    private fun powerMeter(c: Canvas, f: GaugeFrame) {
        val cx = CENTRE_X
        val cy = CENTRE_Y
        centreDial(c)

        // outer ring: power
        ring(c, 138.75f, 31.5f)
        stroke(LINE, 1f)
        c.drawCircle(cx, cy, 123f, line)
        marks(c, HP_TOP, 132f, 146f, 5.5f, 139f, 2.9f, 1.9f)
        style(numerals, 19f, INK, Paint.Align.CENTER)
        for (l in labels(HP_TOP)) {
            val a = sweep(l.toFloat(), HP_TOP)
            c.drawText(l.toString(), px(cx, 103f, a), py(cy, 103f, a) + 19f * 0.35f, text)
        }

        // inner ring: torque
        ring(c, 75f, 14f)
        stroke(LINE, 1f)
        c.drawCircle(cx, cy, 82f, line)
        c.drawCircle(cx, cy, 68f, line)
        marks(c, NM_TOP, 71f, 79f, 3.2f, 75f, 1.7f, 1f)
        style(numerals, 13f, LABEL, Paint.Align.CENTER)
        for (l in labels(NM_TOP)) {
            val a = sweep(l.toFloat(), NM_TOP)
            c.drawText(l.toString(), px(cx, 54f, a), py(cy, 54f, a) + 13f * 0.35f, text)
        }

        // peaks: amber marks across each ring; never shown below the current reading
        val power = f.power
        val torque = f.torque
        val powerPeak = if (f.powerPeak != null && power != null) max(f.powerPeak, power) else f.powerPeak
        val torquePeak = if (f.torquePeak != null && torque != null) max(f.torquePeak, torque) else f.torquePeak
        if (powerPeak != null) {
            val a = sweep(powerPeak.toFloat(), HP_TOP)
            stroke(PEAK, 2.5f, Paint.Cap.ROUND)
            c.drawLine(px(cx, 126.25f, a), py(cy, 126.25f, a), px(cx, 151.75f, a), py(cy, 151.75f, a), line)
        }
        if (torquePeak != null) {
            val a = sweep(torquePeak.toFloat(), NM_TOP)
            stroke(PEAK, 2f, Paint.Cap.ROUND)
            c.drawLine(px(cx, 70f, a), py(cy, 70f, a), px(cx, 80f, a), py(cy, 80f, a), line)
        }

        // needles: power first, so torque's lies over the faded end of it
        if (power != null) fadingNeedle(c, sweep(power.toFloat(), HP_TOP), 142f, 50f, 96f, 15f, 3.3f)
        if (torque != null) fadingNeedle(c, sweep(torque.toFloat(), NM_TOP), 80f, 34f, 56f, 10f, 2.6f)

        // numbers: torque in the middle of its ring, power in the open bottom of its ring
        ringReadout(c, cy - 16f, torque, torquePeak, "Nm", 19f)
        ringReadout(c, cy + 85f, power, powerPeak, "HP", 20f)
    }

    /** A black gloss band round the centre dial, as behind the dots of the oil gauges. */
    private fun ring(c: Canvas, radius: Float, width: Float) {
        val edge = radius + width / 2f
        line.shader = LinearGradient(0f, CENTRE_Y - edge, 0f, CENTRE_Y + edge, GLOSS, GLOSS_AT, Shader.TileMode.CLAMP)
        line.strokeWidth = width
        line.strokeCap = Paint.Cap.BUTT
        c.drawCircle(CENTRE_X, CENTRE_Y, radius, line)
    }

    /** The numbered marks of a scale: every hundred, and the end of the scale. */
    private fun labels(top: Int): List<Int> = (0 until top step 100) + top

    /** A mark every 10 units: a bar where there is a numeral, dots between, larger at the fifties. */
    private fun marks(c: Canvas, top: Int, barFrom: Float, barTo: Float, barWidth: Float, dotAt: Float, large: Float, small: Float) {
        val numbered = labels(top)
        fill.shader = null
        fill.color = INK
        for (v in 0..top step 10) {
            val a = sweep(v.toFloat(), top)
            if (v in numbered) {
                stroke(INK, barWidth, Paint.Cap.ROUND)
                c.drawLine(px(CENTRE_X, barFrom, a), py(CENTRE_Y, barFrom, a), px(CENTRE_X, barTo, a), py(CENTRE_Y, barTo, a), line)
            } else {
                c.drawCircle(px(CENTRE_X, dotAt, a), py(CENTRE_Y, dotAt, a), if (v % 50 == 0) large else small, fill)
            }
        }
    }

    /**
     * A blade from [root] to [tip] (distances from the dial's centre), fully solid from
     * [solidFrom] outwards and fading to nothing at its root.
     */
    private fun fadingNeedle(c: Canvas, angle: Float, tip: Float, root: Float, solidFrom: Float, width: Float, tipWidth: Float) {
        c.save()
        c.translate(CENTRE_X, CENTRE_Y)
        c.rotate(angle)
        fun fade(colour: Int) = LinearGradient(0f, -root, 0f, -solidFrom, colour and 0x00FFFFFF, colour, Shader.TileMode.CLAMP)

        // red glow round the blade
        path.rewind()
        path.moveTo(-tipWidth / 2f, -tip); path.lineTo(tipWidth / 2f, -tip); path.lineTo(width / 2f, -root); path.lineTo(-width / 2f, -root); path.close()
        fill.shader = fade(NEEDLE_GLOW)
        fill.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, fill)
        fill.maskFilter = null

        // two faces, dark and bright, with a light ridge between
        fill.shader = fade(NEEDLE_DARK)
        c.drawPath(path, fill)
        path.rewind()
        path.moveTo(-tipWidth / 2f, -tip); path.lineTo(0f, -tip); path.lineTo(0f, -root); path.lineTo(-width / 2f, -root); path.close()
        fill.shader = fade(NEEDLE_LIGHT)
        c.drawPath(path, fill)
        fill.shader = null
        line.shader = fade(NEEDLE_RIDGE)
        line.strokeWidth = 0.8f
        line.strokeCap = Paint.Cap.BUTT
        c.drawLine(0f, -tip, 0f, -root, line)
        line.shader = null
        c.restore()
    }

    /** A reading with its unit, and its peak on a second line, centred on the dial. [top] is the top of the block. */
    private fun ringReadout(c: Canvas, top: Float, value: Int?, peak: Int?, unit: String, size: Float) {
        val number = value?.toString() ?: "–"
        val colour = if (value == null) MUTED else INK
        style(numerals, size, colour, Paint.Align.LEFT)
        val numberW = text.measureText(number)
        style(bold, 9.5f, MUTED, Paint.Align.LEFT)
        val unitW = text.measureText(unit)
        val left = CENTRE_X - (numberW + 3f + unitW) / 2f
        val baseline = top + size * 0.82f
        style(numerals, size, colour, Paint.Align.LEFT)
        c.drawText(number, left, baseline, text)
        style(bold, 9.5f, MUTED, Paint.Align.LEFT)
        c.drawText(unit, left + numberW + 3f, baseline, text)

        if (peak == null) return
        val peakText = peak.toString()
        style(bold, 7.5f, MUTED, Paint.Align.LEFT, 0.16f)
        val captionW = text.measureText("PEAK")
        style(numerals, 11.5f, PEAK, Paint.Align.LEFT)
        val peakW = text.measureText(peakText)
        val peakLeft = CENTRE_X - (captionW + 4f + peakW) / 2f
        val peakBaseline = top + size + 3f + 11.5f * 0.85f
        style(bold, 7.5f, MUTED, Paint.Align.LEFT, 0.16f)
        c.drawText("PEAK", peakLeft, peakBaseline, text)
        style(numerals, 11.5f, PEAK, Paint.Align.LEFT)
        c.drawText(peakText, peakLeft + captionW + 4f, peakBaseline, text)
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun px(cx: Float, r: Float, deg: Float) = cx + r * sin(Math.toRadians(deg.toDouble())).toFloat()

    private fun py(cy: Float, r: Float, deg: Float) = cy - r * cos(Math.toRadians(deg.toDouble())).toFloat()

    private fun stroke(colour: Int, width: Float, cap: Paint.Cap = Paint.Cap.BUTT) {
        line.shader = null
        line.color = colour
        line.strokeWidth = width
        line.strokeCap = cap
    }

    private fun style(face: Typeface, size: Float, colour: Int, align: Paint.Align, tracking: Float = 0f) {
        text.typeface = face
        text.textSize = size
        text.color = colour
        text.textAlign = align
        text.letterSpacing = tracking
    }

    companion object {
        // the design box
        // The design box has the shape of the car's screen area (1156 × 606 px on the RS5's
        // head unit), so the dials can run from edge to edge.
        const val DESIGN_W = 844f
        const val DESIGN_H = 442f
        private const val CENTRE_X = 422f
        private const val CENTRE_Y = 234f
        private const val LIGHTS_Y = 18f
        /** The three dials are drawn at their first size and enlarged together by this much. */
        private const val DIAL_SCALE = 1.22f
        // Each shift light is a bar leaning 24° to the right. From tip to tip the row of five
        // is as wide as the centre dial across its chrome rim: 2 × (G_R − 0.5) × DIAL_SCALE.
        private const val LIGHTS_SPAN = 394f
        private const val BAR_H = 18f
        private const val BAR_GAP = 8f
        private const val BAR_LEAN = BAR_H / 2f * 0.445f
        private const val BAR_W = (LIGHTS_SPAN - 2f * BAR_LEAN - 4f * BAR_GAP) / 5f
        /** From the middle of the row of five lights to the end of its last bar, the lean aside. */
        private const val LIGHTS_HALF_WIDTH = LIGHTS_SPAN / 2f - BAR_LEAN

        // gauges
        private const val SIDE_R = 116f
        /** The oil dials are drawn at 116 and enlarged by this much. */
        private const val SIDE_SCALE = 1.1f
        // tucked 76 units behind the centre dial
        private const val LEFT_X = CENTRE_X - 213.6f
        private const val RIGHT_X = CENTRE_X + 213.6f
        private const val BAND_R = SIDE_R - 35f
        private const val HUB_R = 24f
        private const val NUMERAL = 21f
        private const val READOUT_W = 72f
        private const val G_R = 162f
        private const val G_SCALE = 60f   // design units per g
        private const val G_DOT_R = 7.7f
        private const val G_DOT = 0x73FFFFFF          // white at 45%
        private val G_DOT_EDGE = 0xE6FFFFFF.toInt()   // white at 90%

        // Limits. Below "cold" the oil has not reached operating temperature; from "hot" it
        // is in the red zone. The gearbox runs cooler than the engine: 70 to 75 °C on the move.
        const val OIL_COLD = 80
        const val OIL_HOT = 130
        const val GEAR_COLD = 50
        const val GEAR_HOT = 115

        // power and torque scales
        const val HP_TOP = 480
        const val NM_TOP = 450

        // palette (see README)
        private val SCREEN = 0xFF18181A.toInt()
        private val FACE = 0xFF222225.toInt()
        private val PLOT = 0xFF1C1C20.toInt()
        private val LINE = 0xFF3A3A3E.toInt()
        private val RING = 0xFF5A5A60.toInt()
        private val CROSSHAIR = 0xFF8E8E94.toInt()
        private val SOCKET = 0xFF0B0B0C.toInt()
        private val INK = 0xFFFFFFFF.toInt()
        private val LABEL = 0xFFD6D6D6.toInt()
        private val MUTED = 0xFFA8A8AE.toInt()
        private val ICON = 0xFFD2D2D6.toInt()
        private val COLD = 0xFF4DA6FF.toInt()
        private val HOT = 0xFFFF1A1A.toInt()
        private val PEAK = 0xFFF5A623.toInt()
        private val NEEDLE_LIGHT = 0xFFFF3A2A.toInt()
        private val NEEDLE_DARK = 0xFFB00800.toInt()
        private val NEEDLE_RIDGE = 0xFFFFC8BC.toInt()
        private val NEEDLE_GLOW = 0x99FF1E14.toInt()

        private fun colours(vararg argb: Long) = IntArray(argb.size) { argb[it].toInt() }

        private val CHROME = colours(0xFFF2F2F2, 0xFF7C7C80, 0xFF2A2A2C, 0xFF9A9A9E)
        private val CHROME_AT = floatArrayOf(0f, 0.45f, 0.7f, 1f)
        private val GLOSS = colours(0xFF2E2E32, 0xFF050506, 0xFF000000, 0xFF151517)
        private val GLOSS_AT = floatArrayOf(0f, 0.22f, 0.8f, 1f)
        private val SILVER = colours(0xFFF0F0F4, 0xFF8E8E94, 0xFFC8C8CC)
        private val SILVER_AT = floatArrayOf(0f, 0.55f, 1f)
        private val HUB_RING = colours(0xFF4E4E54, 0xFF161618, 0xFF2A2A2E, 0xFFF0F0F4, 0xFF8C8C92)
        private val HUB_RING_AT = floatArrayOf(0f, 0.32f, 0.52f, 0.78f, 1f)
        private val HUB_DISC = colours(0xFF1E1E21, 0xFF0E0E10, 0xFF050506)
        private val HUB_DISC_AT = floatArrayOf(0f, 0.55f, 1f)
        private val CAP = colours(
            0xFF060607, 0xFF45454B, 0xFF111113, 0xFF0B0B0C, 0xFF303035, 0xFF55555C,
            0xFF303035, 0xFF0B0B0C, 0xFF111113, 0xFF45454B, 0xFF060607,
        )
        private val CAP_AT = floatArrayOf(0f, 0.10f, 0.20f, 0.36f, 0.44f, 0.50f, 0.56f, 0.64f, 0.80f, 0.90f, 1f)

        /** Cap outline as percentages of an 18×61 box whose pivot is 42 down from the top. */
        private val CAP_OUTLINE = listOf(
            30f to 0f, 70f to 0f, 70f to 7f, 84f to 11f, 100f to 66f, 100f to 93f,
            84f to 100f, 16f to 100f, 0f to 93f, 0f to 66f, 16f to 11f, 30f to 7f,
        )
    }
}
