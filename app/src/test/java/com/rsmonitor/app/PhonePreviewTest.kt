package com.rsmonitor.app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import com.rsmonitor.app.drive.Drive
import com.rsmonitor.app.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the phone app's three tabs to PNG files, next to the gauge previews
 * (app/build/outputs/gauge-previews), as they look with no adapter connected (Connection with one sample adapter paired). The README
 * shows these files by name from the `previews` branch: keep the names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-420dpi")
class PhonePreviewTest {
    private fun render(name: String, section: String) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SECTION, section)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        // let the screen compose and settle
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)) }
        val view = controller.get().window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/outputs/gauge-previews").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // something was drawn: text and panels give more than a couple of colours down the middle
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 11) seen += bitmap.getPixel(x, y)
        assertTrue("only ${seen.size} colours in $name", seen.size > 8)
        controller.pause().stop().destroy()
    }

    @Test
    fun settings() = render("phone-settings", "Settings")

    /** The whole of Settings, which on a phone is scrolled through. */
    @Test
    @Config(qualifiers = "w412dp-h2500dp-420dpi")
    fun settingsInFull() = render("phone-settings-full", "Settings")

    /** With Bluetooth on and one sample adapter paired, so the list of adapters shows. */
    @Test
    fun connection() {
        val app = RuntimeEnvironment.getApplication()
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val sample = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(sample).setName("OBDII")
        shadowOf(sample).setType(BluetoothDevice.DEVICE_TYPE_DUAL)
        shadowOf(adapter).setBondedDevices(setOf(sample))
        render("phone-connection", "Connection")
    }

    /** With a drive's worth of sample peaks, so the rows are not all dashes. */
    @Test
    fun peaks() {
        Drive.onOilTemperature(104)
        Drive.onGearboxTemperature(74)
        Drive.onEngineSpeed(8090)
        Drive.onPower(404, 351)
        Drive.onPower(342, 394)
        render("phone-peaks", "Peaks")
        Drive.resetAllPeaks()
    }
}
