package com.rsmonitor.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rsmonitor.app.car.CarScreen
import com.rsmonitor.app.car.Centre
import com.rsmonitor.app.drive.Drive
import com.rsmonitor.app.drive.GTracker
import com.rsmonitor.app.drive.PeakCallout
import com.rsmonitor.app.obd.DeviceFinder
import com.rsmonitor.app.obd.ObdHub
import com.rsmonitor.app.obd.hexString
import java.io.File
import kotlinx.coroutines.delay

// Palette from the design (see README).
private val Screen = Color(0xFF18181A)
private val Panel = Color(0xFF222225)
private val LogPanel = Color(0xFF0B0B0C)
private val Selected = Color(0xFF3A3A3E)
private val Edge = Color(0xFF6A6A70)
private val Ink = Color(0xFFFFFFFF)
private val Label = Color(0xFFD6D6D6)
private val Secondary = Color(0xFFA8A8AE)
private val Failed = Color(0xFFFF5A4A)
private val OnInk = Color(0xFF0B0B0C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CarScreen.load(this)
        setContent { ConsoleScreen() }
    }

    // the G-meter listens to the phone's motion sensor while this screen is open
    // (the Android Auto screen keeps its own hold on it)
    override fun onStart() {
        super.onStart()
        Drive.acquire(this)
    }

    override fun onStop() {
        Drive.release()
        super.onStop()
    }
}

/** Height of the log and of the scan results, which scroll inside the scrolling screen. */
private val LIST_HEIGHT = 320.dp

private fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasBluetoothPermission(context: Context): Boolean =
    bluetoothPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@Composable
private fun ConsoleScreen() {
    val context = LocalContext.current
    val link by ObdHub.link.collectAsStateWithLifecycle()
    val adapter by ObdHub.adapter.collectAsStateWithLifecycle()
    val log by ObdHub.log.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasBluetoothPermission(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBluetoothPermission(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Screen)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // The whole screen scrolls: the panels are taller than a phone, more so with the
            // keyboard up. The lists inside therefore have a height of their own.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Discovery console", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (link == ObdHub.Link.Ready) Ink else Edge))
                Text(
                    text = when (link) {
                        ObdHub.Link.Disconnected -> "No adapter connected"
                        ObdHub.Link.Connecting -> "Connecting…"
                        ObdHub.Link.Ready -> "Connected · $adapter"
                    },
                    color = Secondary,
                    fontSize = 13.sp,
                )
            }
        }

        CarScreenPanel()
        GMeterPanel()
        PeakTempPanel()

        when {
            !granted -> {
                Text(
                    "RS Monitor needs Bluetooth permission to find and talk to the OBD adapter.",
                    color = Label,
                    fontSize = 15.sp,
                )
                FilledButton("Allow Bluetooth") { askPermission.launch(bluetoothPermissions()) }
            }

            link == ObdHub.Link.Disconnected -> {
                AdapterPicker(Modifier.heightIn(max = 420.dp))
                if (log.isNotEmpty()) LogView(log, Modifier.heightIn(max = 160.dp))
            }

            link == ObdHub.Link.Connecting -> LogView(log, Modifier.height(LIST_HEIGHT))

            else -> ConsoleControls(log)
        }
    }
}

/**
 * Chooses which page the car screen's centre dial shows. Under it, once the car screen has
 * been up, the screen area Android Auto reported: useful when the layout looks wrong there.
 */
@Composable
private fun CarScreenPanel() {
    val centre by CarScreen.centre.collectAsStateWithLifecycle()
    val area by CarScreen.areaNote.collectAsStateWithLifecycle()
    val torqueEvery by CarScreen.torqueEvery.collectAsStateWithLifecycle()
    val rate by ObdHub.rate.collectAsStateWithLifecycle()
    val torqueRate by ObdHub.torqueRate.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("CENTRE DIAL")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((page, name) in listOf(Centre.GMeter to "G-meter", Centre.Power to "Power")) {
                if (page == centre) {
                    FilledButton(name, Modifier.weight(1f)) { CarScreen.show(page) }
                } else {
                    OutlineButton(name, Modifier.weight(1f)) { CarScreen.show(page) }
                }
            }
        }
        // how the readings are shared between engine speed and torque on the power page
        Text("On the Power page, read torque with every", color = Ink, fontSize = 15.sp)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Screen).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (n in CarScreen.TORQUE_EVERY_CHOICES) {
                val name = when (n) {
                    1 -> "reading"
                    2 -> "2nd"
                    3 -> "3rd"
                    else -> "${n}th"
                }
                Segment(name, n == torqueEvery, Modifier.weight(1f)) { CarScreen.setTorqueEvery(n) }
            }
        }
        val now = if (rate > 0f) "Now: engine speed ${"%.0f".format(rate)}/s · torque ${"%.0f".format(torqueRate)}/s. " else ""
        Text(
            text = now + "Reading torque less often makes the shift lights quicker and the torque needle slower.",
            color = Secondary,
            fontSize = 12.sp,
        )
        area?.let { Text("Car screen $it", color = Secondary, fontSize = 12.sp) }
    }
}

/**
 * Calls the exact peak oil temperatures up on the car screen, which otherwise only marks the
 * peaks with an amber hand. They stay for ten seconds; tapping again puts them away sooner.
 */
@Composable
private fun PeakTempPanel() {
    val drive by Drive.state.collectAsStateWithLifecycle()
    val until by Drive.peakTempsUntil.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(until) {
        val left = if (until == PeakCallout.HIDDEN) 0L else until - SystemClock.elapsedRealtime()
        showing = left > 0
        if (showing) {
            delay(left)
            showing = false
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("PEAK OIL TEMPERATURE")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledButton(if (showing) "Hide" else "Peak Temp") { Drive.togglePeakTemps() }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Engine ${drive.oilPeak?.let { "$it °C" } ?: "–"} · gearbox ${drive.gearPeak?.let { "$it °C" } ?: "–"}",
                    color = Ink,
                    fontSize = 15.sp,
                )
                Text(
                    text = if (showing) "Showing on the car screen" else "Shows on the car screen for 10 seconds",
                    color = Secondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * G-meter setup. The phone can lie any way round, so it has to be told where it is each time
 * it is put down: Calibrate finds "down" while parked, then "forward" from the first pull-away.
 */
@Composable
private fun GMeterPanel() {
    val drive by Drive.state.collectAsStateWithLifecycle()
    val ready = drive.stage == GTracker.Stage.Ready
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("G-METER")
        Text(
            text = when (drive.stage) {
                GTracker.Stage.Uncalibrated -> "Not calibrated. Wedge the phone in its place, park, then tap Calibrate."
                GTracker.Stage.Levelling -> "Keep the car still…"
                GTracker.Stage.AwaitingPullAway -> "Now pull away forwards in a straight line."
                GTracker.Stage.Ready ->
                    "Ready · ${"%+.2f".format(drive.lon)} g forward · ${"%+.2f".format(drive.lat)} g right"
            },
            color = Ink,
            fontSize = 15.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledButton("Calibrate") { Drive.calibrate() }
            OutlineButton("Reset peak G", Modifier.weight(1f)) { Drive.resetPeakG() }
        }
        if (ready) {
            Text("If braking shows as accelerating, the pull-away was read backwards:", color = Secondary, fontSize = 12.sp)
            OutlineButton("Swap front and back") { Drive.flip() }
        }
    }
}

@Composable
private fun AdapterPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val finder = remember { DeviceFinder(context.applicationContext) }
    val nearby by finder.nearby.collectAsStateWithLifecycle()
    val paired = remember { finder.paired() }

    DisposableEffect(finder) {
        finder.startScan()
        onDispose { finder.stopScan() }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("CHOOSE ADAPTER")
        if (!finder.bluetoothOn) {
            Text("Bluetooth is off. Turn it on, then reopen this screen.", color = Label, fontSize = 15.sp)
            return@Column
        }
        Text(
            "Plug the adapter into the car, switch it on, and turn the ignition on. " +
                "Adapters paired in the phone's Bluetooth settings are listed first; Bluetooth LE adapters appear as they are found.",
            color = Secondary,
            fontSize = 13.sp,
        )
        val all = paired + nearby.filter { n -> paired.none { it.device.address == n.device.address && it.lowEnergy } }
        if (all.isEmpty()) {
            Text("Searching…", color = Label, fontSize = 15.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(all, key = { it.device.address + it.lowEnergy }) { found ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .clickable(role = Role.Button) { ObdHub.connect(context, found.device, found.lowEnergy) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(found.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (found.lowEnergy) "Bluetooth LE" else "Paired · classic Bluetooth", color = Secondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ConsoleControls(log: List<ObdHub.LogLine>) {
    val unit by ObdHub.unit.collectAsStateWithLifecycle()
    val live by ObdHub.live.collectAsStateWithLifecycle()
    val rpm by ObdHub.rpm.collectAsStateWithLifecycle()
    val rate by ObdHub.rate.collectAsStateWithLifecycle()
    val torque by ObdHub.torque.collectAsStateWithLifecycle()
    val power by ObdHub.power.collectAsStateWithLifecycle()
    val gearTemp by ObdHub.gearTemp.collectAsStateWithLifecycle()
    val oilTemp by ObdHub.oilTemp.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.Console) }

    // which control unit the console talks to
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Segment("Engine · 7E0", unit == ObdHub.ENGINE, Modifier.weight(1f)) { ObdHub.selectUnit(ObdHub.ENGINE) }
        Segment("Gearbox · 7E1", unit == ObdHub.GEARBOX, Modifier.weight(1f)) { ObdHub.selectUnit(ObdHub.GEARBOX) }
    }

    // live engine speed, also shown on the Android Auto screen
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel("LIVE ENGINE DATA")
            Text(
                text = when {
                    !live -> "Off"
                    rpm == null -> "Waiting for the engine unit…"
                    else -> "$rpm rpm · ${"%.0f".format(rate)} readings/s"
                },
                color = Ink,
                fontSize = 15.sp,
            )
            if (live && rpm != null) {
                Text(
                    text = when {
                        torque != null && power != null -> "Torque $torque Nm · power $power HP"
                        torque != null -> "Torque $torque Nm"
                        else -> "Torque: read while the centre dial shows Power"
                    },
                    color = Secondary,
                    fontSize = 13.sp,
                )
                Text(
                    text = oilTemp?.let { "Engine oil $it °C" } ?: "Engine oil: no reading yet",
                    color = Secondary,
                    fontSize = 13.sp,
                )
                Text(
                    text = gearTemp?.let { "Gearbox oil $it °C" } ?: "Gearbox oil: no reading yet",
                    color = Secondary,
                    fontSize = 13.sp,
                )
            }
        }
        Switch(checked = live, onCheckedChange = { ObdHub.setLive(it) })
    }

    // console, the search for data IDs, or a drive recording
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Segment("Console", tab == Tab.Console, Modifier.weight(1f)) { tab = Tab.Console }
        Segment("Find IDs", tab == Tab.FindIds, Modifier.weight(1f)) { tab = Tab.FindIds }
        Segment("Record", tab == Tab.Record, Modifier.weight(1f)) { tab = Tab.Record }
    }

    if (tab == Tab.FindIds) {
        ScanPanel(unit)
        OutlineButton("Disconnect") { ObdHub.disconnect() }
        return
    }
    if (tab == Tab.Record) {
        RecordPanel()
        OutlineButton("Disconnect") { ObdHub.disconnect() }
        return
    }

    LogView(log, Modifier.height(LIST_HEIGHT))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val send = {
            ObdHub.send(input)
            input = ""
        }
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
            decorationBox = { field ->
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .border(1.dp, Edge, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (input.isEmpty()) {
                        Text("Command, e.g. 0105", color = Secondary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    }
                    field()
                }
            },
        )
        FilledButton("Send", onClick = send)
    }

    OutlineButton("Disconnect") { ObdHub.disconnect() }
}

private enum class Tab { Console, FindIds, Record }

/**
 * Records what the gearbox reports during a drive, to be shared as a file afterwards: the
 * way to find out which values follow the gear and the load without reading a phone on the
 * move.
 */
@Composable
private fun RecordPanel() {
    val record by ObdHub.record.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // keep the phone awake while it records
    val view = LocalView.current
    DisposableEffect(record.running) {
        view.keepScreenOn = record.running
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("DRIVE RECORDING")
        Text(
            text = when {
                record.running -> "Recording · ${record.rows} rows · ${clock(record.seconds)}" +
                    if (record.batched) "" else " · one value at a time"
                record.rows > 0 -> "Recorded ${record.rows} rows in ${clock(record.seconds)}."
                else -> "Not recording."
            },
            color = Ink,
            fontSize = 15.sp,
        )
        Text(
            "Logs engine speed, torque, the selector and the gear candidates from the gearbox several times " +
                "a second. It only reads values. Live data is off while it records, so the car screen shows no " +
                "engine speed. Start it parked, drive, stop it parked, then share the file.",
            color = Secondary,
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledButton(if (record.running) "Stop" else "Start recording", Modifier.weight(1f)) {
                if (record.running) ObdHub.stopRecording() else ObdHub.startRecording()
            }
            if (!record.running && record.rows > 0) {
                OutlineButton("Share file", Modifier.weight(1f)) { shareRecording(context) }
            }
        }
    }
}

private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Writes the recording to a file in the app's cache and offers it to the share sheet. */
private fun shareRecording(context: Context) {
    val folder = File(context.cacheDir, "recordings").apply { mkdirs() }
    val file = File(folder, "rs-monitor-recording.txt")
    file.writeText(ObdHub.recordingReport())
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        // the receiving app is allowed to read this one file
        clipData = ClipData.newRawUri("RS Monitor recording", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share recording"))
}

/** Tries a range of data IDs on the selected control unit and lists the ones that answer. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ScanPanel(unit: String) {
    val scan by ObdHub.scan.collectAsStateWithLifecycle()
    var from by remember { mutableStateOf("1000") }
    var to by remember { mutableStateOf("2FFF") }
    var extended by remember { mutableStateOf(false) }
    val unitName = if (unit == ObdHub.ENGINE) "engine" else "gearbox"
    val context = LocalContext.current

    // a scan can take many minutes; keep the phone awake while it runs
    val view = LocalView.current
    DisposableEffect(scan.running) {
        view.keepScreenOn = scan.running
        onDispose { view.keepScreenOn = false }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        HexField(from, "First ID", Modifier.weight(1f)) { from = it }
        Text("to", color = Secondary, fontSize = 14.sp)
        HexField(to, "Last ID", Modifier.weight(1f)) { to = it }
        FilledButton(if (scan.running) "Stop" else "Scan") {
            if (scan.running) {
                ObdHub.stopScan()
            } else {
                val first = from.toIntOrNull(16)
                val last = to.toIntOrNull(16)
                if (first != null && last != null) ObdHub.startScan(first, last, extended)
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Extended session", color = Ink, fontSize = 15.sp)
            Text("Sends 10 03 first. Some units show more IDs this way.", color = Secondary, fontSize = 12.sp)
        }
        Switch(checked = extended, onCheckedChange = { extended = it }, enabled = !scan.running)
    }

    val scannedUnit = if (scan.unit == ObdHub.ENGINE) "engine" else "gearbox"
    Text(
        text = when {
            scan.running ->
                "Scanning the $scannedUnit${if (scan.batched) ", three IDs at a time" else ""} · " +
                    "at ${"%04X".format(scan.current)} of ${"%04X".format(scan.to)} · ${scan.hits.size} answered"
            scan.tried > 0 ->
                "Tried ${scan.tried} IDs on the $scannedUnit from ${"%04X".format(scan.from)}. ${scan.hits.size} answered."
            else -> {
                val count = ((to.toIntOrNull(16) ?: 0) - (from.toIntOrNull(16) ?: 0)).coerceAtLeast(0) + 1
                "Asks the $unitName for every ID in the range and lists the ones that answer. " +
                    "It only reads values. $count IDs take about ${(count / 1500).coerceAtLeast(1)} to " +
                    "${(count / 500).coerceAtLeast(2)} min; keep the car parked."
            }
        },
        color = Secondary,
        fontSize = 13.sp,
    )

    if (!scan.running && scan.tried > 0) {
        OutlineButton("Share results (${scan.hits.size})") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, scan.report())
            }
            context.startActivity(Intent.createChooser(send, "Share scan results"))
        }
    }

    LazyColumn(
        modifier = Modifier.height(LIST_HEIGHT).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LogPanel),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(scan.hits, key = { it.id }) { hit ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("%04X".format(hit.id), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text(hexString(hit.data).ifEmpty { "(no data)" }, color = Label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
                val a = hit.data.firstOrNull()
                if (a != null && hit.data.size <= 2) {
                    // the two temperature formulas VW-group units most often use
                    Text(
                        "if a temperature: ${a - 40} °C (A−40) or ${"%.0f".format(a * 0.75 - 48)} °C (0.75·A−48)",
                        color = Secondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun HexField(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { typed -> onChange(typed.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(4)) },
        singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        modifier = modifier,
        decorationBox = { field ->
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .border(1.dp, Edge, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(hint, color = Secondary, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                }
                field()
            }
        },
    )
}

@Composable
private fun LogView(log: List<ObdHub.LogLine>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) state.scrollToItem(log.lastIndex)
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LogPanel),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(log) { line ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("› ${line.command}", color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text(
                    line.reply,
                    color = if (line.failed) Failed else Secondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.9.sp)
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Selected else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Ink else Label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun FilledButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = OnInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OutlineButton(text: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Edge, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
