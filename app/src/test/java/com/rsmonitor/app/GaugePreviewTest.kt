package com.rsmonitor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.rsmonitor.app.car.GaugeFrame
import com.rsmonitor.app.car.GaugePainter
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the gauge screen to PNG files (app/build/outputs/gauge-previews) at the size the car
 * gives the app, so the design can be looked at without a car. The build keeps the files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GaugePreviewTest {
    // the usable area the car reports to the app (read off the car screen)
    private val width = 1156
    private val height = 606

    private fun render(name: String, frame: GaugeFrame) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        GaugePainter().paint(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()), frame)
        val dir = File("build/outputs/gauge-previews").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // something was drawn: the G-meter's centre is not the screen colour of the corner
        assertNotEquals(bitmap.getPixel(2, 2), bitmap.getPixel(width / 2, height / 2 + 40))
    }

    /** A plausible peak outline: an ellipse per quadrant. */
    private fun trace(accel: Float, right: Float, brake: Float, left: Float): List<Float> = List(72) { i ->
        val a = Math.toRadians(i * 5.0)
        val sx = if (sin(a) >= 0) right else left
        val sy = if (cos(a) >= 0) accel else brake
        (1.0 / sqrt((sin(a) / sx) * (sin(a) / sx) + (cos(a) / sy) * (cos(a) / sy))).toFloat()
    }

    private val driven = GaugeFrame(
        connected = true, live = true, rate = 13f,
        peakAccel = 0.36f, peakRight = 0.88f, peakBrake = 0.95f, peakLeft = 0.82f,
        trace = trace(0.36f, 0.88f, 0.95f, 0.82f),
    )

    @Test
    fun coldStart() = render(
        "1-cold-start",
        GaugeFrame(
            connected = true, live = true, rate = 13f, rpm = 1500, oilTemp = 46, gearTemp = 38,
            gLat = 0.02f, gLon = 0.01f, peakAccel = 0.08f, peakRight = 0.10f, peakBrake = 0.12f, peakLeft = 0.09f,
            trace = trace(0.08f, 0.10f, 0.12f, 0.09f),
        ),
    )

    @Test
    fun driving() = render("2-driving", driven.copy(rpm = 6300, oilTemp = 104, gearTemp = 88, gLat = 0.62f, gLon = -0.28f))

    @Test
    fun hot() = render("3-hot", driven.copy(rpm = 8300, oilTemp = 141, gearTemp = 126, gLat = -0.55f, gLon = 0.20f))

    @Test
    fun todayInTheCar() = render("4-today-rpm-only", GaugeFrame(connected = true, live = true, rate = 13f, rpm = 820))

    @Test
    fun notConnected() = render("5-not-connected", GaugeFrame())
}
