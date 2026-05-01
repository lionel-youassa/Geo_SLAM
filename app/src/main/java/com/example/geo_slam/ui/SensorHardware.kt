package com.example.geo_slam.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.geo_slam.footslam.FootSlamManager

/**
 * Sonia : Gestionnaire des capteurs IMU (Accéléromètre/Gyroscope)
 */
class SensorHardware(context: Context, private val footSlamManager: FootSlamManager) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    fun start() {
        // Fréquence 100Hz demandée par Lionel
        accelerometer?.also { sensorManager.registerListener(this, it, 10000) }
        gyroscope?.also { sensorManager.registerListener(this, it, 10000) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            // Sonia envoie les données à Lionel pour traitement IA
            footSlamManager.onSensorDataReceived(it.sensor.type, it.values, it.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}