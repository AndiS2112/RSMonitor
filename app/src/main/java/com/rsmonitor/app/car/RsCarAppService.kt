package com.rsmonitor.app.car

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
 * Milestone 1 test screen: engine speed and the shift lights, drawn on the surface a
 * navigation-category app is given. The full gauge design replaces this drawing later.
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
                GaugeRenderer.Frame(link, live, rpm, rate)
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

/** Draws one frame onto the car's surface whenever the data changes. */
class GaugeRenderer : SurfaceCallback {
    data class Frame(val link: ObdHub.Link, val live: Boolean, val rpm: Int?, val rate: Float)

    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var frame = Frame(ObdHub.Link.Disconnected, false, null, 0f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val plain = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val numerals = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)

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

    fun show(next: Frame) {
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
            paint(canvas)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private fun paint(c: Canvas) {
        val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
        val h = area.height().toFloat()
        val top = area.top.toFloat()
        val cx = area.exactCenterX()

        c.drawColor(SCREEN)

        // title
        text.typeface = bold
        text.color = INK
        text.textSize = h * 0.05f
        text.letterSpacing = 0.3f
        c.drawText("RS MONITOR", cx, top + h * 0.10f, text)
        text.letterSpacing = 0f

        // shift lights
        val rpm = frame.rpm
        val colours = if (rpm != null) ShiftLights.colours(rpm) else List(ShiftLights.ON_AT.size) { null }
        val flashing = rpm != null && ShiftLights.isFlashing(rpm)
        val dimPhase = flashing && (SystemClock.elapsedRealtime() / (ShiftLights.FLASH_PERIOD_MS / 2)) % 2 == 1L
        val r = h * 0.04f
        val gap = h * 0.035f
        val rowWidth = colours.size * 2 * r + (colours.size - 1) * gap
        var x = cx - rowWidth / 2 + r
        val y = top + h * 0.22f
        stroke.strokeWidth = (h * 0.004f).coerceAtLeast(1f)
        for (colour in colours) {
            if (colour != null && !dimPhase) {
                fill.color = colour
                c.drawCircle(x, y, r, fill)
            } else {
                fill.color = SOCKET
                c.drawCircle(x, y, r, fill)
                stroke.color = LINE
                c.drawCircle(x, y, r, stroke)
            }
            x += 2 * r + gap
        }

        // engine speed
        text.typeface = numerals
        text.color = INK
        text.textSize = h * 0.30f
        c.drawText(rpm?.toString() ?: "–", cx, top + h * 0.66f, text)
        text.typeface = bold
        text.color = LABEL
        text.textSize = h * 0.05f
        text.letterSpacing = 0.16f
        c.drawText("RPM", cx, top + h * 0.76f, text)
        text.letterSpacing = 0f

        // status
        text.typeface = plain
        text.color = SECONDARY
        text.textSize = h * 0.045f
        val status = when {
            frame.link != ObdHub.Link.Ready -> "Adapter not connected. Open RS Monitor on the phone."
            !frame.live -> "Connected. Live data is off."
            else -> "OBD · ${"%.0f".format(frame.rate)} Hz"
        }
        c.drawText(status, cx, top + h * 0.92f, text)
    }

    private companion object {
        val SCREEN = 0xFF18181A.toInt()
        val SOCKET = 0xFF0B0B0C.toInt()
        val LINE = 0xFF3A3A3E.toInt()
        val INK = 0xFFFFFFFF.toInt()
        val LABEL = 0xFFD6D6D6.toInt()
        val SECONDARY = 0xFFA8A8AE.toInt()
    }
}
