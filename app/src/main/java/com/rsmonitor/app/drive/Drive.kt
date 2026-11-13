package com.rsmonitor.app.drive

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the car is doing right now and the peaks since the last reset. Shared by the phone
 * screen and the Android Auto screen (same process).
 *
 * G-force comes from the phone's accelerometer; power, torque and (once their data IDs are
 * known) the oil temperatures are fed in by the OBD connection.
 */
object Drive : SensorEventListener {
    data class State(
        val stage: GTracker.Stage = GTracker.Stage.Uncalibrated,
        val lat: Float = 0f,
        val lon: Float = 0f,
        val peakAccel: Float = 0f,
        val peakRight: Float = 0f,
        val peakBrake: Float = 0f,
        val peakLeft: Float = 0f,
        val trace: List<Float> = emptyList(),
        val oilPeak: Int? = null,
        val gearPeak: Int? = null,
        val powerPeak: Int? = null,
        val torquePeak: Int? = null,
    )

    private val tracker = GTracker()
    private val peaks = Peaks()
    private var manager: SensorManager? = null
    private var users = 0
    private var lastPublished = 0L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val callout = PeakCallout()
    private val _peakTempsUntil = MutableStateFlow(PeakCallout.HIDDEN)

    /**
     * When the exact peak oil temperatures leave the car screen again, on the
     * [SystemClock.elapsedRealtime] clock; [PeakCallout.HIDDEN] while they are not shown.
     */
    val peakTempsUntil: StateFlow<Long> = _peakTempsUntil

    /** The phone's Peak Temp button: shows the exact peaks on the car screen, or puts them away. */
    fun togglePeakTemps() {
        _peakTempsUntil.value = callout.toggle(SystemClock.elapsedRealtime())
    }

    fun peakTempsShown(): Boolean = callout.isShown(SystemClock.elapsedRealtime())

    /** Starts listening to the accelerometer. Each caller must pair this with [release]. */
    @Synchronized
    fun acquire(context: Context) {
        if (users++ > 0) return
        val m = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        manager = m
        m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    @Synchronized
    fun release() {
        if (users == 0) return
        if (--users == 0) manager?.unregisterListener(this)
    }

    /** Begins calibration: the car must be parked and the phone already in its place. */
    @Synchronized
    fun calibrate() {
        tracker.calibrate()
        peaks.resetG()
        publish()
    }

    /** Swaps front and back if the pull-away step was done in reverse. */
    @Synchronized
    fun flip() {
        tracker.flip()
        peaks.resetG()
        publish()
    }

    @Synchronized
    fun resetPeakG() {
        peaks.resetG()
        publish()
    }

    /** The car has gone quiet (switched off): forget every peak. */
    @Synchronized
    fun resetAllPeaks() {
        peaks.resetAll()
        publish()
    }

    @Synchronized
    fun onOilTemperature(celsius: Int) {
        peaks.onOil(celsius)
        publish()
    }

    @Synchronized
    fun onGearboxTemperature(celsius: Int) {
        peaks.onGear(celsius)
        publish()
    }

    @Synchronized
    fun onPower(horsepower: Int, newtonMetres: Int) {
        val before = peaks.power to peaks.torque
        peaks.onPower(horsepower, newtonMetres)
        if (before != peaks.power to peaks.torque) publish()
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        val before = tracker.stage
        tracker.onSample(event.values[0], event.values[1], event.values[2], event.timestamp)
        if (tracker.stage == GTracker.Stage.Ready) peaks.onG(tracker.lat, tracker.lon)
        // the screens do not need every reading; about 16 updates a second is plenty
        if (tracker.stage != before || event.timestamp - lastPublished >= 60_000_000L) {
            lastPublished = event.timestamp
            publish()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publish() {
        _state.value = State(
            stage = tracker.stage,
            lat = tracker.lat,
            lon = tracker.lon,
            peakAccel = peaks.accel,
            peakRight = peaks.right,
            peakBrake = peaks.brake,
            peakLeft = peaks.left,
            trace = peaks.trace(),
            oilPeak = peaks.oil,
            gearPeak = peaks.gear,
            powerPeak = peaks.power,
            torquePeak = peaks.torque,
        )
    }
}
