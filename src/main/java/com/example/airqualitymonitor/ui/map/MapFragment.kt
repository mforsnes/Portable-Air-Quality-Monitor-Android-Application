package com.example.airqualitymonitor.ui.map

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.airqualitymonitor.databinding.FragmentMapBinding
import org.osmdroid.views.MapView
import org.osmdroid.util.GeoPoint

class MapFragment : Fragment() {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private lateinit var mapManager: MapManager
    private var refreshJob: Job? = null

    companion object {
        private const val TAG = "MapFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        mapManager = MapManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMap()
        setupMetricSpinner()

        lifecycleScope.launch {
            mapManager.isLoading.collect { isLoading ->
                Log.d(TAG, "Loading state: $isLoading")
            }
        }

        startRefreshTimer()
    }

    private fun setupMap() {
        Log.d(TAG, "Setting up map")
        mapManager.setupMap(binding.map)

        lifecycleScope.launch {
            Log.d(TAG, "Starting initial data fetch")
            try {
                mapManager.refreshHeatmap()
                Log.d(TAG, "Initial data fetch complete")
                binding.map.controller.setCenter(GeoPoint(47.6553, -122.3035))
                binding.map.controller.setZoom(15.0)
                Log.d(TAG, "Map centered on UW")
            } catch (e: Exception) {
                Log.e(TAG, "Error in initial map setup", e)
            }
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
                Log.d(TAG, "Metric selected: ${metrics[pos]}")
                val metric = when (pos) {
                    0 -> MapManager.AQIMetric.TOTAL_AQI
                    1 -> MapManager.AQIMetric.PM25_AQI
                    2 -> MapManager.AQIMetric.VOC_AQI
                    else -> MapManager.AQIMetric.TOTAL_AQI
                }
                lifecycleScope.launch {
                    try {
                        mapManager.updateMetric(metric)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating metric", e)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                Log.d(TAG, "No metric selected")
            }
        }
    }

    private fun startRefreshTimer() {
        refreshJob?.cancel()

        lifecycleScope.launch {
            Log.d(TAG, "Performing initial data fetch")
            try {
                mapManager.refreshHeatmap()
            } catch (e: Exception) {
                Log.e(TAG, "Error in initial fetch", e)
            }
        }

        refreshJob = lifecycleScope.launch {
            while (true) {
                delay(MapManager.UPDATE_INTERVAL)
                Log.d(TAG, "Performing periodic refresh")
                try {
                    mapManager.refreshHeatmap()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic refresh", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Fragment resumed")
        binding.map.onResume()
        startRefreshTimer()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Fragment paused")
        binding.map.onPause()
        refreshJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "Fragment view destroyed")
        refreshJob?.cancel()
        _binding = null
    }
}