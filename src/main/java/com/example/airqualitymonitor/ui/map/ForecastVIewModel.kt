package com.example.airqualitymonitor.ui.map

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.example.airqualitymonitor.data.models.AQIDataPoint

class ForecastViewModel : ViewModel() {
    private val _forecastData = MutableStateFlow<List<AQIDataPoint>?>(null)
    val forecastData: StateFlow<List<AQIDataPoint>?> = _forecastData

    private val _forecastDate = MutableStateFlow<String?>(null)
    val forecastDate: StateFlow<String?> = _forecastDate

    fun setForecast(data: List<AQIDataPoint>, date: String) {
        _forecastData.value = data
        _forecastDate.value = date
    }

    fun hasForecast(): Boolean {
        return _forecastData.value != null && _forecastDate.value != null
    }

    fun clearForecast() {
        _forecastData.value = null
        _forecastDate.value = null
    }
}