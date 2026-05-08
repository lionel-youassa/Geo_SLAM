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
 */
class FootSlamManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

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
        // Lionel : Acquisition à 100Hz pour les 3 flux requis par RoNIN
        accelerometer?.also { sensorManager.registerListener(this, it, 10000) }
        gyroscope?.also { sensorManager.registerListener(this, it, 10000) }
        rotationVector?.also { sensorManager.registerListener(this, it, 10000) }
    }

    fun stopAcquisition() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Réinitialise la trajectoire (Semaine 2)
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
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Cette méthode sera appelée depuis le C++ (JNI) une fois que l'IA aura calculé la position.
     * C'est ici que Lionel renvoie les données à Sonia.
     */
    private fun onPositionCalculated(x: Float, y: Float, z: Float) {
        positionListener?.onPositionUpdate(x, y, z)
    }

    // --- Méthodes Natives (Track A) ---
    private external fun loadModelNative(assetManager: AssetManager, modelPath: String): Boolean
    private external fun processAccelerometer(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processGyroscope(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processOrientation(x: Float, y: Float, z: Float, w: Float, timestamp: Long)
    private external fun resetPositionNative()

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}