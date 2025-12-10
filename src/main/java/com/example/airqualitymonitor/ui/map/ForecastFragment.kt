package com.example.airqualitymonitor.ui.map

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.airqualitymonitor.data.models.ForecastManager
import com.example.airqualitymonitor.data.models.AQIDataPoint
import com.example.airqualitymonitor.databinding.FragmentForecastBinding
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.activityViewModels


import com.example.airqualitymonitor.utils.Constants

class ForecastFragment : Fragment() {
    private var _binding: FragmentForecastBinding? = null
    private val binding get() = _binding!!
    private lateinit var forecastManager: ForecastManager
    private lateinit var mapManager: MapManager
    private var isLoading = false
    private var forecastData: List<AQIDataPoint>? = null
    private val viewModel: ForecastViewModel by activityViewModels()

    companion object {
        private const val TAG = "ForecastFragment"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forecastManager = ForecastManager(requireContext())
        mapManager = MapManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentForecastBinding.inflate(inflater, container, false)
        return binding.root
    }

    private fun setupMap() {
        mapManager.setupMap(binding.map)
    }

    private fun refreshMap() {
        forecastData?.let { data ->
            Log.d(TAG, "Refreshing map with ${data.size} points")

            binding.map.overlays.clear()
            binding.map.overlays.add(mapManager.createDataOverlay(data))
            binding.map.overlays.add(mapManager.createLegendOverlay())

            binding.map.invalidate()
        }
    }

    private fun setupMetricSpinner() {
        val metrics = arrayOf("Total AQI", "PM2.5 AQI", "VOC AQI")
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            metrics
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.metricSpinner.adapter = adapter

        binding.metricSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val metric = when (pos) {
                    0 -> MapManager.AQIMetric.TOTAL_AQI
                    1 -> MapManager.AQIMetric.PM25_AQI
                    2 -> MapManager.AQIMetric.VOC_AQI
                    else -> MapManager.AQIMetric.TOTAL_AQI
                }

                mapManager.updateMetricOnly(metric)

                forecastData?.let { data ->
                    binding.map.overlays.clear()

                    binding.map.overlays.add(mapManager.createDataOverlay(data))
                    binding.map.overlays.add(mapManager.createLegendOverlay())

                    binding.map.requestLayout()

                    binding.map.invalidate()
                    binding.map.postInvalidate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
    private fun setupForecastButton() {
        binding.forecastButton.setOnClickListener {
            if (!isLoading) {
                generateForecast()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        binding.forecastButton.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun generateForecast() {
        lifecycleScope.launch {
            try {
                setLoading(true)
                val historicalData = fetchHistoricalData()

                if (historicalData.isEmpty()) {
                    Toast.makeText(context, "No historical data available", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val lastDate = historicalData
                    .maxByOrNull { it.day }?.let { maxDay ->
                        val calendar = Calendar.getInstance()
                        if (Constants.DEBUG_MODE) {
                            calendar.time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                .parse(Constants.DEBUG_DATE)!!
                        }
                        calendar.add(Calendar.DAY_OF_YEAR, (6 - maxDay.day))
                        calendar.time
                    }

                if (lastDate == null) {
                    Toast.makeText(context, "Error determining forecast date", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val calendar = Calendar.getInstance()
                calendar.time = lastDate
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val targetForecastDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(calendar.time)

                if (viewModel.forecastDate.value == targetForecastDate) {
                    Toast.makeText(
                        context,
                        "Forecast for $targetForecastDate has already been generated",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val forecast = forecastManager.generateForecast(historicalData)
                if (forecast != null) {
                    viewModel.setForecast(forecast, targetForecastDate)

                    binding.map.overlays.clear()
                    binding.map.overlays.add(mapManager.createDataOverlay(forecast))
                    binding.map.overlays.add(mapManager.createLegendOverlay())
                    binding.map.requestLayout()
                    binding.map.invalidate()
                    binding.map.postInvalidate()
                } else {
                    Toast.makeText(context, "Failed to generate forecast", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating forecast", e)
                Toast.makeText(context, "Error generating forecast", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMap()
        setupMetricSpinner()
        setupForecastButton()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.forecastData.collect { data ->
                    Log.d(TAG, "Received forecast data update: ${data?.size} points")
                    data?.let { updateForecastDisplay(it) }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.forecastDate.collect { date ->
                    Log.d(TAG, "Received forecast date update: $date")
                    updateDateDisplay(date)
                }
            }
        }

        if (viewModel.hasForecast()) {
            Log.d(TAG, "Restoring existing forecast")
            viewModel.forecastData.value?.let { updateForecastDisplay(it) }
            viewModel.forecastDate.value?.let { updateDateDisplay(it) }
        }
    }

    private fun updateDateDisplay(date: String?) {
        binding.forecastDateText.apply {
            if (date != null) {
                text = "Forecast for $date"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun updateForecastDisplay(forecast: List<AQIDataPoint>) {
        Log.d(TAG, "Updating forecast display with ${forecast.size} points")
        forecastData = forecast
        refreshMap()
    }


    private suspend fun fetchHistoricalData(): List<AQIDataPoint> = withContext(Dispatchers.IO) {
        val database = FirebaseDatabase.getInstance()
        val dataRef = database.getReference("air_quality_data")
        val historicalData = mutableListOf<AQIDataPoint>()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val calendar = Calendar.getInstance()

        if (Constants.DEBUG_MODE) {
            calendar.time = dateFormat.parse(Constants.DEBUG_DATE)!!
        }

        Log.d(TAG, "Starting historical data fetch")


        for (i in 0 until ForecastManager.SEQUENCE_LENGTH) {
            val date = dateFormat.format(calendar.time)
            Log.d(TAG, "Fetching data for date: $date")

            try {
                val snapshot = dataRef.child(date).get().await()
                Log.d(TAG, "Data snapshot for $date exists: ${snapshot.exists()}")
                Log.d(TAG, "Number of readings: ${snapshot.childrenCount}")

                snapshot.children.forEach { reading ->
                    try {
                        val data = reading.value as? Map<*, *>
                        if (data == null) {
                            Log.e(TAG, "Invalid reading data format")
                            return@forEach
                        }

                        val lat = data["latitude"] as? Double
                        val lon = data["longitude"] as? Double
                        val pm25 = (data["pm25AQI"] as? Long)?.toDouble()
                        val voc = (data["vocIndex"] as? Long)?.toDouble()
                        val total = (data["weightedAQI"] as? Long)?.toDouble()

                        if (lat == null || lon == null || pm25 == null ||
                            voc == null || total == null) {
                            Log.e(TAG, "Missing required fields in reading: $data")
                            return@forEach
                        }

                        historicalData.add(AQIDataPoint(
                            location = GeoPoint(lat, lon),
                            pm25Value = pm25,
                            vocValue = voc,
                            totalValue = total,
                            day = i
                        ))
                        Log.d(TAG, "Added data point: lat=$lat, lon=$lon, day=$i")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing reading: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching data for date $date: ${e.message}")
            }

            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        Log.d(TAG, "Historical data fetch complete. Total points: ${historicalData.size}")

        if (historicalData.isEmpty()) {
            Log.w(TAG, "No historical data found for any of the dates")
        } else {
            Log.d(TAG, "Data range - First day: ${historicalData.minOf { it.day }}, " +
                    "Last day: ${historicalData.maxOf { it.day }}")
        }

        historicalData
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        if (viewModel.hasForecast()) {
            Log.d(TAG, "onResume: Restoring forecast display")
            viewModel.forecastData.value?.let { updateForecastDisplay(it) }
        }
    }


    override fun onPause() {
        super.onPause()
        binding.map.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        forecastManager.close()
        _binding = null
    }
}