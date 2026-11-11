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
import com.rsmonitor.app.obd.ObdHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
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

        lifecycleScope.launch {
            combine(ObdHub.link, ObdHub.live, ObdHub.rpm, ObdHub.rate) { link, live, rpm, rate ->
                // Oil temperatures and g-force have no data source yet, so they stay empty.
                GaugeFrame(connected = link == ObdHub.Link.Ready, live = live, rate = rate, rpm = rpm)
            }.collect { renderer.show(it) }
        }
        lifecycleScope.launch {
            // keeps the flash going while the engine is past the upshift point
            while (isActive) {
                delay(ShiftLights.FLASH_PERIOD_MS / 2)
                renderer.tick()
            }
        }
        lifecycleScope.launch {
            // the button's label follows the live state
            ObdHub.live.collect { invalidate() }
        }
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
}

/** Paints a frame onto the car's surface whenever the data changes. */
class GaugeRenderer : SurfaceCallback {
    private val painter = GaugePainter()
    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var frame = GaugeFrame()

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
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

    fun show(next: GaugeFrame) {
        frame = next
        draw()
    }

    /** Redraws only while the lights are flashing. */
    fun tick() {
        val rpm = frame.rpm ?: return
        if (ShiftLights.isFlashing(rpm)) draw()
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
            val flashOn = (SystemClock.elapsedRealtime() / (ShiftLights.FLASH_PERIOD_MS / 2)) % 2 == 0L
            painter.paint(canvas, area, frame.copy(flashOn = flashOn))
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
