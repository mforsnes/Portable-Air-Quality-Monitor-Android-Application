package com.example.airqualitymonitor.data.location

import android.util.Log
import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val isIndoors: Boolean,
    val timeToFix: Long
)

class GPSManager(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val _locationData = MutableStateFlow<LocationData?>(null)
    val locationData: StateFlow<LocationData?> = _locationData

    private var isRequestingUpdates = false
    private var updateRequestRetryCount = 0
    private var lastRequestTime: Long = 0
    private val MAX_RETRIES = 3
    private val INDOOR_TIME_THRESHOLD = 800L //2 seconds threshold

    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
        .setWaitForAccurateLocation(true)
        .setMinUpdateIntervalMillis(500)
        .setMaxUpdateDelayMillis(1000)
        .setMinUpdateDistanceMeters(0f)
        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            Log.d("GPSManager", "onLocationResult called")
            locationResult.lastLocation?.let { location ->
                val timeToFix = System.currentTimeMillis() - lastRequestTime
                Log.d("GPSManager", "New location received: lat=${location.latitude}, lon=${location.longitude}")
                Log.d("GPSManager", "Time to fix: $timeToFix ms")

                updateRequestRetryCount = 0
                _locationData.value = LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    isIndoors = timeToFix > INDOOR_TIME_THRESHOLD,
                    timeToFix = timeToFix
                )

                // Reset timer for next measurement
                lastRequestTime = System.currentTimeMillis()
            } ?: run {
                Log.d("GPSManager", "Location result was null")
                retryLocationUpdatesIfNeeded()
            }
        }

        override fun onLocationAvailability(locationAvailability: LocationAvailability) {
            Log.d("GPSManager", "Location availability changed: isLocationAvailable=${locationAvailability.isLocationAvailable}")
            if (!locationAvailability.isLocationAvailable) {
                retryLocationUpdatesIfNeeded()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun retryLocationUpdatesIfNeeded() {
        if (updateRequestRetryCount < MAX_RETRIES) {
            updateRequestRetryCount++
            Log.d("GPSManager", "Retrying location updates (attempt $updateRequestRetryCount)")
            stopLocationUpdates()
            startLocationUpdates()
        } else {
            Log.e("GPSManager", "Max retries reached for location updates")
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isRequestingUpdates) {
            Log.d("GPSManager", "Location updates already requested")
            return
        }

        Log.d("GPSManager", "Starting location updates")
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Log.d("GPSManager", "GPS enabled: $isGpsEnabled, Network location enabled: $isNetworkEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.e("GPSManager", "No location providers enabled")
                return
            }

            lastRequestTime = System.currentTimeMillis()

            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val initialTimeToFix = System.currentTimeMillis() - lastRequestTime
                        Log.d("GPSManager", "Last location: lat=${location.latitude}, lon=${location.longitude}")
                        _locationData.value = LocationData(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            isIndoors = initialTimeToFix > INDOOR_TIME_THRESHOLD,
                            timeToFix = initialTimeToFix
                        )
                    } else {
                        Log.d("GPSManager", "Last location is null")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("GPSManager", "Error getting last location", e)
                }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d("GPSManager", "Location updates successfully requested")
                isRequestingUpdates = true
                updateRequestRetryCount = 0
            }.addOnFailureListener { e ->
                Log.e("GPSManager", "Failed to request location updates", e)
                isRequestingUpdates = false
                retryLocationUpdatesIfNeeded()
            }

        } catch (e: Exception) {
            Log.e("GPSManager", "Exception when requesting location updates", e)
            e.printStackTrace()
            isRequestingUpdates = false
            retryLocationUpdatesIfNeeded()
        }
    }

    fun stopLocationUpdates() {
        Log.d("GPSManager", "Stopping location updates")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isRequestingUpdates = false
    }
}