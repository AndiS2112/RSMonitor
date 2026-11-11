package com.rsmonitor.app.obd

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import com.rsmonitor.app.drive.Drive
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

    // The two oil temperatures stay empty until their data IDs on this car are known.
    private val _oilTemp = MutableStateFlow<Int?>(null)
    val oilTemp: StateFlow<Int?> = _oilTemp

    private val _gearTemp = MutableStateFlow<Int?>(null)
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
        poller?.cancel()
        poller = null
        transport?.close()
        transport = null
        elm = null
        adapterHeader = ""
        _rpm.value = null
        _rate.value = 0f
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
    private suspend fun ask(e: Elm327, unit: String, request: String): List<String> =
        try {
            lock.withLock {
                ensureHeader(e, unit)
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

    /** Turns continuous engine-speed polling on or off. */
    fun setLive(on: Boolean) {
        _live.value = on
        poller?.cancel()
        poller = null
        _rpm.value = null
        _rate.value = 0f
        if (on && _link.value == Link.Ready) startPolling()
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            var count = 0
            var windowStart = SystemClock.elapsedRealtime()
            var lastReading = windowStart
            while (isActive && _link.value == Link.Ready) {
                val e = elm ?: break
                try {
                    val reply = lock.withLock {
                        ensureHeader(e, ENGINE)
                        e.command("010C", 1_500)
                    }
                    val value = parseRpm(reply)
                    if (value != null) {
                        _rpm.value = value
                        count++
                        lastReading = SystemClock.elapsedRealtime()
                        heard()
                    }
                } catch (ex: LinkLost) {
                    append("010C", ex.message ?: "Adapter disconnected", failed = true)
                    disconnect()
                    break
                } catch (ex: IOException) {
                    delay(200)
                }
                val now = SystemClock.elapsedRealtime()
                // ignition off with the adapter still powered: stop showing the last engine speed
                if (now - lastReading > STALE_MS) _rpm.value = null
                if (now - windowStart >= 1_000) {
                    _rate.value = count * 1_000f / (now - windowStart)
                    count = 0
                    windowStart = now
                }
            }
        }
    }

    /** Points the adapter at a control unit. Call only while holding [lock]. */
    private suspend fun ensureHeader(e: Elm327, header: String) {
        if (adapterHeader == header) return
        val reply = e.command("ATSH$header")
        append("ATSH$header", reply)
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
