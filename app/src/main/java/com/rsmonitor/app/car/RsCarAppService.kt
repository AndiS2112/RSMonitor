package com.rsmonitor.app.car

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rsmonitor.app.R
import com.rsmonitor.app.drive.Drive
import com.rsmonitor.app.drive.GTracker
import com.rsmonitor.app.obd.ObdHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Entry point Android Auto binds to. */
class RsCarAppService : CarAppService() {
    // The app is sideloaded for one car, not distributed, so any host may connect.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = GaugeScreen(carContext)
    }
}

/**
 * The gauge screen. A navigation-category app is given a surface to draw on, and the design
 * is painted there by [GaugePainter].
 */
class GaugeScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private val renderer = GaugeRenderer()
    private val tail = GTail()

    init {
        CarScreen.load(carContext)
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)

        Drive.acquire(carContext)

        lifecycleScope.launch {
            // About 30 times a second: gather the latest readings and repaint if anything
            // changed. This also keeps the upshift flash going.
            while (isActive) {
                renderer.show(currentFrame())
                delay(FRAME_MS)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Drive.release()
    }

    private fun currentFrame(): GaugeFrame {
        val drive = Drive.state.value
        val rpm = ObdHub.rpm.value
        val ready = drive.stage == GTracker.Stage.Ready
        val now = SystemClock.elapsedRealtime()
        if (ready) tail.add(now, drive.lat, drive.lon) else tail.clear()
        val oil = ObdHub.oilTemp.value
        val gearbox = ObdHub.gearTemp.value
        // outside the red zones the value never changes, so the screen is not repainted for it
        val inRedZone = (oil != null && oil >= GaugePainter.OIL_HOT) || (gearbox != null && gearbox >= GaugePainter.GEAR_HOT)
        // both halves of the flash last half a period; outside the flash the value never changes
        val flashOn = rpm == null || !ShiftLights.isFlashing(rpm) ||
            (SystemClock.elapsedRealtime() / (ShiftLights.FLASH_PERIOD_MS / 2)) % 2 == 0L
        return GaugeFrame(
            connected = ObdHub.link.value == ObdHub.Link.Ready,
            live = ObdHub.live.value,
            rate = ObdHub.rate.value,
            rpm = rpm,
            oilTemp = oil,
            gearTemp = gearbox,
            gear = ObdHub.gear.value,
            oilPeak = drive.oilPeak,
            gearPeak = drive.gearPeak,
            showPeaks = Drive.peakTempsShown(),
            gStage = drive.stage,
            gLat = if (ready) drive.lat else null,
            gLon = if (ready) drive.lon else null,
            peakAccel = drive.peakAccel,
            peakRight = drive.peakRight,
            peakBrake = drive.peakBrake,
            peakLeft = drive.peakLeft,
            // only the G-meter draws it; left out elsewhere, the other page is not repainted for it
            tail = if (CarScreen.centre.value == Centre.GMeter) tail.points(now) else GTailPoints.NONE,
            centre = CarScreen.centre.value,
            power = ObdHub.power.value,
            torque = ObdHub.torque.value,
            powerPeak = drive.powerPeak,
            torquePeak = drive.torquePeak,
            pulse = if (inRedZone) redZonePulse(SystemClock.elapsedRealtime()) else 1f,
            flashOn = flashOn,
        )
    }

    override fun onGetTemplate(): Template = gaugeTemplate(carContext) { CarScreen.switchCentre() }

    private companion object {
        const val FRAME_MS = 33L
    }
}

/**
 * The controls live on the phone. Android Auto does not allow this kind of screen without at
 * least one button, so one small icon button stays: it switches the centre dial's page, the
 * one control worth having within reach of the car screen. Android Auto shows it for a few
 * seconds after the screen is touched and then puts it away.
 */
fun gaugeTemplate(context: Context, onSwitchCentre: () -> Unit): Template {
    val switchPage = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(context, R.drawable.ic_switch_page)).build())
        .setOnClickListener { onSwitchCentre() }
        .build()
    return NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(switchPage).build())
        .build()
}

/** Paints a frame onto the car's surface whenever the data changes. */
class GaugeRenderer : SurfaceCallback {
    private val painter = GaugePainter()
    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var frame = GaugeFrame()
    private var painted: GaugeFrame? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        painted = null
        draw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    /** Paints [next] unless it is exactly what the screen already shows. */
    fun show(next: GaugeFrame) {
        frame = next
        if (next != painted) draw()
    }

    private fun draw() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: RuntimeException) {
            // the surface went away between the check and the lock
            return
        }
        try {
            CarScreen.report(canvas.width, canvas.height, visibleArea)
            painter.paint(canvas, gaugeArea(visibleArea, canvas.width, canvas.height), frame)
            painted = frame
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
