package com.example.airqualitymonitor.ui.map

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.example.airqualitymonitor.data.models.AQIDataPoint
import com.example.airqualitymonitor.utils.Constants

class MapManager(private val context: Context) {
    private var mapView: MapView? = null
    private var heatmapOverlay: HeatmapOverlay? = null
    private var legendOverlay: LegendOverlay? = null
    private val database = FirebaseDatabase.getInstance()
    private val dataRef = database.getReference("air_quality_data")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedMetric = MutableStateFlow(AQIMetric.TOTAL_AQI)
    val selectedMetric: StateFlow<AQIMetric> = _selectedMetric

    companion object {
        const val UPDATE_INTERVAL = 5 * 60 * 1000L
        private const val TAG = "MapManager"
    }

    enum class AQIMetric {
        PM25_AQI,
        VOC_AQI,
        TOTAL_AQI
    }

    fun setupMap(map: MapView) {
        mapView = map
        setupMapSettings()
        setupLegend()
    }

    private fun setupMapSettings() {
        mapView?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(47.6553, -122.3035))
        }
    }

    private fun setupLegend() {
        legendOverlay = createLegendOverlay()
        mapView?.overlays?.add(legendOverlay)
    }

    fun createDataOverlay(dataPoints: List<AQIDataPoint>) = HeatmapOverlay(dataPoints)
    fun createLegendOverlay() = LegendOverlay()

    fun updateMapOverlayWithData(dataPoints: List<AQIDataPoint>) {
        updateMapOverlay(dataPoints)
    }

    suspend fun updateMetric(metric: AQIMetric) {
        _selectedMetric.value = metric
        refreshHeatmap()
    }

    fun updateMetricOnly(metric: AQIMetric) {
        _selectedMetric.value = metric
    }

    suspend fun refreshHeatmap() {
        try {
            _isLoading.value = true
            val data = fetchTodayData()
            updateMapOverlay(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing heatmap", e)
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun fetchTodayData(): List<AQIDataPoint> = suspendCancellableCoroutine { continuation ->
        val dateToFetch = if (Constants.DEBUG_MODE) {
            Constants.DEBUG_DATE
        } else {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }

        Log.d(TAG, "Attempting to fetch data for date: $dateToFetch")

        val dataListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "onDataChange triggered")
                Log.d(TAG, "Snapshot exists: ${snapshot.exists()}")
                Log.d(TAG, "Snapshot child count: ${snapshot.childrenCount}")

                try {
                    val dataPoints = mutableListOf<AQIDataPoint>()

                    snapshot.children.forEach { recordSnapshot ->
                        Log.d(TAG, "Processing record: ${recordSnapshot.key}")
                        val record = recordSnapshot.value as? Map<*, *>
                        if (record == null) {
                            Log.e(TAG, "Record is null or not a Map")
                            return@forEach
                        }

                        Log.d(TAG, "Record data: $record")

                        try {
                            val latitude = (record["latitude"] as? Double)
                            val longitude = (record["longitude"] as? Double)
                            val pm25Value = (record["pm25AQI"] as? Long)?.toDouble()
                            val vocValue = (record["vocIndex"] as? Long)?.toDouble()
                            val totalValue = (record["weightedAQI"] as? Long)?.toDouble()

                            if (latitude == null || longitude == null ||
                                pm25Value == null || vocValue == null || totalValue == null) {
                                Log.e(TAG, """
                                Missing or invalid data in record:
                                latitude: $latitude
                                longitude: $longitude
                                pm25Value: $pm25Value
                                vocValue: $vocValue
                                totalValue: $totalValue
                            """.trimIndent())
                                return@forEach
                            }

                            Log.d(TAG, """
                            Created data point:
                            Location: ($latitude, $longitude)
                            PM2.5: $pm25Value
                            VOC: $vocValue
                            Total: $totalValue
                        """.trimIndent())

                            dataPoints.add(AQIDataPoint(
                                location = GeoPoint(latitude, longitude),
                                pm25Value = pm25Value,
                                vocValue = vocValue,
                                totalValue = totalValue
                            ))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing record data: ${record}", e)
                        }
                    }

                    Log.d(TAG, "Successfully processed ${dataPoints.size} data points")
                    if (dataPoints.isEmpty()) {
                        Log.w(TAG, "No valid data points were created from the snapshot")
                    } else {
                        Log.d(TAG, "Data bounds:")
                        val minLat = dataPoints.minOf { it.location.latitude }
                        val maxLat = dataPoints.maxOf { it.location.latitude }
                        val minLon = dataPoints.minOf { it.location.longitude }
                        val maxLon = dataPoints.maxOf { it.location.longitude }
                        Log.d(TAG, "Latitude range: $minLat to $maxLat")
                        Log.d(TAG, "Longitude range: $minLon to $maxLon")

                        dataPoints.take(3).forEachIndexed { index, point ->
                            Log.d(TAG, """
                            Sample point $index:
                            Location: (${point.location.latitude}, ${point.location.longitude})
                            PM2.5: ${point.pm25Value}
                            VOC: ${point.vocValue}
                            Total: ${point.totalValue}
                        """.trimIndent())
                        }
                    }

                    continuation.resume(dataPoints)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing data snapshot", e)
                    continuation.resumeWithException(e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Database error: ${error.message}")
                Log.e(TAG, "Error details: ${error.details}")
                Log.e(TAG, "Error code: ${error.code}")
                continuation.resumeWithException(error.toException())
            }
        }

        Log.d(TAG, "Adding listener for data fetch at date: $dateToFetch")
        dataRef.child(dateToFetch).addListenerForSingleValueEvent(dataListener)

        continuation.invokeOnCancellation {
            Log.d(TAG, "Removing database listener due to cancellation")
            dataRef.child(dateToFetch).removeEventListener(dataListener)
        }
    }

    private fun updateMapOverlay(dataPoints: List<AQIDataPoint>) {
        Log.d(TAG, "Updating map overlay with ${dataPoints.size} points")

        if (dataPoints.isEmpty()) {
            Log.w(TAG, "No data points to display")
            return
        }

        heatmapOverlay?.let { overlay ->
            mapView?.overlays?.remove(overlay)
        }

        heatmapOverlay = HeatmapOverlay(dataPoints)
        mapView?.overlays?.apply {
            add(heatmapOverlay)
            remove(legendOverlay)
            add(legendOverlay)
        }

        val minLat = dataPoints.minOf { it.location.latitude }
        val maxLat = dataPoints.maxOf { it.location.latitude }
        val minLon = dataPoints.minOf { it.location.longitude }
        val maxLon = dataPoints.maxOf { it.location.longitude }
        Log.d(TAG, "Data bounds: Lat[$minLat, $maxLat], Lon[$minLon, $maxLon]")

        mapView?.invalidate()
    }

    private fun AQIDataPoint.getValueForMetric(metric: AQIMetric): Double {
        return when (metric) {
            AQIMetric.PM25_AQI -> pm25Value
            AQIMetric.VOC_AQI -> vocValue
            AQIMetric.TOTAL_AQI -> totalValue
        }
    }

    inner class HeatmapOverlay(private val dataPoints: List<AQIDataPoint>) : Overlay() {
        private val paint = Paint().apply {
            isAntiAlias = true
        }

        override fun draw(pCanvas: Canvas?, pMapView: MapView?, pShadow: Boolean) {
            if (pCanvas == null || pShadow || dataPoints.isEmpty()) return

            val radius = 25f
            val colors = getAqiColors()
            val currentMetric = _selectedMetric.value

            Log.d(TAG, "Drawing heatmap with ${dataPoints.size} points")

            dataPoints.forEach { point ->
                val pixelPoint = pMapView?.projection?.toPixels(point.location, null)
                if (pixelPoint == null) {
                    Log.e(TAG, "Failed to convert GeoPoint to pixels: ${point.location}")
                    return@forEach
                }

                val value = point.getValueForMetric(currentMetric)

                val gradient = RadialGradient(
                    pixelPoint.x.toFloat(),
                    pixelPoint.y.toFloat(),
                    radius,
                    getColorForValue(value, colors),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )

                paint.shader = gradient
                paint.alpha = 200
                pCanvas.drawCircle(
                    pixelPoint.x.toFloat(),
                    pixelPoint.y.toFloat(),
                    radius,
                    paint
                )
            }
        }
    }

    inner class LegendOverlay : Overlay() {
        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        private val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        override fun draw(pCanvas: Canvas?, pMapView: MapView?, pShadow: Boolean) {
            if (pCanvas == null || pShadow) return

            val width = pMapView?.width ?: return
            val height = pMapView.height

            val legendWidth = width * 0.8f
            val legendHeight = 12f
            val legendX = (width - legendWidth) / 2
            val legendY = height - legendHeight - 40f

            val bgPadding = 4f
            paint.color = Color.WHITE
            pCanvas.drawRect(
                legendX - bgPadding,
                legendY - bgPadding,
                legendX + legendWidth + bgPadding,
                legendY + legendHeight + 32f,
                paint
            )

            val gradient = LinearGradient(
                legendX, legendY,
                legendX + legendWidth, legendY,
                getAqiColors(),
                null,
                Shader.TileMode.CLAMP
            )
            paint.shader = gradient
            pCanvas.drawRect(
                legendX, legendY,
                legendX + legendWidth, legendY + legendHeight,
                paint
            )

            val (labels, values) = when (_selectedMetric.value) {
                AQIMetric.PM25_AQI, AQIMetric.VOC_AQI, AQIMetric.TOTAL_AQI -> Pair(
                    arrayOf("Good", "Moderate", "Unhealthy", "Hazardous"),
                    arrayOf("0-50", "51-100", "101-150", "151+")
                )
            }

            val positions = arrayOf(0.125f, 0.375f, 0.625f, 0.875f)

            labels.forEachIndexed { index, label ->
                val x = legendX + (legendWidth * positions[index])

                pCanvas.drawText(
                    label,
                    x,
                    legendY + legendHeight + 12f,
                    textPaint
                )

                pCanvas.drawText(
                    values[index],
                    x,
                    legendY + legendHeight + 28f,
                    textPaint
                )
            }
        }
    }

    private fun getAqiColors() = intArrayOf(
        Color.rgb(0, 228, 0),      //Good - Green
        Color.rgb(255, 255, 0),    //Moderate - Yellow
        Color.rgb(255, 126, 0),    //Unhealthy - Orange
        Color.rgb(255, 0, 0)       //Hazardous - Red
    )

    private fun getColorForValue(value: Double, colors: IntArray): Int {
        val normalized = (value / 300).coerceIn(0.0, 1.0)
        val index = (normalized * (colors.size - 1)).toInt()
        val nextIndex = (index + 1).coerceAtMost(colors.size - 1)
        val fraction = normalized * (colors.size - 1) - index

        return interpolateColor(colors[index], colors[nextIndex], fraction.toFloat())
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val startA = Color.alpha(startColor)
        val startR = Color.red(startColor)
        val startG = Color.green(startColor)
        val startB = Color.blue(startColor)

        val endA = Color.alpha(endColor)
        val endR = Color.red(endColor)
        val endG = Color.green(endColor)
        val endB = Color.blue(endColor)

        return Color.argb(
            (startA + (endA - startA) * fraction).toInt(),
            (startR + (endR - startR) * fraction).toInt(),
            (startG + (endG - startG) * fraction).toInt(),
            (startB + (endB - startB) * fraction).toInt()
        )
    }
}