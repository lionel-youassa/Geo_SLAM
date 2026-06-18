package com.example.geo_slam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.geo_slam.footslam.FootSlamManager
import com.example.geo_slam.ui.map.MapViewModel
import com.example.geo_slam.vslam.VSlamManager

/**
 * MainActivity : Gère les permissions et le cycle de vie des moteurs.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var footSlamManager: FootSlamManager
    private lateinit var vSlamManager: VSlamManager
    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        footSlamManager = FootSlamManager.getInstance(this)
        vSlamManager = VSlamManager.getInstance(this)

        if (savedInstanceState == null) {
            footSlamManager.initModel(assets, "ronin_model.tflite")
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        footSlamManager.startAcquisition()
    }

    override fun onPause() {
        super.onPause()
        vSlamManager.stopCamera()
    }
}
