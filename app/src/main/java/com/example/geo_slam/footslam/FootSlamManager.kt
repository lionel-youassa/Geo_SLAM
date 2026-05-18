package com.example.geo_slam.footslam

import android.content.Context
import android.content.res.AssetManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Lionel : Gestionnaire du FootSLAM IA (RoNIN / TLIO)
 * Gère l'acquisition des capteurs en direct (100Hz).
 * Mise à jour Semaine 5 : Intégration Baromètre pour l'altitude (Z).
 */
class FootSlamManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    // Interface pour Sonia (UI/3D)
    interface OnPositionUpdateListener {
        fun onPositionUpdate(x: Float, y: Float, z: Float)
    }

    private var positionListener: OnPositionUpdateListener? = null

    fun setOnPositionUpdateListener(listener: OnPositionUpdateListener) {
        this.positionListener = listener
    }

    /**
     * Charge le modèle TFLite depuis les assets
     */
    fun initModel(assetManager: AssetManager, modelPath: String): Boolean {
        return loadModelNative(assetManager, modelPath)
    }

    fun startAcquisition() {
        // Lionel : Acquisition à 100Hz pour les flux IMU
        accelerometer?.also { sensorManager.registerListener(this, it, 10000) }
        gyroscope?.also { sensorManager.registerListener(this, it, 10000) }
        rotationVector?.also { sensorManager.registerListener(this, it, 10000) }
        
        // Le baromètre peut être plus lent (20Hz suffit généralement pour l'altitude)
        pressureSensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stopAcquisition() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Réinitialise la trajectoire
     */
    fun reset() {
        resetPositionNative()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    processAccelerometer(it.values[0], it.values[1], it.values[2], it.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    processGyroscope(it.values[0], it.values[1], it.values[2], it.timestamp)
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    processOrientation(it.values[0], it.values[1], it.values[2], it.values[3], it.timestamp)
                }
                Sensor.TYPE_PRESSURE -> {
                    // Semaine 5 : Lionel envoie la pression (hPa) au moteur C++
                    processPressure(it.values[0], it.timestamp)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Cette méthode sera appelée depuis le C++ (JNI) une fois que l'IA aura calculé la position.
     */
    private fun onPositionCalculated(x: Float, y: Float, z: Float) {
        positionListener?.onPositionUpdate(x, y, z)
    }

    // --- Méthodes Natives ---
    private external fun loadModelNative(assetManager: AssetManager, modelPath: String): Boolean
    private external fun processAccelerometer(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processGyroscope(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processOrientation(x: Float, y: Float, z: Float, w: Float, timestamp: Long)
    private external fun processPressure(pressure: Float, timestamp: Long)
    private external fun resetPositionNative()

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}