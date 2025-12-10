package com.example.airqualitymonitor.data.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.airqualitymonitor.data.models.SensorReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.UUID
import java.nio.ByteOrder
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothDevice

class BLEManager(private val context: Context) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var isAutoReconnectEnabled = true
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _sensorData = MutableStateFlow<SensorReading?>(null)
    val sensorData: StateFlow<SensorReading?> = _sensorData

    companion object {
        private const val TAG = "BLEManager"
        private val SERVICE_UUID = UUID.fromString("8985ec22-ba8e-4009-8966-7c0d4f25460d")
        private val CHARACTERISTIC_UUID = UUID.fromString("2ce00ed4-b48a-4f0f-9dc9-34a71b75526b")
        private val DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEVICE_NAME = "picow"

        private fun ByteArray.toHexString() = joinToString(", ") { String.format("%02X", it) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
                    return
                }

                Log.d(TAG, "Device found: ${result.device.name ?: "null"} (${result.device.address})")
                Log.d(TAG, "Device UUID: ${result.scanRecord?.serviceUuids}")

                if (result.device.name == DEVICE_NAME ||
                    result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true) {
                    Log.d(TAG, "Found matching device: ${result.device.name}")
                    Log.d(TAG, "RSSI: ${result.rssi}")
                    Log.d(TAG, "Service UUIDs: ${result.scanRecord?.serviceUuids}")

                    stopScan()
                    result.device.connectGatt(context, false, gattCallback)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception during scan result processing", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _connectionState.value = true
                    bluetoothGatt = gatt
                    gatt?.requestMtu(517)
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    _connectionState.value = false
                    bluetoothGatt = null

                    if (isAutoReconnectEnabled) {
                        Log.d(TAG, "Auto-reconnect enabled, starting scan...")
                        Handler(Looper.getMainLooper()).postDelayed({
                            startScan()
                        }, 1000)
                    }
                }
            }
        }


        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to: $mtu, status: $status")

            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
                    return
                }
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception during service discovery", e)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")

                gatt?.services?.forEach { service ->
                    Log.d(TAG, "Found service: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        Log.d(TAG, "  Characteristic: ${characteristic.uuid}")
                        val charProps = characteristic.properties
                        Log.d(TAG, "  Properties: ${
                            buildString {
                                if ((charProps and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) append("NOTIFY ")
                                if ((charProps and BluetoothGattCharacteristic.PROPERTY_READ) != 0) append("READ ")
                                if ((charProps and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) append("WRITE ")
                            }
                        }")
                        characteristic.descriptors.forEach { descriptor ->
                            Log.d(TAG, "    Descriptor: ${descriptor.uuid}")
                        }
                    }
                }

                val service = gatt?.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Required service not found: $SERVICE_UUID")
                    return
                }

                val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.e(TAG, "Required characteristic not found: $CHARACTERISTIC_UUID")
                    return
                }

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
                    return
                }

                // Enable notifications
                if (gatt.setCharacteristicNotification(characteristic, true)) {
                    Log.d(TAG, "Notifications enabled at GATT level")

                    val descriptor = characteristic.getDescriptor(DESCRIPTOR_UUID)
                    if (descriptor != null) {
                        Log.d(TAG, "Found notification descriptor")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val result = gatt.writeDescriptor(descriptor)
                        Log.d(TAG, "Write descriptor result: $result")
                    } else {
                        Log.e(TAG, "Notification descriptor not found!")
                    }
                } else {
                    Log.e(TAG, "Failed to enable notifications!")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            Log.d(TAG, "onDescriptorWrite - status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful. Notifications should now be enabled.")
            } else {
                Log.e(TAG, "Descriptor write failed!")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged called")
            Log.d(TAG, "Characteristic UUID: ${characteristic.uuid}")
            Log.d(TAG, "Value size: ${value.size}")
            Log.d(TAG, "Raw value: ${value.joinToString(", ") { String.format("%02X", it) }}")

            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                handleCharacteristicData(value)
            } else {
                Log.w(TAG, "Received change for unexpected characteristic: ${characteristic.uuid}")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Legacy onCharacteristicChanged called")
            @Suppress("DEPRECATION")
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                handleCharacteristicData(characteristic.value)
            }
        }


        private fun handleCharacteristicData(value: ByteArray) {
            Log.d(TAG, "Handling characteristic data")
            Log.d(TAG, "Received data of length: ${value.size}")
            Log.d(TAG, "Raw data: ${value.joinToString(", ") { String.format("%02X", it) }}")

            if (value.size != 24) {
                Log.e(TAG, "Unexpected data length: ${value.size}, expected: 24")
                return
            }

            try {
                ByteBuffer.wrap(value).apply {
                    order(ByteOrder.LITTLE_ENDIAN)

                    val temperature = float.also {
                        Log.d(
                            TAG,
                            "Temperature bytes: ${
                                String.format(
                                    "%08X",
                                    java.lang.Float.floatToRawIntBits(it)
                                )
                            }"
                        )
                    }
                    val humidity = float.also {
                        Log.d(
                            TAG,
                            "Humidity bytes: ${
                                String.format(
                                    "%08X",
                                    java.lang.Float.floatToRawIntBits(it)
                                )
                            }"
                        )
                    }
                    val pressure = float.also {
                        Log.d(
                            TAG,
                            "Pressure bytes: ${
                                String.format(
                                    "%08X",
                                    java.lang.Float.floatToRawIntBits(it)
                                )
                            }"
                        )
                    }
                    val gasResistance = float.also {
                        Log.d(
                            TAG,
                            "Gas Resistance bytes: ${
                                String.format(
                                    "%08X",
                                    java.lang.Float.floatToRawIntBits(it)
                                )
                            }"
                        )
                    }
                    val vocPpm = float.also {
                        Log.d(
                            TAG,
                            "VOC bytes: ${
                                String.format(
                                    "%08X",
                                    java.lang.Float.floatToRawIntBits(it)
                                )
                            }"
                        )
                    }
                    val pm25 = float.also {
                        Log.d(
                            TAG,
                            "PM2.5 bytes: ${
                                String.format(
                                    "%08X",
                                    java.lang.Float.floatToRawIntBits(it)
                                )
                            }"
                        )
                    }

                    val reading = SensorReading(
                        temperature = temperature,
                        humidity = humidity,
                        pressure = pressure,
                        gasResistance = gasResistance,
                        vocPpm = vocPpm,
                        pm25 = pm25
                    )

                    Log.d(
                        TAG, """
                Successfully parsed sensor reading:
                Temperature: ${reading.temperature} °C
                Humidity: ${reading.humidity} %
                Pressure: ${reading.pressure} Pa
                Gas Resistance: ${reading.gasResistance} kΩ
                VOC: ${reading.vocPpm} PPM
                PM2.5: ${reading.pm25} µg/m³
            """.trimIndent()
                    )

                    _sensorData.value = reading
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing sensor data", e)
                Log.e(TAG, "Stack trace:", e)

                try {
                    val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 6) {
                        val floatBytes = ByteArray(4)
                        buffer.get(floatBytes)
                        Log.d(
                            TAG,
                            "Float $i raw bytes: ${
                                floatBytes.joinToString(", ") {
                                    String.format(
                                        "%02X",
                                        it
                                    )
                                }
                            }"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during detailed buffer analysis", e)
                }
            }
        }
    }


    internal fun startScan() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission")
            return
        }

        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                .setReportDelay(0L)
                .build()

            Log.d(TAG, "Starting BLE scan")
            bluetoothAdapter?.bluetoothLeScanner?.startScan(
                listOf(filter),
                settings,
                scanCallback
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        isAutoReconnectEnabled = enabled
        if (enabled && _connectionState.value == false) {
            startScan()
        } else if (!enabled) {
            stopScan()
        }
    }

    fun stopScan() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission")
            return
        }

        try {
            Log.d(TAG, "Stopping BLE scan")
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
    }

    fun disconnect() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }

        Log.d(TAG, "Disconnecting from device")
        bluetoothGatt?.disconnect()
        bluetoothGatt = null
    }
}