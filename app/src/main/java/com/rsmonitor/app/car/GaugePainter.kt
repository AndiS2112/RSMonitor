package com.rsmonitor.app.car

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.rsmonitor.app.drive.GTracker
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Everything the gauge screen shows. A null reading means "no data yet". */
data class GaugeFrame(
    val connected: Boolean = false,
    val live: Boolean = false,
    /** Engine-speed readings per second. */
    val rate: Float = 0f,
    val rpm: Int? = null,
    val oilTemp: Int? = null,
    val gearTemp: Int? = null,
    /** Highest oil temperatures since the car was last switched off. */
    val oilPeak: Int? = null,
    val gearPeak: Int? = null,
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
    /** False during the dark half of the upshift flash. */
    val flashOn: Boolean = true,
)

/**
 * Draws the gauge screen from the design: engine oil left, G-meter centre, gearbox oil right,
 * shift lights in the top row, a notice below. All geometry is in the design's own units (a 736×442
 * box) and scaled to whatever area the car gives. Uses only android.graphics, so it can be
 * rendered to a bitmap in tests.
 */
class GaugePainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

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
        shiftLights(c, f)
        sideGauge(c, LEFT_X, -1, 50, 150, intArrayOf(50, 100, 150), OIL_HOT, f.oilTemp, f.oilPeak, "ENGINE OIL", engineIcon = true)
        sideGauge(c, RIGHT_X, 1, 50, 130, intArrayOf(50, 100, 130), GEAR_HOT, f.gearTemp, f.gearPeak, "GEARBOX OIL", engineIcon = false)
        gMeter(c, f)
        notice(c, f)

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
        c.drawText(if (running) "LIVE" else "PAUSED", 14f, 29f, text)

        style(plain, 13f, MUTED, Paint.Align.RIGHT, 0.06f)
        val status = if (running) "OBD · ${"%.0f".format(f.rate)} Hz" else if (f.connected) "OBD idle" else "OBD off"
        c.drawText(status, DESIGN_W - 14f, 29f, text)
        fill.shader = null
        fill.color = if (running) INK else LINE
        c.drawCircle(DESIGN_W - 14f - text.measureText(status) - 11f, 24.5f, 3.5f, fill)
    }

    // ---- shift lights -----------------------------------------------------------

    private fun shiftLights(c: Canvas, f: GaugeFrame) {
        val rpm = f.rpm
        val colours = if (rpm != null) ShiftLights.colours(rpm) else List(ShiftLights.ON_AT.size) { null }
        val dark = rpm != null && ShiftLights.isFlashing(rpm) && !f.flashOn
        val r = 11f
        val pitch = 36f
        var x = CENTRE_X - pitch * (colours.size - 1) / 2f
        for (colour in colours) {
            fill.shader = null
            if (colour != null && !dark) {
                fill.color = colour
                fill.setShadowLayer(8f, 0f, 0f, colour)
                c.drawCircle(x, LIGHTS_Y, r, fill)
                fill.clearShadowLayer()
            } else {
                fill.color = SOCKET
                c.drawCircle(x, LIGHTS_Y, r, fill)
                stroke(LINE, 1f)
                c.drawCircle(x, LIGHTS_Y, r - 0.5f, line)
            }
            x += pitch
        }
    }

    // ---- oil gauges ---------------------------------------------------------------

    /** Needle angle in degrees clockwise from straight up. 100 °C sits at the middle of the scale. */
    private fun angle(v: Float, lo: Int, hi: Int, side: Int): Float {
        val t = if (v <= 100f) 150f - 60f * (v - lo) / (100f - lo) else 90f - 60f * (v - 100f) / (hi - 100f)
        return side * t
    }

    private fun sideGauge(
        c: Canvas, cx: Float, side: Int, lo: Int, hi: Int, labels: IntArray, hotFrom: Int,
        value: Int?, peak: Int?, label: String, engineIcon: Boolean,
    ) {
        val cy = CENTRE_Y

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

        // scale: a bar at each labelled value, dots between, red from the hot zone
        var v = lo
        while (v <= hi) {
            val a = angle(v.toFloat(), lo, hi, side)
            val colour = if (v >= hotFrom) HOT else INK
            if (v in labels) {
                stroke(colour, 5f, Paint.Cap.ROUND)
                c.drawLine(px(cx, SIDE_R - 14f, a), py(cy, SIDE_R - 14f, a), px(cx, SIDE_R - 28f, a), py(cy, SIDE_R - 28f, a), line)
            } else {
                fill.color = colour
                c.drawCircle(px(cx, SIDE_R - 20f, a), py(cy, SIDE_R - 20f, a), if ((v - lo) % 10 == 0) 2.645f else 1.725f, fill)
            }
            v += 5
        }

        // numerals, each kept clear of the gloss band
        for (l in labels) {
            val s = l.toString()
            val a = angle(l.toFloat(), lo, hi, side)
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

        // highest temperature reached: a thin amber hand across the dot band, under the needle.
        // It is never shown below the current reading.
        val peakShown = if (peak != null && value != null) max(peak, value) else peak
        if (peakShown != null) {
            val a = angle(peakShown.coerceIn(lo, hi).toFloat(), lo, hi, side)
            stroke(PEAK, 2.5f, Paint.Cap.ROUND)
            c.drawLine(px(cx, 85.25f, a), py(cy, 85.25f, a), px(cx, 106.75f, a), py(cy, 106.75f, a), line)
        }

        // needle and its ribbed cap, turned together
        val shown = (value ?: lo).coerceIn(lo, hi)
        c.save()
        c.translate(cx, cy)
        c.rotate(angle(shown.toFloat(), lo, hi, side))
        needle(c)
        c.restore()
        fill.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, 2f, fill)

        // icon below the hub, on the G-meter side of the 50 mark
        c.save()
        c.translate(cx - side * 12f - 16f, cy + 56f - 16f)
        c.scale(32f / 36f, 32f / 36f)
        if (engineIcon) oilIcon(c) else gearboxIcon(c)
        c.restore()

        // the peak as a number, above the hub on the G-meter side
        if (peakShown != null) {
            val x = cx - side * 22f
            style(bold, 9f, MUTED, Paint.Align.CENTER, 0.16f)
            c.drawText("PEAK", x, cy - 58f, text)
            style(numerals, 16f, PEAK, Paint.Align.CENTER)
            c.drawText(peakShown.toString(), x, cy - 41f, text)
        }

        c.restore()

        // name and precise reading under the gauge; the reading keeps a fixed width
        style(bold, 13f, LABEL, Paint.Align.LEFT, 0.16f)
        val labelW = text.measureText(label)
        val rowLeft = cx - (labelW + 10f + READOUT_W) / 2f
        val baseline = cy + SIDE_R * SIDE_SCALE + 10f + 17f
        val hot = value != null && value >= hotFrom
        if (side < 0) {
            c.drawText(label, rowLeft, baseline, text)
            readout(c, value, hot, rowLeft + labelW + 10f, baseline, alignRight = false)
        } else {
            readout(c, value, hot, rowLeft + READOUT_W, baseline, alignRight = true)
            style(bold, 13f, LABEL, Paint.Align.LEFT, 0.16f)
            c.drawText(label, rowLeft + READOUT_W + 10f, baseline, text)
        }
    }

    private fun readout(c: Canvas, value: Int?, hot: Boolean, x: Float, baseline: Float, alignRight: Boolean) {
        val number = value?.toString() ?: "–"
        val colour = if (value == null) MUTED else if (hot) HOT else INK
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

    /** Oil can with a thermometer, in a 36-unit box. */
    private fun oilIcon(c: Canvas) {
        fill.shader = null
        fill.color = ICON
        path.rewind()
        path.moveTo(9f, 20f); path.lineTo(21f, 20f); path.lineTo(29f, 15.5f); path.lineTo(31f, 17.5f)
        path.lineTo(22.5f, 28f); path.lineTo(9f, 28f); path.close()
        c.drawPath(path, fill)
        stroke(ICON, 2f, Paint.Cap.ROUND)
        line.strokeJoin = Paint.Join.ROUND
        path.rewind()
        path.moveTo(9f, 23.5f); path.lineTo(4.5f, 21.5f); path.lineTo(4.5f, 18.5f); path.lineTo(9f, 20.5f)
        c.drawPath(path, line)
        path.rewind()
        path.moveTo(32.5f, 20.5f); path.rQuadTo(-1.8f, 2.8f, 0f, 3.9f); path.rQuadTo(1.8f, -1.1f, 0f, -3.9f); path.close()
        c.drawPath(path, fill)
        rect.set(13.2f, 4.5f, 17.2f, 21.5f)
        c.drawRoundRect(rect, 2f, 2f, fill)
        stroke(ICON, 1.6f, Paint.Cap.ROUND)
        for (y in floatArrayOf(7.5f, 11f, 14.5f)) c.drawLine(18.6f, y, 21.6f, y, line)
    }

    /** Gear with a thermometer inside and a small gear beside it, in a 36-unit box. */
    private fun gearboxIcon(c: Canvas) {
        fill.shader = null
        fill.color = ICON
        stroke(ICON, 3f)
        c.drawCircle(15f, 21f, 8.2f, line)
        teeth(c, 15f, 21f, 9.4f, 12.8f, 3.6f, 22.5f)
        stroke(ICON, 2.2f, Paint.Cap.ROUND)
        c.drawLine(15f, 14.5f, 15f, 20.5f, line)
        c.drawCircle(15f, 22.6f, 2.7f, fill)
        stroke(ICON, 2.2f)
        c.drawCircle(28.5f, 8.5f, 3f, line)
        teeth(c, 28.5f, 8.5f, 3.9f, 6.2f, 2.2f, 0f)
    }

    private fun teeth(c: Canvas, cx: Float, cy: Float, r0: Float, r1: Float, width: Float, offsetDeg: Float) {
        stroke(ICON, width)
        for (i in 0 until 8) {
            val a = offsetDeg + i * 45f
            c.drawLine(px(cx, r0, a), py(cy, r0, a), px(cx, r1, a), py(cy, r1, a), line)
        }
    }

    // ---- G-meter ------------------------------------------------------------------

    private fun gMeter(c: Canvas, f: GaugeFrame) {
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
            fill.color = INK
            c.drawCircle(cx + x, cy + y, 11f, fill)
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

    // ---- notice ---------------------------------------------------------------------

    private fun notice(c: Canvas, f: GaugeFrame) {
        val oil = f.oilTemp
        val gear = f.gearTemp
        val oilHot = oil != null && oil >= OIL_HOT
        val gearHot = gear != null && gear >= GEAR_HOT
        val oilCold = oil != null && oil < COLD_BELOW
        val gearCold = gear != null && gear < COLD_BELOW
        val message: String
        val warning: Boolean
        when {
            oilHot && gearHot -> { message = "Engine and gearbox oil hot — above $OIL_HOT °C and $GEAR_HOT °C"; warning = true }
            oilHot -> { message = "Engine oil hot — above $OIL_HOT °C"; warning = true }
            gearHot -> { message = "Gearbox oil hot — above $GEAR_HOT °C"; warning = true }
            !f.connected -> { message = "Adapter not connected. Open RS Monitor on the phone."; warning = false }
            !f.live -> { message = "Connected. Live data is off."; warning = false }
            oilCold && gearCold -> { message = "Engine and gearbox still cold — oil below $COLD_BELOW °C"; warning = false }
            oilCold -> { message = "Engine oil still cold — below $COLD_BELOW °C"; warning = false }
            gearCold -> { message = "Gearbox oil still cold — below $COLD_BELOW °C"; warning = false }
            else -> return
        }

        if (warning) style(bold, 14f, INK, Paint.Align.LEFT, 0.02f) else style(plain, 13f, LABEL, Paint.Align.LEFT)
        val textW = text.measureText(message)
        val w = 12f + 16f + 8f + textW + 16f
        val left = CENTRE_X - w / 2f
        rect.set(left, NOTICE_Y - 15f, left + w, NOTICE_Y + 15f)
        fill.shader = null
        fill.color = if (warning) HOT_FILL else FACE
        c.drawRoundRect(rect, 15f, 15f, fill)
        stroke(if (warning) HOT else LINE, 1f)
        c.drawRoundRect(rect, 15f, 15f, line)

        // 16-unit icon drawn in a 24-unit box
        c.save()
        c.translate(left + 12f, NOTICE_Y - 8f)
        c.scale(16f / 24f, 16f / 24f)
        line.strokeJoin = Paint.Join.ROUND
        if (warning) {
            stroke(INK, 2.4f, Paint.Cap.ROUND)
            path.rewind()
            path.moveTo(12f, 3.5f); path.lineTo(21.5f, 20f); path.lineTo(2.5f, 20f); path.close()
            c.drawPath(path, line)
            c.drawLine(12f, 9.5f, 12f, 13.5f, line)
            fill.color = INK
            c.drawCircle(12f, 16.8f, 1.3f, fill)
        } else {
            stroke(INK, 2f, Paint.Cap.ROUND)
            path.rewind()
            path.moveTo(14f, 14.76f)
            path.lineTo(14f, 3.5f)
            rect.set(9f, 1f, 14f, 6f)
            path.arcTo(rect, 0f, -180f)
            path.lineTo(9f, 14.76f)
            rect.set(7f, 14f, 16f, 23f)
            path.arcTo(rect, 236.2f, -292.4f)
            path.close()
            c.drawPath(path, line)
        }
        c.restore()

        if (warning) style(bold, 14f, INK, Paint.Align.LEFT, 0.02f) else style(plain, 13f, LABEL, Paint.Align.LEFT)
        c.drawText(message, left + 12f + 16f + 8f, NOTICE_Y + (if (warning) 5f else 4.6f), text)
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
        const val DESIGN_W = 736f
        const val DESIGN_H = 442f
        private const val CENTRE_X = 368f
        private const val CENTRE_Y = 217f
        private const val LIGHTS_Y = 24f
        private const val NOTICE_Y = 413f

        // gauges
        private const val SIDE_R = 116f
        /** The oil dials are drawn at 116 and enlarged by this much. */
        private const val SIDE_SCALE = 1.1f
        // tucked 76 units behind the G-meter
        private const val LEFT_X = 154.4f
        private const val RIGHT_X = 581.6f
        private const val BAND_R = SIDE_R - 35f
        private const val HUB_R = 24f
        private const val NUMERAL = 21f
        private const val READOUT_W = 72f
        private const val G_R = 162f
        private const val G_SCALE = 60f   // design units per g

        // limits
        const val OIL_HOT = 130
        const val GEAR_HOT = 120
        const val COLD_BELOW = 80

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
        private val HOT = 0xFFFF1A1A.toInt()
        private val HOT_FILL = 0xFFC8101A.toInt()
        private val PEAK = 0xFFF5A623.toInt()
        private val NEEDLE_LIGHT = 0xFFFF3A2A.toInt()
        private val NEEDLE_DARK = 0xFFB00800.toInt()
        private val NEEDLE_RIDGE = 0xFFFFC8BC.toInt()

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
