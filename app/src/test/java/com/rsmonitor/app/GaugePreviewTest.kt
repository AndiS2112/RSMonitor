package com.rsmonitor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.rsmonitor.app.car.Centre
import com.rsmonitor.app.car.GaugeFrame
import com.rsmonitor.app.car.GaugePainter
import com.rsmonitor.app.drive.GTracker
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
        connected = true, live = true, rate = 13f, gStage = GTracker.Stage.Ready,
        peakAccel = 0.36f, peakRight = 0.88f, peakBrake = 0.95f, peakLeft = 0.82f,
        trace = trace(0.36f, 0.88f, 0.95f, 0.82f),
    )

    @Test
    fun coldStart() = render(
        "1-cold-start",
        GaugeFrame(
            connected = true, live = true, rate = 13f, rpm = 1500, oilTemp = 46, gearTemp = 38,
            oilPeak = 46, gearPeak = 38, gStage = GTracker.Stage.Ready,
            gLat = 0.02f, gLon = 0.01f, peakAccel = 0.08f, peakRight = 0.10f, peakBrake = 0.12f, peakLeft = 0.09f,
            trace = trace(0.08f, 0.10f, 0.12f, 0.09f),
        ),
    )

    @Test
    fun driving() = render(
        "2-driving",
        driven.copy(rpm = 6300, oilTemp = 104, gearTemp = 88, oilPeak = 104, gearPeak = 88, gLat = 0.62f, gLon = -0.28f),
    )

    @Test
    fun hot() = render(
        "3-hot",
        driven.copy(rpm = 8300, oilTemp = 141, gearTemp = 126, oilPeak = 141, gearPeak = 126, gLat = -0.55f, gLon = 0.20f),
    )

    /** After a hard run: the needles have dropped back, the amber hands stay at the peaks. */
    @Test
    fun coolingDown() = render(
        "4-cooling-down",
        driven.copy(rpm = 2100, oilTemp = 112, gearTemp = 96, oilPeak = 138, gearPeak = 121, gLat = 0.05f, gLon = 0.03f),
    )

    /** What the car shows today: engine speed only, G-meter waiting to be calibrated. */
    @Test
    fun todayInTheCar() = render("5-today-rpm-only", GaugeFrame(connected = true, live = true, rate = 13f, rpm = 820))

    @Test
    fun calibrating() = render(
        "6-calibrating",
        GaugeFrame(connected = true, live = true, rate = 13f, rpm = 820, gStage = GTracker.Stage.AwaitingPullAway),
    )

    @Test
    fun notConnected() = render("7-not-connected", GaugeFrame())

    // ---- the centre dial switched to power and torque ----

    private val powerPage = driven.copy(centre = Centre.Power, oilPeak = 118, gearPeak = 96)

    /** Full throttle at 5,000 rpm: peak torque. */
    @Test
    fun powerAtPeakTorque() = render(
        "8-power-peak-torque",
        powerPage.copy(rpm = 5000, oilTemp = 104, gearTemp = 88, torque = 430, power = 306, torquePeak = 430, powerPeak = 306),
    )

    /** Full throttle at 8,250 rpm: peak power, shift lights flashing. */
    @Test
    fun powerAtPeakPower() = render(
        "9-power-peak-power",
        powerPage.copy(rpm = 8250, oilTemp = 106, gearTemp = 90, torque = 383, power = 450, torquePeak = 430, powerPeak = 450),
    )

    @Test
    fun powerCruising() = render(
        "10-power-cruising",
        powerPage.copy(rpm = 2400, oilTemp = 108, gearTemp = 92, torque = 112, power = 38, torquePeak = 430, powerPeak = 450),
    )

    /** The engine does not offer torque: empty rings, dashes and a notice. */
    @Test
    fun powerWithoutTorque() = render(
        "11-power-no-torque",
        GaugeFrame(connected = true, live = true, rate = 13f, rpm = 820, centre = Centre.Power, torqueMissing = true),
    )
}
