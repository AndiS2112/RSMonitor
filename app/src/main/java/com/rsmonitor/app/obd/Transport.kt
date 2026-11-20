package com.rsmonitor.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** The link to the adapter has gone; the session must reconnect. */
class LinkLost(message: String) : IOException(message)

/** A raw byte pipe to the OBD adapter. One implementation per kind of Bluetooth. */
interface Transport {
    /** Opens the link. Throws [IOException] if it cannot. */
    suspend fun open()

    suspend fun write(bytes: ByteArray)

    /** Bytes from the adapter, in arrival order. Closed when the link drops. */
    val incoming: ReceiveChannel<ByteArray>

    fun close()
}

/** Classic Bluetooth serial port: adapters that pair in the phone's Bluetooth settings. */
@SuppressLint("MissingPermission")
class ClassicTransport(private val device: BluetoothDevice) : Transport {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = channel
    private var socket: BluetoothSocket? = null

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val s = device.createRfcommSocketToServiceRecord(SERIAL_PORT)
            try {
                s.connect()
            } catch (e: IOException) {
                runCatching { s.close() }
                throw e
            }
            socket = s
            thread(name = "obd-classic-read", isDaemon = true) {
                val buf = ByteArray(1024)
                try {
                    val input = s.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) channel.trySend(buf.copyOf(n))
                    }
                } catch (_: IOException) {
                    // socket closed or link dropped
                } finally {
                    channel.close()
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw LinkLost("Adapter disconnected")
            try {
                s.outputStream.write(bytes)
                s.outputStream.flush()
            } catch (e: IOException) {
                throw LinkLost("Adapter disconnected")
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** Bluetooth Low Energy ("Bluetooth 4.0") adapters, which expose a serial-like GATT service. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : Transport {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = channel
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Unit>()
    private val writeDone = Channel<Int>(Channel.CONFLATED)

    private fun fail(reason: String) {
        ready.completeExceptionally(IOException(reason))
        channel.close()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else {
                fail("Bluetooth LE link closed (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not read the adapter's services (status $status)")
                return
            }
            val pair = findSerialPair(g)
            if (pair == null) {
                fail("This adapter has no serial service the app recognises")
                return
            }
            val (notify, write) = pair
            writeChar = write
            // Ask for the short connection interval: each request and its answer cross the
            // link several times, and at the default interval that alone takes most of a
            // tenth of a second.
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CLIENT_CONFIG)
            if (cccd == null) {
                ready.complete(Unit)
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit) else fail("Could not turn on notifications (status $status)")
        }

        // Android 12 and older deliver the value on the characteristic object.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { channel.trySend(it.copyOf()) }
        }

        // Android 13 and newer pass the value directly.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            channel.trySend(value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.trySend(status)
        }
    }

    override suspend fun open() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(15_000) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw IOException("Bluetooth LE connection timed out")
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override suspend fun write(bytes: ByteArray) {
        val g = gatt ?: throw LinkLost("Adapter disconnected")
        val c = writeChar ?: throw LinkLost("Adapter disconnected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + CHUNK, bytes.size))
            while (writeDone.tryReceive().isSuccess) {
                // clear a stale result
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = chunk
                g.writeCharacteristic(c)
            }
            if (!started) throw IOException("Bluetooth LE write refused")
            withTimeoutOrNull(2_000) { writeDone.receive() } ?: throw IOException("Bluetooth LE write timed out")
            offset += chunk.size
        }
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        channel.close()
    }

    private fun findSerialPair(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        fun BluetoothGattCharacteristic.canNotify() =
            properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

        fun BluetoothGattCharacteristic.canWrite() =
            properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        // Layouts used by common ELM327 Bluetooth LE adapters.
        for ((serviceId, notifyId, writeId) in KNOWN_LAYOUTS) {
            val service = g.services.firstOrNull { it.uuid.toString().startsWith(serviceId) } ?: continue
            val notify = service.characteristics.firstOrNull { it.uuid.toString().startsWith(notifyId) && it.canNotify() }
            val write = service.characteristics.firstOrNull { it.uuid.toString().startsWith(writeId) && it.canWrite() }
            if (notify != null && write != null) return notify to write
        }
        // Otherwise: the first non-system service that can both notify and be written to.
        for (service in g.services) {
            val id = service.uuid.toString()
            if (id.startsWith("00001800") || id.startsWith("00001801")) continue
            val notify = service.characteristics.firstOrNull { it.canNotify() } ?: continue
            val write = service.characteristics.firstOrNull { it.canWrite() } ?: continue
            return notify to write
        }
        return null
    }

    private companion object {
        const val CHUNK = 20
        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val KNOWN_LAYOUTS = listOf(
            Triple("0000fff0", "0000fff1", "0000fff2"),
            Triple("0000ffe0", "0000ffe1", "0000ffe1"),
            Triple("000018f0", "00002af0", "00002af1"),
            Triple("e7810a71", "bef8d6c9", "bef8d6c9"),
        )
    }
}
