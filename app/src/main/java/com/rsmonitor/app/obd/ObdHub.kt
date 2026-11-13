package com.rsmonitor.app.obd

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import com.rsmonitor.app.drive.Drive
import com.rsmonitor.app.drive.Gear
import com.rsmonitor.app.drive.metricHorsepower
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one connection to the car, shared by the phone screen and the Android Auto screen
 * (both run in the same process). Everything that talks to the adapter goes through here,
 * one command at a time.
 */
object ObdHub {
    enum class Link { Disconnected, Connecting, Ready }

    data class LogLine(val command: String, val reply: String, val failed: Boolean)

    const val ENGINE = "7E0"
    const val GEARBOX = "7E1"

    private const val LOG_LIMIT = 200

    /** No answer from the car for this long counts as "the car has been switched off". */
    private const val CAR_OFF_MS = 30_000L

    /** An engine-speed reading older than this is no longer shown. */
    private const val STALE_MS = 2_000L

    /** Oil temperatures move slowly, and every question about them costs an engine-speed reading. */
    private const val GEAR_PERIOD_MS = 3_000L
    private const val GEAR_STALE_MS = 10_000L
    private const val OIL_PERIOD_MS = 3_000L
    private const val OIL_STALE_MS = 10_000L

    /** The gear and the selector are asked for twice a second; a gear older than this is not shown. */
    private const val SELECTOR_PERIOD_MS = 500L
    private const val SELECTOR_STALE_MS = 3_000L

    /** After this many rounds without engine speed from the gearbox, the engine is asked instead. */
    private const val GEARBOX_GIVE_UP = 5
    private const val GEARBOX_RETRY_MS = 5_000L

    /** Engine speed, engine torque and the gear, asked for together while the power page shows. */
    private val FAST_IDS = listOf(GEARBOX_SPEED_ID, GEARBOX_TORQUE_ID, GEARBOX_GEAR_ID)
    private val FAST_SIZES = listOf(2, 2, 1)

    /** Set by the car screen: true while the centre dial shows power and torque. */
    @Volatile
    var torqueWanted = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var transport: Transport? = null
    private var elm: Elm327? = null
    private var poller: Job? = null

    /** The header the adapter is currently set to, so it is only changed when needed. */
    private var adapterHeader = ""

    private val _link = MutableStateFlow(Link.Disconnected)
    val link: StateFlow<Link> = _link

    private val _adapter = MutableStateFlow("")
    /** The adapter's own identification line, for example "ELM327 v1.5". */
    val adapter: StateFlow<String> = _adapter

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())
    val log: StateFlow<List<LogLine>> = _log

    private val _unit = MutableStateFlow(ENGINE)
    /** The control unit the console sends to. */
    val unit: StateFlow<String> = _unit

    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live

    private val _rpm = MutableStateFlow<Int?>(null)
    val rpm: StateFlow<Int?> = _rpm

    private val _rate = MutableStateFlow(0f)
    /** Engine-speed readings per second while live. */
    val rate: StateFlow<Float> = _rate

    /** Whether torque has been read since live data was switched on. */
    enum class TorqueSource { Unknown, Available, Missing }

    private val _torqueSource = MutableStateFlow(TorqueSource.Unknown)
    val torqueSource: StateFlow<TorqueSource> = _torqueSource

    private val _torque = MutableStateFlow<Int?>(null)
    /** Engine torque in Nm, as the gearbox receives it from the engine. Never below zero. */
    val torque: StateFlow<Int?> = _torque

    private val _power = MutableStateFlow<Int?>(null)
    /** Engine power in metric horsepower, from torque and engine speed. */
    val power: StateFlow<Int?> = _power

    private val _oilTemp = MutableStateFlow<Int?>(null)
    /** Engine oil temperature in °C, asked from the engine control unit every few seconds while live. */
    val oilTemp: StateFlow<Int?> = _oilTemp

    private val _gear = MutableStateFlow<Gear?>(null)
    /** The selector position and gear, asked from the gearbox twice a second while live. */
    val gear: StateFlow<Gear?> = _gear

    private val _gearTemp = MutableStateFlow<Int?>(null)
    /** Gearbox oil temperature in °C, asked from the gearbox control unit every few seconds while live. */
    val gearTemp: StateFlow<Int?> = _gearTemp

    /** When the car last answered anything, on the SystemClock.elapsedRealtime clock. */
    @Volatile private var lastHeard = 0L

    /** True once the peaks have been cleared for the current silence (or nothing was ever heard). */
    @Volatile private var silenceHandled = true

    private fun heard() {
        lastHeard = SystemClock.elapsedRealtime()
        silenceHandled = false
    }

    /** One data ID that answered during a scan, with the bytes it returned. */
    data class ScanHit(val id: Int, val data: List<Int>)

    data class ScanState(
        val running: Boolean = false,
        val unit: String = ENGINE,
        val from: Int = 0,
        val to: Int = 0,
        val current: Int = 0,
        val tried: Int = 0,
        val hits: List<ScanHit> = emptyList(),
        /** True when the unit accepts several IDs per request, which makes the scan about three times faster. */
        val batched: Boolean = false,
        val extendedSession: Boolean = false,
    ) {
        /** The results as plain text, one ID per line, for sharing. */
        fun report(): String = buildString {
            val name = if (unit == ENGINE) "engine" else "gearbox"
            append("RS Monitor scan · $name ($unit) · ${"%04X".format(from)}–${"%04X".format(current)} · ")
            append("tried $tried · ${hits.size} answered")
            if (extendedSession) append(" · extended session")
            append('\n')
            for (hit in hits) {
                append("%04X".format(hit.id)).append(": ").append(hexString(hit.data)).append('\n')
            }
        }
    }

    private var scanner: Job? = null
    private val _scan = MutableStateFlow(ScanState())
    /** Progress and results of the data-ID scan. */
    val scan: StateFlow<ScanState> = _scan

    /** Progress of a drive recording; the rows themselves are fetched with [recordingReport]. */
    data class RecordState(
        val running: Boolean = false,
        val rows: Int = 0,
        val seconds: Int = 0,
        /** True when the gearbox answers several IDs in one request, which makes the rows come faster. */
        val batched: Boolean = false,
    )

    private var recorder: Job? = null
    private val recordedRows = ArrayList<String>()
    private val _record = MutableStateFlow(RecordState())
    val record: StateFlow<RecordState> = _record

    fun connect(context: Context, device: BluetoothDevice, lowEnergy: Boolean) {
        if (_link.value != Link.Disconnected) return
        _link.value = Link.Connecting
        val appContext = context.applicationContext
        scope.launch {
            val t: Transport = if (lowEnergy) BleTransport(appContext, device) else ClassicTransport(device)
            try {
                lock.withLock {
                    t.open()
                    val e = Elm327(t)
                    _adapter.value = e.initialise { cmd, reply -> append(cmd, reply) }
                    transport = t
                    elm = e
                    adapterHeader = ""
                }
                _link.value = Link.Ready
                if (_live.value) startPolling()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // IOException from the link, SecurityException if Bluetooth permission was withdrawn
                append("connect", ex.message ?: "Connection failed", failed = true)
                t.close()
                _link.value = Link.Disconnected
            }
        }
    }

    fun disconnect() {
        scanner?.cancel()
        scanner = null
        recorder?.cancel()
        recorder = null
        poller?.cancel()
        poller = null
        transport?.close()
        transport = null
        elm = null
        adapterHeader = ""
        _rpm.value = null
        _rate.value = 0f
        clearReadings()
        _adapter.value = ""
        _link.value = Link.Disconnected
    }

    /** Chooses which control unit console requests go to: [ENGINE] or [GEARBOX]. */
    fun selectUnit(header: String) {
        _unit.value = header
    }

    /** Sends a line typed in the console and logs the reply. */
    fun send(raw: String) {
        val cmd = raw.trim().uppercase()
        if (cmd.isEmpty()) return
        val e = elm
        if (e == null) {
            append(cmd, "Not connected", failed = true)
            return
        }
        scope.launch {
            try {
                val reply = lock.withLock {
                    val compact = cmd.replace(" ", "")
                    if (compact.startsWith("AT")) {
                        // an adapter command; remember a header the user sets by hand
                        if (compact.startsWith("ATSH")) adapterHeader = compact.removePrefix("ATSH")
                    } else {
                        ensureHeader(e, _unit.value)
                    }
                    e.command(cmd)
                }
                if (isCarReply(reply)) heard()
                append(cmd, reply)
            } catch (ex: LinkLost) {
                append(cmd, ex.message ?: "Adapter disconnected", failed = true)
                disconnect()
            } catch (ex: IOException) {
                append(cmd, ex.message ?: "No reply", failed = true)
            }
        }
    }

    /**
     * Asks the selected control unit for every data ID from [from] to [to] (read-only "22"
     * requests) and records the ones that answer. Live polling is switched off while it runs.
     *
     * With [extendedSession] the unit is first asked to open an extended diagnostic session
     * ("10 03"), in which some units reveal more IDs. The scan's own requests keep it open.
     *
     * If the unit accepts several IDs in one request, IDs are asked three at a time and only
     * groups that answer are then asked one by one.
     */
    fun startScan(from: Int, to: Int, extendedSession: Boolean) {
        val e = elm ?: return
        if (_scan.value.running) return
        val lo = minOf(from, to).coerceIn(0, 0xFFFF)
        val hi = maxOf(from, to).coerceIn(0, 0xFFFF)
        val unit = _unit.value
        stopRecording()
        setLive(false)
        _scan.value = ScanState(running = true, unit = unit, from = lo, to = hi, current = lo, extendedSession = extendedSession)
        scanner = scope.launch {
            try {
                if (extendedSession) append("1003", ask(e, unit, "1003"))
                val batched = canBatch(e, unit)
                _scan.value = _scan.value.copy(batched = batched)

                var id = lo
                while (id <= hi && isActive) {
                    val group = (id..minOf(id + (if (batched) 2 else 0), hi)).toList()
                    val worthAsking = group.size == 1 ||
                        isPositiveRead(ask(e, unit, "22" + group.joinToString("") { "%04X".format(it) }))
                    var found = emptyList<ScanHit>()
                    if (worthAsking) {
                        for (one in group) {
                            val data = parseUdsData(ask(e, unit, "22%04X".format(one)), one)
                            if (data != null) found = found + ScanHit(one, data)
                        }
                    }
                    val before = _scan.value
                    _scan.value = before.copy(current = group.last(), tried = before.tried + group.size, hits = before.hits + found)
                    id = group.last() + 1
                }
            } catch (ex: LinkLost) {
                append("scan", ex.message ?: "Adapter disconnected", failed = true)
                disconnect()
            } finally {
                _scan.value = _scan.value.copy(running = false)
            }
        }
    }

    /** One request to a unit during a scan. A timeout counts as "no answer"; a lost link is thrown. */
    private suspend fun ask(e: Elm327, unit: String, request: String, log: Boolean = true): List<String> =
        try {
            lock.withLock {
                ensureHeader(e, unit, log)
                e.command(request, 1_200)
            }.also { if (isCarReply(it)) heard() }
        } catch (ex: LinkLost) {
            throw ex
        } catch (ex: IOException) {
            emptyList()
        }

    /**
     * Whether the unit answers a request that mixes an ID it refuses with one it supports.
     * The VIN (F190) is the supported one; units that only answer when every ID is valid
     * fail this and are scanned one ID at a time.
     */
    private suspend fun canBatch(e: Elm327, unit: String): Boolean {
        if (parseUdsData(ask(e, unit, "22F190"), 0xF190) == null) return false
        val refused = listOf(0x0001, 0x0002, 0xFFF0).firstOrNull { !isPositiveRead(ask(e, unit, "22%04X".format(it))) }
            ?: return false
        return isPositiveRead(ask(e, unit, "22%04X".format(refused) + "F190"))
    }

    fun stopScan() {
        scanner?.cancel()
        scanner = null
    }

    /**
     * Records what the gearbox reports while the car is driven: see [RecordPlan]. It only
     * reads values. Live polling and any scan are switched off while it runs, so the car
     * screen shows no engine speed during a recording.
     */
    fun startRecording() {
        val e = elm ?: return
        if (_record.value.running) return
        stopScan()
        setLive(false)
        synchronized(recordedRows) { recordedRows.clear() }
        _record.value = RecordState(running = true)
        recorder = scope.launch {
            suspend fun together(group: RecordPlan.Group): List<List<Int>>? =
                parseUdsGroup(ask(e, GEARBOX, group.request, log = false), group.ids, group.sizes)
            try {
                var batched = RecordPlan.GROUPS.all { together(it) != null }
                _record.value = _record.value.copy(batched = batched)
                val start = SystemClock.elapsedRealtime()
                var row = 0
                var misses = 0
                while (isActive && row < RecordPlan.MAX_ROWS) {
                    val fast = ArrayList<List<Int>?>(RecordPlan.FAST.size)
                    if (batched) {
                        var missed = false
                        for (group in RecordPlan.GROUPS) {
                            val values = together(group)
                            if (values != null) fast.addAll(values) else {
                                missed = true
                                group.ids.forEach { _ -> fast.add(null) }
                            }
                        }
                        // if the combined answers stop coming, ask one by one from then on
                        if (!missed) misses = 0 else if (++misses >= 5) batched = false
                    } else {
                        for (id in RecordPlan.FAST) fast.add(parseUdsData(ask(e, GEARBOX, "22%04X".format(id), log = false), id))
                    }
                    val extraId = RecordPlan.ROTATING[row % RecordPlan.ROTATING.size]
                    val extra = parseUdsData(ask(e, GEARBOX, "22%04X".format(extraId), log = false), extraId)
                    val engineRpm = if (row % RecordPlan.ENGINE_EVERY == 0) parseRpm(ask(e, ENGINE, "010C", log = false)) else null
                    val millis = SystemClock.elapsedRealtime() - start
                    synchronized(recordedRows) { recordedRows += recordRow(millis, fast, extraId, extra, engineRpm) }
                    row++
                    _record.value = RecordState(running = true, rows = row, seconds = (millis / 1000).toInt(), batched = batched)
                }
            } catch (ex: LinkLost) {
                append("record", ex.message ?: "Adapter disconnected", failed = true)
                disconnect()
            } finally {
                _record.value = _record.value.copy(running = false)
            }
        }
    }

    fun stopRecording() {
        recorder?.cancel()
        recorder = null
    }

    /** The recording as text: a title line, the column names, then one row per line. */
    fun recordingReport(): String {
        val state = _record.value
        val rows = synchronized(recordedRows) { recordedRows.toList() }
        return buildString {
            append("RS Monitor recording · gearbox (7E1) · ${rows.size} rows · ${state.seconds} s · ")
            append(if (state.batched) "several values per request" else "one value per request")
            append('\n')
            append(RecordPlan.COLUMNS).append('\n')
            for (row in rows) append(row).append('\n')
        }
    }

    /** Turns live data on or off. */
    fun setLive(on: Boolean) {
        // live polling and a recording would only slow each other down
        if (on) stopRecording()
        _live.value = on
        poller?.cancel()
        poller = null
        _rpm.value = null
        _rate.value = 0f
        clearReadings()
        if (on && _link.value == Link.Ready) startPolling()
    }

    private fun clearReadings() {
        _torque.value = null
        _power.value = null
        _torqueSource.value = TorqueSource.Unknown
        _gear.value = null
        _gearTemp.value = null
        _oilTemp.value = null
    }

    /**
     * Reads the car while live.
     *
     * Everything fast comes from the gearbox control unit, so the adapter does not have to be
     * pointed back and forth: engine speed in every round and, while the centre dial shows
     * power ([torqueWanted]), engine torque and the gear with it in the same answer. Twice a
     * second the gear and the selector are asked for; every few seconds the gearbox's mode
     * byte and oil temperature, and, from the engine control unit, the engine oil temperature.
     *
     * If the gearbox stops answering for engine speed, the engine is asked instead ("010C"),
     * and the gearbox is tried again every few seconds.
     */
    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            var count = 0
            var windowStart = SystemClock.elapsedRealtime()
            var lastReading = windowStart
            var lastTorque = windowStart
            var lastGear = 0L
            var groups = true              // the gearbox answers several IDs in one request
            var groupMisses = 0
            var gearboxMisses = 0          // rounds in a row without engine speed from the gearbox
            var gearboxRetryAt = 0L
            var engaged: Int? = null
            var selector: Int? = null
            var mode: Int? = null
            var selectorAsked = 0L
            var gearAsked = 0L
            var gearRead = 0L
            var oilAsked = 0L
            var oilRead = 0L

            /** Several gearbox IDs in one request; null when that did not work, and for good after three failures. */
            suspend fun group(e: Elm327, ids: List<Int>, sizes: List<Int>): List<List<Int>>? {
                if (!groups) return null
                val values = readGroup(e, GEARBOX, ids, sizes)
                if (values != null) groupMisses = 0 else if (++groupMisses >= 3) groups = false
                return values
            }

            while (isActive && _link.value == Link.Ready) {
                val e = elm ?: break
                try {
                    val started = SystemClock.elapsedRealtime()
                    var speed: Int? = null
                    var nm: Int? = null
                    var gearSeen = false

                    if (gearboxMisses < GEARBOX_GIVE_UP || started >= gearboxRetryAt) {
                        if (torqueWanted) {
                            val values = group(e, FAST_IDS, FAST_SIZES)
                            if (values != null) {
                                speed = gearboxEngineSpeed(values[0])
                                nm = gearboxEngineTorque(values[1])
                                engaged = values[2][0]
                                gearSeen = true
                            } else {
                                speed = gearboxEngineSpeed(readOne(e, GEARBOX, GEARBOX_SPEED_ID))
                                nm = gearboxEngineTorque(readOne(e, GEARBOX, GEARBOX_TORQUE_ID))
                            }
                        } else {
                            speed = gearboxEngineSpeed(readOne(e, GEARBOX, GEARBOX_SPEED_ID))
                        }
                        if (speed != null) {
                            gearboxMisses = 0
                        } else if (++gearboxMisses >= GEARBOX_GIVE_UP) {
                            gearboxRetryAt = SystemClock.elapsedRealtime() + GEARBOX_RETRY_MS
                        }
                    }
                    if (speed == null && gearboxMisses >= GEARBOX_GIVE_UP) {
                        val reply = lock.withLock {
                            ensureHeader(e, ENGINE, log = false)
                            e.command("010C", 1_500)
                        }
                        speed = parseRpm(reply)
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (speed != null) {
                        _rpm.value = speed
                        count++
                        lastReading = now
                        heard()
                    }
                    if (nm != null) {
                        lastTorque = now
                        // the dials start at zero; engine braking shows as none
                        val shown = nm.coerceAtLeast(0)
                        _torque.value = shown
                        _torqueSource.value = TorqueSource.Available
                        val rpm = speed ?: _rpm.value
                        if (rpm != null) {
                            val hp = metricHorsepower(shown, rpm)
                            _power.value = hp
                            Drive.onPower(hp, shown)
                        }
                    }
                    val answering = now - lastReading < STALE_MS

                    // twice a second: which gear, and where the selector is
                    if (answering && now - selectorAsked >= SELECTOR_PERIOD_MS) {
                        selectorAsked = now
                        val pair = group(e, listOf(GEARBOX_GEAR_ID, GEARBOX_SELECTOR_ID), listOf(1, 1))
                        if (pair != null) {
                            engaged = pair[0][0]
                            selector = pair[1][0]
                            gearSeen = true
                        } else {
                            readOne(e, GEARBOX, GEARBOX_GEAR_ID)?.firstOrNull()?.let {
                                engaged = it
                                gearSeen = true
                            }
                            readOne(e, GEARBOX, GEARBOX_SELECTOR_ID)?.firstOrNull()?.let { selector = it }
                        }
                    }

                    // every few seconds: the gearbox's mode byte and oil temperature
                    if (answering && now - gearAsked >= GEAR_PERIOD_MS) {
                        gearAsked = now
                        val pair = group(e, listOf(GEARBOX_MODE_ID, GEARBOX_OIL_ID), listOf(1, 1))
                        val oilByte = if (pair != null) {
                            mode = pair[0][0]
                            pair[1][0]
                        } else {
                            readOne(e, GEARBOX, GEARBOX_MODE_ID)?.firstOrNull()?.let { mode = it }
                            readOne(e, GEARBOX, GEARBOX_OIL_ID)?.firstOrNull()
                        }
                        if (oilByte != null) {
                            val celsius = oilByte - 40
                            gearRead = SystemClock.elapsedRealtime()
                            heard()
                            _gearTemp.value = celsius
                            Drive.onGearboxTemperature(celsius)
                        }
                    }

                    // and, at the same pace, the engine for its oil temperature
                    if (answering && now - oilAsked >= OIL_PERIOD_MS) {
                        oilAsked = now
                        val answer = lock.withLock {
                            ensureHeader(e, ENGINE, log = false)
                            e.command("22%04X".format(ENGINE_OIL_ID), 1_500)
                        }
                        val celsius = engineOilCelsius(answer)
                        if (celsius != null) {
                            oilRead = SystemClock.elapsedRealtime()
                            heard()
                            _oilTemp.value = celsius
                            Drive.onOilTemperature(celsius)
                        }
                    }

                    if (gearSeen) lastGear = SystemClock.elapsedRealtime()
                } catch (ex: LinkLost) {
                    append("live", ex.message ?: "Adapter disconnected", failed = true)
                    disconnect()
                    break
                } catch (ex: IOException) {
                    delay(200)
                }
                val now = SystemClock.elapsedRealtime()
                // ignition off with the adapter still powered: stop showing the last readings
                if (now - lastReading > STALE_MS) _rpm.value = null
                if (now - lastTorque > STALE_MS) {
                    _torque.value = null
                    _power.value = null
                }
                _gear.value = if (now - lastGear <= SELECTOR_STALE_MS) gearFrom(engaged, selector, mode) else null
                if (now - gearRead > GEAR_STALE_MS) _gearTemp.value = null
                if (now - oilRead > OIL_STALE_MS) _oilTemp.value = null
                if (now - windowStart >= 1_000) {
                    _rate.value = count * 1_000f / (now - windowStart)
                    count = 0
                    windowStart = now
                }
            }
        }
    }

    /** The data bytes of one ID while live, or null if the unit did not answer in time or refused. */
    private suspend fun readOne(e: Elm327, unit: String, id: Int): List<Int>? =
        try {
            val reply = lock.withLock {
                ensureHeader(e, unit, log = false)
                e.command("22%04X".format(id), 1_500)
            }
            parseUdsData(reply, id)
        } catch (ex: LinkLost) {
            throw ex
        } catch (ex: IOException) {
            null
        }

    /** Several IDs in one request while live; null unless the answer holds exactly those. */
    private suspend fun readGroup(e: Elm327, unit: String, ids: List<Int>, sizes: List<Int>): List<List<Int>>? =
        try {
            val reply = lock.withLock {
                ensureHeader(e, unit, log = false)
                e.command("22" + ids.joinToString("") { "%04X".format(it) }, 1_500)
            }
            parseUdsGroup(reply, ids, sizes)
        } catch (ex: LinkLost) {
            throw ex
        } catch (ex: IOException) {
            null
        }

    /** Points the adapter at a control unit. Call only while holding [lock]. */
    private suspend fun ensureHeader(e: Elm327, header: String, log: Boolean = true) {
        if (adapterHeader == header) return
        val reply = e.command("ATSH$header")
        // live polling hops between the engine and the gearbox all the time; keep that out of the console
        if (log) append("ATSH$header", reply)
        adapterHeader = header
    }

    private fun append(command: String, reply: List<String>) {
        append(command, reply.joinToString("\n") { prettyReply(it) }, failed = reply.isEmpty() || reply.any { isFailedReply(it) })
    }

    private fun append(command: String, reply: String, failed: Boolean) {
        val line = LogLine(command, reply.ifEmpty { "(no reply)" }, failed)
        _log.value = (_log.value + line).takeLast(LOG_LIMIT)
    }

    init {
        // Car-off watch. Once the car has answered at least once, 30 s without any answer
        // clears every peak. That covers both the Bluetooth link dropping and the ignition
        // going off while the adapter stays powered. The clock only runs while the app is
        // actually asking (live data or a scan) or the link is down; a connected but idle
        // console is not silence.
        scope.launch {
            var quietFrom = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                val asking = _link.value != Link.Ready || _live.value || _scan.value.running
                if (!asking) {
                    quietFrom = now
                    continue
                }
                if (!silenceHandled && now - maxOf(lastHeard, quietFrom) >= CAR_OFF_MS) {
                    silenceHandled = true
                    Drive.resetAllPeaks()
                }
            }
        }
    }
}
