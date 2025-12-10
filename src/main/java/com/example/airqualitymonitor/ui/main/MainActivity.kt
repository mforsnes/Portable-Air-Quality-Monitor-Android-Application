package com.example.airqualitymonitor

import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.airqualitymonitor.data.remote.BLEManager
import com.example.airqualitymonitor.data.location.GPSManager
import com.example.airqualitymonitor.data.cloud.CloudStorageManager
import com.example.airqualitymonitor.databinding.ActivityMainBinding
import com.example.airqualitymonitor.data.models.SensorReading
import com.example.airqualitymonitor.ui.map.MapFragment
import com.example.airqualitymonitor.ui.map.ForecastFragment
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import org.osmdroid.config.Configuration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.app.Service
import android.os.IBinder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bleManager: BLEManager
    private lateinit var gpsManager: GPSManager
    private lateinit var cloudManager: CloudStorageManager

    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val channelId = "air_quality_alerts"

    private var isUnhealthyPM25Notified = false
    private var isUnhealthyVOCNotified = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private val mapPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, isGranted) ->
            Log.d(
                "MainActivity",
                "Permission $permission: ${if (isGranted) "Granted" else "Denied"}"
            )
        }

        if (permissions.all { it.value }) {
            Log.d("MainActivity", "All permissions granted")
            setupBLE()
            gpsManager.startLocationUpdates()
        } else {
            val missingPermissions = permissions.filter { !it.value }
                .keys.joinToString(", ")
            Log.d("MainActivity", "Missing permissions: $missingPermissions")
            Toast.makeText(
                this,
                "Required permissions not granted: $missingPermissions",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupManagers()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Air Quality Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for unhealthy air quality levels"
            }
            notificationManager.createNotificationChannel(channel)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        Intent(this, AirQualityService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        if (hasPermissions()) {
            setupBLE()
            gpsManager.startLocationUpdates()
            Log.d("MainActivity", "Starting services - permissions already granted")
        } else {
            Log.d("MainActivity", "Requesting permissions")
            requestPermissions()
        }

        setupUI()
        setupNavigation()
        observeData()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MapFragment())
                .commit()
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.navigationRealtime.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MapFragment())
                .commit()
        }

        binding.bottomNav.navigationForecast.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ForecastFragment())
                .commit()
        }
    }

    private fun setupBLE() {
        lifecycleScope.launch {
            bleManager.connectionState.collect { isConnected ->
                binding.statusText.text = if (isConnected) "Connected" else "Scanning..."
                if (!isConnected) {
                    clearReadings()
                }
            }
        }
    }

    private fun clearReadings() {
        binding.apply {
            pm25Text.text = "PM2.5 AQI: --"
            vocText.text = "VOC AQI: --"
            totalAqiText.text = "Total AQI: --"
            temperatureText.text = "Temperature: -- °F"
            humidityText.text = "Humidity: --%"
            humidityComfortText.text = "Humidity Comfort Level: --"

            rawSensorData.text = "Raw Sensor Data: --"
            locationData.text = "Location Data: --"
            timestampData.text = "Timestamp: --"
        }
    }

    private fun setupManagers() {
        bleManager = BLEManager(this)
        gpsManager = GPSManager(this)
        cloudManager = CloudStorageManager()
    }

    private fun setupUI() {
        if (hasPermissions()) {
            bleManager.setAutoReconnect(true)
        }
    }


    private fun observeData() {
        lifecycleScope.launch {
            gpsManager.locationData.collect { locationData ->
                Log.d("MainActivity", "GPS Data received: $locationData")
                if (locationData != null) {
                    binding.locationData.text = """
                    GPS Data:
                    Latitude: ${locationData.latitude}
                    Longitude: ${locationData.longitude}
                    GPS Accuracy: ${locationData.accuracy} m
                    Time to Fix: ${locationData.timeToFix} ms
                    Location Type: ${if (locationData.isIndoors) "Indoor" else "Outdoor"}
                    Last Updated: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}
                """.trimIndent()
                    Log.d("MainActivity", "UI updated with GPS data")
                }
            }
        }

        lifecycleScope.launch {
            bleManager.sensorData.collect { sensorData ->
                if (sensorData != null) {
                    val locationData = gpsManager.locationData.value

                    if (locationData != null) {
                        val completeReading = sensorData.copy(
                            latitude = locationData.latitude,
                            longitude = locationData.longitude,
                            gpsPing = locationData.accuracy,
                            isIndoors = locationData.isIndoors
                        )

                        updateUI(completeReading)

                        Log.d("MainActivity", "Uploading new sensor reading to Firebase")
                        cloudManager.uploadData(completeReading)
                    }
                }
            }
        }
    }

    private fun updateUI(reading: SensorReading) {
        binding.apply {
            pm25Text.text = "PM2.5 AQI: ${reading.calculatePM25AQI()}"
            vocText.text = "VOC AQI: ${reading.calculateVOCIndex()}"
            totalAqiText.text = "Total AQI: ${reading.calculateWeightedAQI()}"

            val tempF = (reading.temperature * 9/5) + 32
            temperatureText.text = "Temperature: ${String.format("%.1f", tempF)}°F"

            // Humidity metrics
            humidityText.text = "Humidity: ${reading.humidity.toInt()}%"
            val comfortText = when(reading.calculateHumidityComfort()) {
                0 -> "Comfortable"
                1 -> "Too Dry"
                2 -> "Too Humid"
                else -> "Unknown"
            }
            humidityComfortText.text = "Humidity Comfort Level: $comfortText"

            rawSensorData.text = """
            Temperature: ${reading.temperature} °C
            Humidity: ${reading.humidity} %
            Pressure: ${reading.pressure} Pa
            Gas Resistance: ${reading.gasResistance} kΩ
            VOC: ${reading.vocPpm} PPM
            PM2.5: ${reading.pm25} µg/m³
        """.trimIndent()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            timestampData.text = "Timestamp: ${dateFormat.format(Date(reading.timestamp))}"
        }

        checkAirQualityAlerts(reading)
    }

    private fun checkAirQualityAlerts(reading: SensorReading) {
        val pm25AQI = reading.calculatePM25AQI()
        val vocIndex = reading.calculateVOCIndex()

        if (pm25AQI > 101 && !isUnhealthyPM25Notified) {
            showAQINotification("PM2.5", 1)
            isUnhealthyPM25Notified = true
        } else if (pm25AQI <= 101 && isUnhealthyPM25Notified) {
            notificationManager.cancel(1)
            isUnhealthyPM25Notified = false
        }

        // VOC Notification Management
        if (vocIndex > 101 && !isUnhealthyVOCNotified) {
            showAQINotification("VOC", 2)
            isUnhealthyVOCNotified = true
        } else if (vocIndex <= 101 && isUnhealthyVOCNotified) {
            notificationManager.cancel(2)
            isUnhealthyVOCNotified = false
        }
    }

    private fun showAQINotification(type: String, notificationId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Air Quality Alert")
            .setContentText("Unhealthy $type levels detected")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Air Quality Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for unhealthy air quality levels"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showAlert(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionRequest.launch(requiredPermissions)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            Log.d("MainActivity", "onResume: Starting location updates")
            gpsManager.stopLocationUpdates()
            gpsManager.startLocationUpdates()
        } else {
            Log.d("MainActivity", "onResume: Requesting permissions")
            requestPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        gpsManager.stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
        stopService(Intent(this, AirQualityService::class.java))
    }

    inner class AirQualityService : Service() {
        private val NOTIFICATION_ID = 1
        private val SERVICE_CHANNEL_ID = "air_quality_service"

        override fun onCreate() {
            super.onCreate()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serviceChannel = NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "Air Quality Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background service for air quality monitoring"
                }
                notificationManager.createNotificationChannel(serviceChannel)
            }
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val notification = NotificationCompat.Builder(this@MainActivity, SERVICE_CHANNEL_ID)
                .setContentTitle("Air Quality Monitor")
                .setContentText("Monitoring air quality in background")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            return START_STICKY
        }

        override fun onBind(intent: Intent?): IBinder? = null
    }
}