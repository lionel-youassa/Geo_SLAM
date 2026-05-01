package com.example.geo_slam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.geo_slam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialisation du SensorManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        binding.sampleText.text = "Geo-SLAM : Initialisation..."
    }

    override fun onResume() {
        super.onResume()
        // Semaine 1 : Mise en place du SensorManager (100Hz = 10 000 µs)
        accelerometer?.also { accel ->
            sensorManager.registerListener(this, accel, 10000)
        }
        gyroscope?.also { gyro ->
            sensorManager.registerListener(this, gyro, 10000)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            // Track A : Transmission des signaux IMU au moteur C++ (FootSLAM IA)
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    processAccelerometer(it.values[0], it.values[1], it.values[2], it.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    processGyroscope(it.values[0], it.values[1], it.values[2], it.timestamp)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Optionnel : Gérer les changements de précision
    }

    /**
     * Méthodes natives implémentées dans 'geo_slam' (C++)
     */
    private external fun processAccelerometer(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun processGyroscope(x: Float, y: Float, z: Float, timestamp: Long)
    private external fun stringFromJNI(): String

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}