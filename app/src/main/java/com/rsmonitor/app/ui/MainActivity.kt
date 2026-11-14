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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rsmonitor.app.car.CarScreen
import com.rsmonitor.app.car.Centre
import com.rsmonitor.app.car.ShiftLights
import com.rsmonitor.app.drive.Drive
import com.rsmonitor.app.drive.GTracker
import com.rsmonitor.app.drive.PeakCallout
import com.rsmonitor.app.obd.DeviceFinder
import com.rsmonitor.app.obd.ObdHub
import com.rsmonitor.app.obd.hexString
import java.io.File
import kotlin.math.max
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
private val Amber = Color(0xFFF5A623)
private val Rule = Color(0xFF2E2E32)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CarScreen.load(this)
        setContent { App() }
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

/** The three sections on the bottom bar. */
private enum class Section(val title: String, val glyph: Glyph) {
    Settings("Settings", Glyph.Settings),
    Connection("Connection", Glyph.Connection),
    Peaks("Peaks", Glyph.Peaks),
}

/** The tools under Settings; each opens as a screen of its own. */
private enum class Tool(val title: String, val about: String) {
    Console("Console", "Send a command to the engine or gearbox"),
    FindIds("Find IDs", "Scan a control unit for values it reports"),
    Recorder("Recorder", "Log gearbox values during a drive"),
}

/**
 * The phone app: Settings, Connection and Peaks on a bottom bar. It opens on Connection
 * until an adapter is connected, since nothing else works before that.
 */
@Composable
private fun App() {
    var section by rememberSaveable {
        mutableStateOf(if (ObdHub.link.value == ObdHub.Link.Ready) Section.Settings else Section.Connection)
    }
    var tool by rememberSaveable { mutableStateOf<Tool?>(null) }
    BackHandler(enabled = tool != null) { tool = null }

    // every screen starts at its top
    val scroll = rememberScrollState()
    LaunchedEffect(section, tool) { scroll.scrollTo(0) }

    Column(Modifier.fillMaxSize().background(Screen).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            // Each screen scrolls as a whole: the panels can be taller than a phone, more so
            // with the keyboard up. The lists inside therefore have a height of their own.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val open = tool
            when {
                section == Section.Settings && open != null -> ToolScreen(open) { tool = null }
                section == Section.Settings -> SettingsScreen { tool = it }
                section == Section.Connection -> ConnectionScreen()
                else -> PeaksScreen()
            }
        }
        BottomBar(section) { section = it }
    }
}

@Composable
private fun BottomBar(current: Section, onSelect: (Section) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
        for (section in Section.values()) {
            val on = section == current
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Tab) { onSelect(section) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier
                        .size(width = 60.dp, height = 30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (on) Selected else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(section.glyph, if (on) Ink else Secondary)
                }
                Text(
                    section.title,
                    color = if (on) Ink else Secondary,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsScreen(onOpen: (Tool) -> Unit) {
    Title("Settings")
    CarScreenPanel()
    ShiftLightsPanel()
    GMeterPanel()
    PanelColumn("TOOLS", gap = 2.dp) {
        Tool.values().forEachIndexed { i, tool ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onOpen(tool) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tool.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(tool.about, color = Secondary, fontSize = 13.sp)
                }
                GlyphIcon(Glyph.Next, Secondary)
            }
        }
    }
}

/**
 * Which page the car screen's centre dial shows, and how the readings on the power page are
 * shared between engine speed and torque.
 */
@Composable
private fun CarScreenPanel() {
    val centre by CarScreen.centre.collectAsStateWithLifecycle()
    val torqueEvery by CarScreen.torqueEvery.collectAsStateWithLifecycle()
    val rate by ObdHub.rate.collectAsStateWithLifecycle()
    val torqueRate by ObdHub.torqueRate.collectAsStateWithLifecycle()
    PanelColumn("CAR SCREEN", gap = 8.dp) {
        Text("Centre dial", color = Ink, fontSize = 15.sp)
        SegmentRow {
            for ((page, name) in listOf(Centre.GMeter to "G-meter", Centre.Power to "Power")) {
                Segment(name, page == centre, Modifier.weight(1f)) { CarScreen.show(page) }
            }
        }
        Text("On the Power page, read torque with every", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        SegmentRow {
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
            text = now + "Less often makes the shift lights quicker and the torque needle slower.",
            color = Secondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * The engine speed at which each shift light comes on, and from which all five flash. A tap
 * moves one by 100 rpm and the car screen follows at once.
 */
@Composable
private fun ShiftLightsPanel() {
    val steps by CarScreen.shiftSteps.collectAsStateWithLifecycle()
    val names = listOf("Green", "Yellow 1", "Yellow 2", "Yellow 3", "Red", "All flash")
    val colours = listOf(ShiftLights.GREEN, ShiftLights.YELLOW, ShiftLights.YELLOW, ShiftLights.YELLOW, ShiftLights.RED, ShiftLights.RED)
    PanelColumn("SHIFT LIGHTS", gap = 4.dp) {
        for (i in steps.indices) {
            val name = names.getOrElse(i) { "Light ${i + 1}" }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Swatch(Color(colours.getOrElse(i) { ShiftLights.RED }), bars = if (i == steps.lastIndex) 5 else 1)
                Text(name, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                StepButton("−", "$name: 100 rpm lower") { CarScreen.moveShiftStep(i, -ShiftLights.STEP) }
                Text(
                    "%,d".format(steps[i]),
                    color = Ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(62.dp),
                )
                StepButton("+", "$name: 100 rpm higher") { CarScreen.moveShiftStep(i, ShiftLights.STEP) }
            }
        }
        OutlineButton("Back to the standard steps", Modifier.fillMaxWidth().padding(top = 6.dp)) { CarScreen.standardShiftSteps() }
        Text(
            "Each tap is 100 rpm and takes effect at once on the car screen. A light cannot be set below the one before it.",
            color = Secondary,
            fontSize = 12.sp,
        )
    }
}

/** One slanted bar in a shift light's colour, or five narrow ones for the flash. */
@Composable
private fun Swatch(colour: Color, bars: Int) {
    Canvas(Modifier.size(width = 44.dp, height = 12.dp)) {
        val lean = size.height * 0.445f
        val gap = 2.dp.toPx()
        val width = if (bars == 1) 26.dp.toPx() else (size.width - lean - gap * (bars - 1)) / bars
        var left = (size.width - (bars * width + (bars - 1) * gap)) / 2f
        repeat(bars) {
            val bar = Path().apply {
                moveTo(left + lean / 2f, 0f)
                lineTo(left + width + lean / 2f, 0f)
                lineTo(left + width - lean / 2f, size.height)
                lineTo(left - lean / 2f, size.height)
                close()
            }
            drawPath(bar, colour)
            left += width + gap
        }
    }
}

@Composable
private fun StepButton(sign: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Edge, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(sign, color = Ink, fontSize = 22.sp)
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
    PanelColumn("G-METER") {
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledButton("Calibrate", Modifier.weight(1f)) { Drive.calibrate() }
            if (ready) OutlineButton("Swap front / back", Modifier.weight(1f)) { Drive.flip() }
        }
        Text(
            "Calibrate while parked, then pull away. If braking shows as accelerating, swap front and back.",
            color = Secondary,
            fontSize = 12.sp,
        )
    }
}

// ------------------------------------------------------------------ Connection

@Composable
private fun ConnectionScreen() {
    val context = LocalContext.current
    val link by ObdHub.link.collectAsStateWithLifecycle()
    val adapter by ObdHub.adapter.collectAsStateWithLifecycle()
    val log by ObdHub.log.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasBluetoothPermission(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBluetoothPermission(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Title("Connection")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (link == ObdHub.Link.Ready) Ink else Edge))
            Text(
                text = when (link) {
                    ObdHub.Link.Disconnected -> "Not connected"
                    ObdHub.Link.Connecting -> "Connecting…"
                    ObdHub.Link.Ready -> "Connected"
                },
                color = Secondary,
                fontSize = 15.sp,
            )
        }
    }

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

        else -> {
            PanelColumn("ADAPTER") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(adapter.ifEmpty { "Adapter" }, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    OutlineButton("Disconnect", Modifier.width(132.dp)) { ObdHub.disconnect() }
                }
            }
            LiveDataPanel()
            CarScreenAreaPanel()
        }
    }
}

/** Live data on or off, and what the car is reporting. The same readings go to the car screen. */
@Composable
private fun LiveDataPanel() {
    val live by ObdHub.live.collectAsStateWithLifecycle()
    val rpm by ObdHub.rpm.collectAsStateWithLifecycle()
    val rate by ObdHub.rate.collectAsStateWithLifecycle()
    val torque by ObdHub.torque.collectAsStateWithLifecycle()
    val power by ObdHub.power.collectAsStateWithLifecycle()
    val gear by ObdHub.gear.collectAsStateWithLifecycle()
    val gearTemp by ObdHub.gearTemp.collectAsStateWithLifecycle()
    val oilTemp by ObdHub.oilTemp.collectAsStateWithLifecycle()
    PanelColumn("LIVE DATA", gap = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Read the car and show it on the car screen", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = live, onCheckedChange = { ObdHub.setLive(it) })
        }
        if (live) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
            Reading("Engine speed", rpm?.let { "%,d".format(it) }, "rpm")
            Reading("Gear", gear?.let { g -> g.mode.toString() + (g.number?.let { " $it" } ?: "") }, "")
            Reading("Engine oil", oilTemp?.toString(), "°C")
            Reading("Gearbox oil", gearTemp?.toString(), "°C")
            if (torque != null) Reading("Torque", torque.toString(), "Nm")
            if (power != null) Reading("Power", power.toString(), "hp")
            Text(
                text = if (rpm == null) "Waiting for the car…" else "${"%.0f".format(rate)} readings a second",
                color = Secondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Reading(name: String, value: String?, unit: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value ?: "–", color = if (value != null) Ink else Secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (unit.isNotEmpty()) Text(" $unit", color = Secondary, fontSize = 13.sp)
    }
}

/** The screen area Android Auto reported, once the car screen has been up: useful when the layout looks wrong there. */
@Composable
private fun CarScreenAreaPanel() {
    val area by CarScreen.areaNote.collectAsStateWithLifecycle()
    val note = area ?: return
    PanelColumn("CAR SCREEN", gap = 6.dp) {
        Text(note, color = Secondary, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ Peaks

/**
 * The highest readings since the last reset. The two buttons call the exact peak oil
 * temperatures up on the car screen for ten seconds, and clear every peak.
 */
@Composable
private fun PeaksScreen() {
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
    val g = { value: Float -> if (value > 0f) "%.2f".format(value) else null }

    Title("Peaks")
    PanelColumn("OIL TEMPERATURE", gap = 2.dp) {
        Peak("Engine oil", drive.oilPeak?.toString(), "°C")
        Peak("Gearbox oil", drive.gearPeak?.toString(), "°C")
    }
    PanelColumn("ENGINE", gap = 2.dp) {
        Peak("Power", drive.powerPeak?.toString(), "hp")
        Peak("Torque", drive.torquePeak?.toString(), "Nm")
        Peak("Engine speed", drive.rpmPeak?.let { "%,d".format(it) }, "rpm")
    }
    PanelColumn("G-FORCE", gap = 2.dp) {
        Peak("Acceleration", g(drive.peakAccel), "g")
        Peak("Braking", g(drive.peakBrake), "g")
        Peak(
            "Lateral",
            g(max(drive.peakLeft, drive.peakRight)),
            "g",
            sub = "left ${g(drive.peakLeft) ?: "–"} · right ${g(drive.peakRight) ?: "–"}",
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlineButton(if (showing) "Hide temps on car screen" else "Show temps on car screen", Modifier.weight(1f)) {
            Drive.togglePeakTemps()
        }
        OutlineButton("Reset", Modifier.width(96.dp)) { Drive.resetAllPeaks() }
    }
    Text(
        "Power and torque are recorded while the centre dial shows Power. " +
            "Peaks also clear by themselves once the car has been off for 30 seconds.",
        color = Secondary,
        fontSize = 12.sp,
    )
}

@Composable
private fun Peak(name: String, value: String?, unit: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Label, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Secondary, fontSize = 12.sp)
        }
        Text(value ?: "–", color = if (value != null) Amber else Secondary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(" $unit", color = Secondary, fontSize = 13.sp)
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

// ------------------------------------------------------------------ Tools, opened from Settings

@Composable
private fun ColumnScope.ToolScreen(tool: Tool, onBack: () -> Unit) {
    val link by ObdHub.link.collectAsStateWithLifecycle()
    val unit by ObdHub.unit.collectAsStateWithLifecycle()
    val log by ObdHub.log.collectAsStateWithLifecycle()

    Column {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .heightIn(min = 44.dp)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            GlyphIcon(Glyph.Back, Label)
            Text("Settings", color = Label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Title(tool.title)
    }

    if (link != ObdHub.Link.Ready) {
        Text("Connect to the adapter first, on the Connection tab.", color = Label, fontSize = 15.sp)
        return
    }

    if (tool != Tool.Recorder) {
        // which control unit the console and the scan talk to
        SegmentRow(Panel) {
            Segment("Engine · 7E0", unit == ObdHub.ENGINE, Modifier.weight(1f)) { ObdHub.selectUnit(ObdHub.ENGINE) }
            Segment("Gearbox · 7E1", unit == ObdHub.GEARBOX, Modifier.weight(1f)) { ObdHub.selectUnit(ObdHub.GEARBOX) }
        }
    }
    when (tool) {
        Tool.Console -> ConsolePanel(log)
        Tool.FindIds -> ScanPanel(unit)
        Tool.Recorder -> RecordPanel()
    }
}

@Composable
private fun ConsolePanel(log: List<ObdHub.LogLine>) {
    var input by remember { mutableStateOf("") }
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
}


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
private fun ColumnScope.ScanPanel(unit: String) {
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
private fun Title(text: String) {
    Text(text, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Medium)
}

/** A panel with its label on top. */
@Composable
private fun PanelColumn(label: String, gap: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        SectionLabel(label)
        content()
    }
}

/** The well that a row of [Segment]s sits in. */
@Composable
private fun SegmentRow(well: Color = Screen, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(well).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

private enum class Glyph { Settings, Connection, Peaks, Back, Next }

/** The app's few icons, drawn as strokes on a 24-unit grid. */
@Composable
private fun GlyphIcon(kind: Glyph, colour: Color) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val pen = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(colour, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
        fun ring(x: Float, y: Float, r: Float) = drawCircle(colour, r * u, Offset(x * u, y * u), style = pen)
        fun arc(r: Float, from: Float) =
            drawArc(colour, from, 90f, false, topLeft = Offset((12f - r) * u, (12f - r) * u), size = Size(2f * r * u, 2f * r * u), style = pen)
        fun stroke(closed: Boolean, vararg points: Float) {
            val path = Path()
            path.moveTo(points[0] * u, points[1] * u)
            for (i in 2 until points.size step 2) path.lineTo(points[i] * u, points[i + 1] * u)
            if (closed) path.close()
            drawPath(path, colour, style = pen)
        }
        when (kind) {
            // three sliders
            Glyph.Settings -> {
                line(4f, 7f, 14f, 7f); line(18f, 7f, 20f, 7f); ring(16f, 7f, 2f)
                line(4f, 12f, 7f, 12f); line(11f, 12f, 20f, 12f); ring(9f, 12f, 2f)
                line(4f, 17f, 15f, 17f); line(19f, 17f, 20f, 17f); ring(17f, 17f, 2f)
            }
            // a point sending in both directions
            Glyph.Connection -> {
                drawCircle(colour, 1.6f * u, Offset(12f * u, 12f * u))
                arc(5f, 135f); arc(5f, -45f)
                arc(9f, 135f); arc(9f, -45f)
            }
            // a summit
            Glyph.Peaks -> stroke(true, 3f, 19f, 9.2f, 8f, 12.6f, 13.6f, 15f, 10f, 21f, 19f)
            Glyph.Back -> stroke(false, 15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.Next -> stroke(false, 9f, 5f, 16f, 12f, 9f, 19f)
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
