package com.rsmonitor.app.car

import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)

        Drive.acquire(carContext)

        lifecycleScope.launch {
            // About 20 times a second: gather the latest readings and repaint if anything
            // changed. This also keeps the upshift flash going.
            while (isActive) {
                renderer.show(currentFrame())
                delay(FRAME_MS)
            }
        }
        lifecycleScope.launch {
            // the button's label follows the live state
            ObdHub.live.collect { invalidate() }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Drive.release()
    }

    private fun currentFrame(): GaugeFrame {
        val drive = Drive.state.value
        val rpm = ObdHub.rpm.value
        val ready = drive.stage == GTracker.Stage.Ready
        // both halves of the flash last half a period; outside the flash the value never changes
        val flashOn = rpm == null || !ShiftLights.isFlashing(rpm) ||
            (SystemClock.elapsedRealtime() / (ShiftLights.FLASH_PERIOD_MS / 2)) % 2 == 0L
        return GaugeFrame(
            connected = ObdHub.link.value == ObdHub.Link.Ready,
            live = ObdHub.live.value,
            rate = ObdHub.rate.value,
            rpm = rpm,
            oilTemp = ObdHub.oilTemp.value,
            gearTemp = ObdHub.gearTemp.value,
            oilPeak = drive.oilPeak,
            gearPeak = drive.gearPeak,
            gStage = drive.stage,
            gLat = if (ready) drive.lat else null,
            gLon = if (ready) drive.lon else null,
            peakAccel = drive.peakAccel,
            peakRight = drive.peakRight,
            peakBrake = drive.peakBrake,
            peakLeft = drive.peakLeft,
            trace = drive.trace,
            flashOn = flashOn,
        )
    }

    override fun onGetTemplate(): Template {
        val live = ObdHub.live.value
        val toggle = Action.Builder()
            .setTitle(if (live) "Stop live" else "Start live")
            .setOnClickListener { ObdHub.setLive(!ObdHub.live.value) }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(toggle).build())
            .build()
    }

    private companion object {
        const val FRAME_MS = 50L
    }
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
            val area = visibleArea?.takeIf { !it.isEmpty }?.let { RectF(it) }
                ?: RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            painter.paint(canvas, area, frame)
            painted = frame
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
