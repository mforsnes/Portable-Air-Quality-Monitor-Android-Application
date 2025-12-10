package com.example.airqualitymonitor.data.models

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import android.content.res.AssetFileDescriptor
import java.io.FileInputStream
import java.nio.channels.FileChannel
import com.google.gson.Gson
import java.io.InputStreamReader
import org.osmdroid.util.GeoPoint
import kotlin.math.abs


data class ModelParams(
    val lat_bounds: List<Double>,
    val lon_bounds: List<Double>,
    val grid_size: Int,
    val sequence_length: Int,
    val scaler: ScalerParams
)

data class ScalerParams(
    val scale_: List<Double>,
    val min_: List<Double>,
    val data_min_: List<Double>,
    val data_max_: List<Double>,
    val data_range_: List<Double>
)

class ForecastManager(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var modelParams: ModelParams? = null
    private val scaler = MinMaxScaler()

    // Tuning parameters for forecast intensity
    private val FORECAST_INTENSITY = 0.4f
    private val PM25_INTENSITY = 0.3f
    private val VOC_INTENSITY = 0.7f
    private val TOTAL_INTENSITY = 0.8f
    private val SMOOTHING_WEIGHT = 0.85f

    companion object {
        private const val MODEL_FILE = "air_quality_model.tflite"
        private const val PARAMS_FILE = "air_quality_model_params.json"
        private const val TAG = "ForecastManager"
        const val GRID_SIZE = 64
        const val SEQUENCE_LENGTH = 7
    }

    init {
        Log.d(TAG, "Initializing ForecastManager")
        loadModel()
        loadParams()
    }

    private fun loadModel() {
        try {
            Log.d(TAG, "Starting model loading")
            val options = Interpreter.Options()
                .setUseNNAPI(false)
                .setNumThreads(4)

            Log.d(TAG, "Loading model file from assets")
            val modelBuffer = loadModelFile()
            Log.d(TAG, "Model buffer loaded, initializing interpreter")

            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "TFLite model loaded successfully")

            interpreter?.let {
                Log.d(TAG, "Model input tensor count: ${it.inputTensorCount}")
                Log.d(TAG, "Model output tensor count: ${it.outputTensorCount}")
                Log.d(TAG, "Input tensor shape: ${it.getInputTensor(0).shape().joinToString()}")
                Log.d(TAG, "Output tensor shape: ${it.getOutputTensor(0).shape().joinToString()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        Log.d(TAG, "Loading model file: $MODEL_FILE")
        try {
            val fileDescriptor: AssetFileDescriptor = context.assets.openFd(MODEL_FILE)
            Log.d(TAG, "File descriptor obtained, size: ${fileDescriptor.declaredLength}")

            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel: FileChannel = inputStream.channel
            val startOffset: Long = fileDescriptor.startOffset
            val declaredLength: Long = fileDescriptor.declaredLength

            Log.d(TAG, "Mapping file to buffer: offset=$startOffset, length=$declaredLength")
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model file: ${e.message}")
            throw e
        }
    }

    private fun loadParams() {
        try {
            Log.d(TAG, "Loading model parameters from: $PARAMS_FILE")
            context.assets.open(PARAMS_FILE).use { inputStream ->
                val reader = InputStreamReader(inputStream)
                modelParams = Gson().fromJson(reader, ModelParams::class.java)
                Log.d(TAG, "Parameters loaded: grid_size=${modelParams?.grid_size}, sequence_length=${modelParams?.sequence_length}")
                Log.d(TAG, "Lat bounds: ${modelParams?.lat_bounds}")
                Log.d(TAG, "Lon bounds: ${modelParams?.lon_bounds}")

                scaler.setup(modelParams!!.scaler)
                Log.d(TAG, "Scaler setup complete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model parameters: ${e.message}")
            e.printStackTrace()
        }
    }

    private lateinit var historicalData: List<AQIDataPoint>

    fun generateForecast(historicalData: List<AQIDataPoint>): List<AQIDataPoint>? {
        this.historicalData = historicalData
        Log.d(TAG, "Starting forecast generation with ${historicalData.size} historical points")

        if (interpreter == null || modelParams == null) {
            Log.e(TAG, "Model or parameters not initialized")
            return null
        }

        try {
            Log.d(TAG, "Converting historical data to grid sequence")
            val sequence = convertToGridSequence(historicalData)

            val inputShape = intArrayOf(1, SEQUENCE_LENGTH, GRID_SIZE, GRID_SIZE, 3)
            val outputShape = intArrayOf(1, SEQUENCE_LENGTH, GRID_SIZE, GRID_SIZE, 3)
            Log.d(TAG, "Input shape: ${inputShape.joinToString()}")
            Log.d(TAG, "Output shape: ${outputShape.joinToString()}")

            Log.d(TAG, "Scaling input data")
            val scaledSequence = scaler.transform(sequence)

            Log.d(TAG, "Preparing input buffer")
            val inputBuffer = ByteBuffer.allocateDirect(4 * inputShape.reduce { acc, i -> acc * i })
                .order(ByteOrder.nativeOrder())
            fillInputBuffer(inputBuffer, scaledSequence)

            Log.d(TAG, "Preparing output buffer")
            val outputBuffer = ByteBuffer.allocateDirect(4 * outputShape.reduce { acc, i -> acc * i })
                .order(ByteOrder.nativeOrder())

            Log.d(TAG, "Running model inference")
            val inputs = arrayOf(inputBuffer)
            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = outputBuffer

            interpreter?.runForMultipleInputsOutputs(inputs, outputs)
            Log.d(TAG, "Model inference complete")

            outputBuffer.rewind()
            val forecastGrid = extractForecastFromBuffer(outputBuffer)

            var points = convertGridToPoints(forecastGrid)
            Log.d(TAG, "Generated ${points.size} forecast points")

            points = smoothPolarizedValues(points)
            Log.d(TAG, "Applied smoothing to forecast points")

            return points

        } catch (e: Exception) {
            Log.e(TAG, "Error during forecast generation: ${e.message}")
            e.printStackTrace()
            return null
        }
    }


    private fun convertToGridSequence(historicalData: List<AQIDataPoint>): Array<FloatArray> {
        val result = Array(SEQUENCE_LENGTH * GRID_SIZE * GRID_SIZE) { FloatArray(3) }
        var index = 0

        for (day in 0 until SEQUENCE_LENGTH) {
            val dayData = historicalData.filter { it.day == day }

            for (x in 0 until GRID_SIZE) {
                for (y in 0 until GRID_SIZE) {
                    val point = findNearestPoint(x, y, dayData)
                    result[index][0] = point.pm25Value.toFloat()
                    result[index][1] = point.vocValue.toFloat()
                    result[index][2] = point.totalValue.toFloat()
                    index++
                }
            }
        }

        return result
    }

    private fun fillInputBuffer(buffer: ByteBuffer, sequence: Array<FloatArray>) {
        Log.d(TAG, "Filling input buffer with sequence data")
        sequence.forEach { values ->
            values.forEach { value ->
                buffer.putFloat(value)
            }
        }
        buffer.rewind()
        Log.d(TAG, "Input buffer filled and rewound")
    }

    private fun extractForecastFromBuffer(buffer: ByteBuffer): Array<Array<FloatArray>> {
        Log.d(TAG, "Starting forecast extraction from buffer")
        val result = Array(GRID_SIZE) {
            Array(GRID_SIZE) {
                FloatArray(3) { 0f }
            }
        }

        val lastTimeStepOffset = (SEQUENCE_LENGTH - 1) * GRID_SIZE * GRID_SIZE * 3 * 4
        buffer.position(lastTimeStepOffset)

        val valueStats = Array(3) { mutableListOf<Float>() }

        for (x in 0 until GRID_SIZE) {
            for (y in 0 until GRID_SIZE) {
                for (i in 0..2) {
                    val value = buffer.float
                    valueStats[i].add(value)

                    val scaledValue = value * FORECAST_INTENSITY * when(i) {
                        0 -> PM25_INTENSITY
                        1 -> VOC_INTENSITY
                        else -> TOTAL_INTENSITY
                    }

                    result[x][y][i] = scaledValue.coerceIn(0f, 500f)

                    if (x < 2 && y < 2) {
                        Log.d(TAG, "Value at ($x,$y) metric $i: original=$value, scaled=$scaledValue")
                    }
                }
            }
        }

        val metricNames = arrayOf("PM2.5", "VOC", "Total")
        valueStats.forEachIndexed { index, values ->
            Log.d(TAG, """
            ${metricNames[index]} Distribution:
            Min: ${values.minOrNull()}
            Max: ${values.maxOrNull()}
            Mean: ${values.average()}
            Sample values: ${values.take(5)}
        """.trimIndent())
        }

        return result
    }


    private fun convertGridToPoints(grid: Array<Array<FloatArray>>): List<AQIDataPoint> {
        Log.d(TAG, "Converting grid forecast to GPS points")
        val points = mutableListOf<AQIDataPoint>()

        val (latMin, latMax) = modelParams!!.lat_bounds
        val (lonMin, lonMax) = modelParams!!.lon_bounds

        val locationFrequency = historicalData
            .groupBy { Pair(it.location.latitude, it.location.longitude) }
            .mapValues { it.value.size }

        val avgReadingsPerLocation = locationFrequency.values.average()
        val activeLocations = locationFrequency
            .filter { it.value >= avgReadingsPerLocation * 0.5 }
            .keys
            .toList()

        Log.d(TAG, "Found ${activeLocations.size} active locations out of ${locationFrequency.size} total locations")

        val pm25Threshold = 10.0
        val vocThreshold = 10.0
        val totalThreshold = 10.0

        val baselinePM25 = historicalData.map { it.pm25Value }.average()
        val baselineVOC = historicalData.map { it.vocValue }.average()
        val baselineTotal = historicalData.map { it.totalValue }.average()

        Log.d(TAG, """
        Baseline values:
        PM2.5: $baselinePM25
        VOC: $baselineVOC
        Total: $baselineTotal
    """.trimIndent())

        activeLocations.forEach { (lat, lon) ->
            val gridX = ((lat - latMin) / (latMax - latMin) * (GRID_SIZE - 1)).toInt()
                .coerceIn(0, GRID_SIZE - 1)
            val gridY = ((lon - lonMin) / (lonMax - lonMin) * (GRID_SIZE - 1)).toInt()
                .coerceIn(0, GRID_SIZE - 1)

            val pm25Value = smoothValueWithNeighbors(gridX, gridY, 0, grid)
            val vocValue = smoothValueWithNeighbors(gridX, gridY, 1, grid)
            val totalValue = smoothValueWithNeighbors(gridX, gridY, 2, grid)

            val pm25Diff = abs(pm25Value - baselinePM25)
            val vocDiff = abs(vocValue - baselineVOC)
            val totalDiff = abs(totalValue - baselineTotal)

            if (pm25Diff > pm25Threshold ||
                vocDiff > vocThreshold ||
                totalDiff > totalThreshold) {

                points.add(AQIDataPoint(
                    location = GeoPoint(lat, lon),
                    pm25Value = pm25Value,
                    vocValue = vocValue,
                    totalValue = totalValue
                ))

                if (points.size <= 5) {
                    Log.d(TAG, """
                    Added point at ($lat, $lon):
                    PM2.5: $pm25Value (diff: $pm25Diff)
                    VOC: $vocValue (diff: $vocDiff)
                    Total: $totalValue (diff: $totalDiff)
                """.trimIndent())
                }
            }
        }

        Log.d(TAG, "Generated ${points.size} significant forecast points from ${activeLocations.size} active locations")
        return points
    }

    private fun smoothValueWithNeighbors(x: Int, y: Int, metricIndex: Int, grid: Array<Array<FloatArray>>): Double {
        var weightedSum = 0.0
        var totalWeight = 0.0
        val weights = arrayOf(
            Triple(-1, -1, 0.5), Triple(-1, 0, 0.7), Triple(-1, 1, 0.5),
            Triple(0, -1, 0.7),  Triple(0, 0, 1.0),  Triple(0, 1, 0.7),
            Triple(1, -1, 0.5),  Triple(1, 0, 0.7),  Triple(1, 1, 0.5)
        )

        weights.forEach { (dx, dy, weight) ->
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until GRID_SIZE && ny in 0 until GRID_SIZE) {
                weightedSum += grid[nx][ny][metricIndex].toDouble() * weight
                totalWeight += weight
            }
        }

        return if (totalWeight > 0) weightedSum / totalWeight else grid[x][y][metricIndex].toDouble()
    }

    private fun findNearestPoint(gridX: Int, gridY: Int, points: List<AQIDataPoint>): AQIDataPoint {
        val (latMin, latMax) = modelParams!!.lat_bounds
        val (lonMin, lonMax) = modelParams!!.lon_bounds

        val targetLat = latMin + (gridX.toDouble() / (GRID_SIZE - 1)) * (latMax - latMin)
        val targetLon = lonMin + (gridY.toDouble() / (GRID_SIZE - 1)) * (lonMax - lonMin)

        return points.minByOrNull { point ->
            val latDiff = point.location.latitude - targetLat
            val lonDiff = point.location.longitude - targetLon
            latDiff * latDiff + lonDiff * lonDiff
        } ?: AQIDataPoint(
            location = GeoPoint(targetLat, targetLon),
            pm25Value = 0.0,
            vocValue = 0.0,
            totalValue = 0.0
        )
    }

    private fun smoothPolarizedValues(points: List<AQIDataPoint>): List<AQIDataPoint> {
        val avgPM25 = points.map { it.pm25Value }.average()
        val avgVOC = points.map { it.vocValue }.average()
        val avgTotal = points.map { it.totalValue }.average()

        return points.map { point ->
            val nearbyPoints = points.filter { nearby ->
                val latDiff = nearby.location.latitude - point.location.latitude
                val lonDiff = nearby.location.longitude - point.location.longitude
                val distance = Math.sqrt(latDiff * latDiff + lonDiff * lonDiff)
                distance < 0.001
            }

            // Use SMOOTHING_WEIGHT instead of hardcoded 0.9
            val weight = SMOOTHING_WEIGHT

            fun smoothValue(value: Double, neighborValues: List<Double>, globalAvg: Double): Double {
                val neighborAvg = neighborValues.average()
                return if (Math.abs(value - neighborAvg) > globalAvg * 0.5 &&
                    Math.abs(value - globalAvg) > globalAvg * 0.5) {
                    value * weight + neighborAvg * (1 - weight)
                } else {
                    value
                }
            }

            AQIDataPoint(
                location = point.location,
                pm25Value = smoothValue(point.pm25Value, nearbyPoints.map { it.pm25Value }, avgPM25),
                vocValue = smoothValue(point.vocValue, nearbyPoints.map { it.vocValue }, avgVOC),
                totalValue = smoothValue(point.totalValue, nearbyPoints.map { it.totalValue }, avgTotal)
            )
        }
    }

    private inner class MinMaxScaler {
        internal lateinit var scale: FloatArray
        internal lateinit var min: FloatArray
        internal lateinit var dataMin: FloatArray
        internal lateinit var dataMax: FloatArray
        internal lateinit var dataRange: FloatArray

        fun setup(params: ScalerParams) {
            Log.d(TAG, "Setting up MinMaxScaler")
            // Convert all parameters to float arrays
            scale = params.scale_.map { it.toFloat() }.toFloatArray()
            min = params.min_.map { it.toFloat() }.toFloatArray()
            dataMin = params.data_min_.map { it.toFloat() }.toFloatArray()
            dataMax = params.data_max_.map { it.toFloat() }.toFloatArray()
            dataRange = params.data_range_.map { it.toFloat() }.toFloatArray()

            // Log all parameters for debugging
            Log.d(TAG, """
            MinMaxScaler Parameters:
            scale: ${scale.joinToString()}
            min: ${min.joinToString()}
            data_min: ${dataMin.joinToString()}
            data_max: ${dataMax.joinToString()}
            data_range: ${dataRange.joinToString()}
        """.trimIndent())
        }

        fun transform(data: Array<FloatArray>): Array<FloatArray> {
            return data.map { values ->
                FloatArray(values.size) { i ->
                    val xStd = (values[i] - dataMin[i % 3]) / dataRange[i % 3]
                    xStd * scale[i % 3] + min[i % 3]
                }
            }.toTypedArray()
        }

        fun inverseTransformBatch(scaledData: Array<Array<FloatArray>>): Array<Array<FloatArray>> {
            return Array(scaledData.size) { i ->
                Array(scaledData[i].size) { j ->
                    FloatArray(scaledData[i][j].size) { k ->
                        inverseTransform(scaledData[i][j][k], k)
                    }
                }
            }
        }

        fun inverseTransform(value: Float, index: Int): Float {
            val xStd = (value - min[index]) / scale[index]
            return (xStd * dataRange[index]) + dataMin[index]
        }
    }

    fun close() {
        Log.d(TAG, "Closing ForecastManager resources")
        try {
            interpreter?.close()
            Log.d(TAG, "Interpreter closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interpreter: ${e.message}")
        }
    }
}