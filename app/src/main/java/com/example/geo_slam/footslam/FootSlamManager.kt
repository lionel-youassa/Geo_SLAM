package com.example.geo_slam.footslam

import android.hardware.Sensor

/**
 * Lionel : Gestionnaire du FootSLAM IA (RoNIN / TLIO)
 */
class FootSlamManager {

    /**
     * Méthode appelée par Sonia (SensorHardware) pour injecter les données IMU
     */
    fun onSensorDataReceived(type: Int, values: FloatArray, timestamp: Long) {
        when (type) {
            Sensor.TYPE_ACCELEROMETER -> {
                processAccelerometer(values[0], values[1], values[2], timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> {
                processGyroscope(values[0], values[1], values[2], timestamp)
            }
        }
    }

    // --- Méthodes Natives (Track A) ---
    private external fun processAccelerometer(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processGyroscope(x: Float, y: Float, z: Float, timestamp: Long)

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}