package com.example.airqualitymonitor.data.models

import java.text.SimpleDateFormat
import java.util.Locale

data class SensorReading(
    val temperature: Float = 0f,
    val humidity: Float = 0f,
    val pressure: Float = 0f,
    val gasResistance: Float = 0f,
    val vocPpm: Float = 0f,
    val pm25: Float = 0f,

    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsPing: Float = 0f,
    val isIndoors: Boolean = false,

    val timestamp: Long = System.currentTimeMillis()
) {
    fun calculatePM25AQI(): Int {
        return when {
            pm25 <= 12.0f -> linearScale(pm25, 0f, 12f, 0, 50)
            pm25 <= 35.4f -> linearScale(pm25, 12.1f, 35.4f, 51, 100)
            pm25 <= 55.4f -> linearScale(pm25, 35.5f, 55.4f, 101, 150)
            pm25 <= 150.4f -> linearScale(pm25, 55.5f, 150.4f, 151, 200)
            pm25 <= 250.4f -> linearScale(pm25, 150.5f, 250.4f, 201, 300)
            else -> linearScale(pm25, 250.5f, 500.4f, 301, 500)
        }
    }

    fun calculateVOCIndex(): Int {
        return when {
            vocPpm <= 0.5f -> linearScale(vocPpm, 0f, 0.5f, 0, 25)  // Good
            vocPpm <= 1.0f -> linearScale(vocPpm, 0.5f, 1.0f, 26, 50)  // Good
            vocPpm <= 6.0f -> linearScale(vocPpm, 1.0f, 6.0f, 51, 100)  // Moderate
            vocPpm <= 10.0f -> linearScale(vocPpm, 6.0f, 10.0f, 101, 150)  // Unhealthy for Sensitive
            else -> linearScale(vocPpm, 10.0f, 500.0f, 151, 200)  // Unhealthy
        }
    }

    fun calculateHumidityComfort(): Int {
        return when {
            humidity < 30 -> 1
            humidity <= 60 -> 0
            else -> 2
        }
    }

    fun calculateWeightedAQI(): Int {
        val vocWeight = if (isIndoors) 0.4f else 0.3f
        val pm25Weight = if (isIndoors) 0.4f else 0.5f
        val humidityWeight = 0.2f

        return (
                (calculatePM25AQI() * pm25Weight) +
                        (calculateVOCIndex() * vocWeight) +
                        (calculateHumidityComfort() * 50 * humidityWeight)
                ).toInt()
    }

    private fun linearScale(value: Float, minInput: Float, maxInput: Float, minOutput: Int, maxOutput: Int): Int {
        return ((value - minInput) / (maxInput - minInput) * (maxOutput - minOutput) + minOutput).toInt()
    }

    fun toTableRow(): Map<String, Any> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        return mapOf(
            "date" to dateFormat.format(timestamp),
            "time" to timeFormat.format(timestamp),
            "temperature" to temperature,
            "humidity" to humidity,
            "pressure" to pressure,
            "gasResistance" to gasResistance,
            "vocPpm" to vocPpm,
            "pm25" to pm25,
            "pm25AQI" to calculatePM25AQI(),
            "vocIndex" to calculateVOCIndex(),
            "humidityComfort" to calculateHumidityComfort(),
            "weightedAQI" to calculateWeightedAQI(),
            "latitude" to (latitude ?: 0.0),
            "longitude" to (longitude ?: 0.0),
            "gpsPing" to gpsPing,
            "isIndoors" to isIndoors
        )
    }

    fun getDebugInfo(): String {
        return """
            Raw Sensor Data:
            Temperature: $temperature °C
            Humidity: $humidity %
            Pressure: $pressure Pa
            Gas Resistance: $gasResistance kΩ
            VOC: $vocPpm PPM
            PM2.5: $pm25 µg/m³
            
            Calculated Values:
            PM2.5 AQI: ${calculatePM25AQI()}
            VOC Index: ${calculateVOCIndex()}
            Humidity Comfort: ${calculateHumidityComfort()}
            Weighted AQI: ${calculateWeightedAQI()}
            
            Location Data:
            Latitude: ${latitude ?: "N/A"}
            Longitude: ${longitude ?: "N/A"}
            GPS Accuracy: $gpsPing m
            Location Type: ${if (isIndoors) "Indoor" else "Outdoor"}
            
            Timestamp:
            Date: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(timestamp)}
            Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(timestamp)}
        """.trimIndent()
    }
}

