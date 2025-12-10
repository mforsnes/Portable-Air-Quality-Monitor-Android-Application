package com.example.airqualitymonitor.data.cloud

import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.example.airqualitymonitor.data.models.SensorReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

data class AirQualityRecord(
    val date: String,
    val time: String,
    val pm25AQI: Int,
    val vocIndex: Int,
    val weightedAQI: Int,
    val latitude: Double,
    val longitude: Double,
    val isIndoors: Boolean
)

class CloudStorageManager {
    private val database = Firebase.database
    private val dataRef = database.getReference("air_quality_data")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    suspend fun uploadData(sensorReading: SensorReading) {
        withContext(Dispatchers.IO) {
            try {
                val date = dateFormat.format(Date(sensorReading.timestamp))
                val time = timeFormat.format(Date(sensorReading.timestamp))
                val timeBasedKey = "${sensorReading.timestamp}_${System.nanoTime()}"

                val record = AirQualityRecord(
                    date = date,
                    time = time,
                    pm25AQI = sensorReading.calculatePM25AQI(),
                    vocIndex = sensorReading.calculateVOCIndex(),
                    weightedAQI = sensorReading.calculateWeightedAQI(),
                    latitude = sensorReading.latitude ?: 0.0,
                    longitude = sensorReading.longitude ?: 0.0,
                    isIndoors = sensorReading.isIndoors
                )

                dataRef.child(date)
                    .child(timeBasedKey)
                    .setValue(record)
                    .await()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}