package com.example.geo_slam.footslam

import android.content.Context
import android.content.res.AssetManager
import android.graphics.PointF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.example.geo_slam.ui.map.FloorPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FootSlamManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val _positionFlow = MutableStateFlow(PointF(0f, 0f))
    val positionFlow = _positionFlow.asStateFlow()

    private val _headingFlow = MutableStateFlow(0f)
    val headingFlow = _headingFlow.asStateFlow()

    private val _altitudeFlow = MutableStateFlow(0f)
    val altitudeFlow = _altitudeFlow.asStateFlow()

    private val _stepCountFlow = MutableStateFlow(0)
    val stepCountFlow = _stepCountFlow.asStateFlow()

    var isModelLoaded: Boolean = false
        private set

    fun initModel(assetManager: AssetManager, modelPath: String): Boolean {
        setStepDetectorSupportedNative(stepDetector != null)
        isModelLoaded = loadModelNative(assetManager, modelPath)
        return isModelLoaded
    }

    /**
     * Définit le plan au moteur natif.
     * Seul le périmètre extérieur est désormais transmis pour permettre la libre circulation entre les zones.
     */
    fun setFloorPlan(floorPlan: FloorPlan) {
        val walls = floorPlan.outerWalls
        val wallCoords = FloatArray(walls.size * 4)
        walls.forEachIndexed { i, wall ->
            wallCoords[i * 4] = wall.x1
            wallCoords[i * 4 + 1] = wall.y1
            wallCoords[i * 4 + 2] = wall.x2
            wallCoords[i * 4 + 3] = wall.y2
        }
        setWallsNative(wallCoords)
    }

    fun startAcquisition() {
        val delay = 10000 // 100Hz
        accelerometer?.also { sensorManager.registerListener(this, it, delay) }
        gyroscope?.also { sensorManager.registerListener(this, it, delay) }
        rotationVector?.also { sensorManager.registerListener(this, it, delay) }
        pressureSensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepDetector?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stopAcquisition() {
        sensorManager.unregisterListener(this)
    }

    fun reset() {
        resetPositionNative()
        _positionFlow.update { PointF(0f, 0f) }
        _stepCountFlow.update { 0 }
    }

    fun setInitialPosition(x: Float, y: Float) {
        setInitialPositionNative(x, y)
        _positionFlow.update { PointF(x, y) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> processAccelerometer(it.values[0], it.values[1], it.values[2], it.timestamp)
                Sensor.TYPE_GYROSCOPE -> processGyroscope(it.values[0], it.values[1], it.values[2], it.timestamp)
                Sensor.TYPE_PRESSURE -> processPressure(it.values[0], it.timestamp)
                Sensor.TYPE_STEP_DETECTOR -> processStepDetectorNative(it.timestamp)
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    val remappedMatrix = FloatArray(9)
                    val orientationValues = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                    
                    val worldAxisX: Int
                    val worldAxisY: Int
                    when (windowManager.defaultDisplay.rotation) {
                        Surface.ROTATION_90 -> { worldAxisX = SensorManager.AXIS_Y; worldAxisY = SensorManager.AXIS_MINUS_X }
                        Surface.ROTATION_180 -> { worldAxisX = SensorManager.AXIS_MINUS_X; worldAxisY = SensorManager.AXIS_MINUS_Y }
                        Surface.ROTATION_270 -> { worldAxisX = SensorManager.AXIS_MINUS_Y; worldAxisY = SensorManager.AXIS_X }
                        else -> { worldAxisX = SensorManager.AXIS_X; worldAxisY = SensorManager.AXIS_Y }
                    }
                    
                    SensorManager.remapCoordinateSystem(rotationMatrix, worldAxisX, worldAxisY, remappedMatrix)
                    SensorManager.getOrientation(remappedMatrix, orientationValues)
                    
                    val yaw = orientationValues[0]
                    _headingFlow.update { Math.toDegrees(yaw.toDouble()).toFloat() }
                    processYawNative(yaw, it.timestamp)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onPositionCalculated(x: Float, y: Float, z: Float) {
        _positionFlow.update { PointF(x, y) }
        _altitudeFlow.update { z }
    }

    /**
     * Callback déclenché par le moteur natif lors de la détection d'un pas.
     */
    private fun onStepDetected() {
        _stepCountFlow.update { it + 1 }
    }

    private external fun loadModelNative(assetManager: AssetManager, modelPath: String): Boolean
    private external fun processAccelerometer(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processGyroscope(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processYawNative(yaw: Float, timestamp: Long)
    private external fun processPressure(pressure: Float, timestamp: Long)
    private external fun processStepDetectorNative(timestamp: Long)
    private external fun setStepDetectorSupportedNative(supported: Boolean)
    private external fun resetPositionNative()
    private external fun setInitialPositionNative(x: Float, y: Float)
    private external fun setWallsNative(walls: FloatArray)

    companion object {
        @Volatile
        private var INSTANCE: FootSlamManager? = null
        fun getInstance(context: Context): FootSlamManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FootSlamManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        init { System.loadLibrary("geo_slam") }
    }
}