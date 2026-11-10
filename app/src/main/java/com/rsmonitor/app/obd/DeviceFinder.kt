package com.rsmonitor.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lists adapters the phone can reach: ones already paired, and Bluetooth LE ones nearby. */
@SuppressLint("MissingPermission")
class DeviceFinder(context: Context) {
    data class Found(val device: BluetoothDevice, val name: String, val lowEnergy: Boolean)

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val _nearby = MutableStateFlow<List<Found>>(emptyList())
    /** Named Bluetooth LE devices seen since [startScan]. */
    val nearby: StateFlow<List<Found>> = _nearby

    /** Devices paired in the phone's Bluetooth settings. */
    fun paired(): List<Found> = runCatching {
        adapter?.bondedDevices.orEmpty().map {
            Found(it, it.name ?: it.address, lowEnergy = it.type == BluetoothDevice.DEVICE_TYPE_LE)
        }
    }.getOrDefault(emptyList())

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (_nearby.value.none { it.device.address == result.device.address }) {
                _nearby.value = _nearby.value + Found(result.device, name, lowEnergy = true)
            }
        }
    }

    fun startScan() {
        _nearby.value = emptyList()
        runCatching { adapter?.bluetoothLeScanner?.startScan(callback) }
    }

    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
    }
}
