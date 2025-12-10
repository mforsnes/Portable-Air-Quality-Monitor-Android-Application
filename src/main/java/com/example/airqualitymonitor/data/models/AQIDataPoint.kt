package com.example.airqualitymonitor.data.models

import org.osmdroid.util.GeoPoint

data class AQIDataPoint(
    val location: GeoPoint,
    val pm25Value: Double,
    val vocValue: Double,
    val totalValue: Double,
    val day: Int = 0
)