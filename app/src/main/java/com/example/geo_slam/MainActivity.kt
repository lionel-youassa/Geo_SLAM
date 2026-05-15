package com.example.geo_slam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.geo_slam.databinding.ActivityMainBinding
import com.example.geo_slam.footslam.FootSlamManager
import com.example.geo_slam.vslam.VSlamManager
import java.util.Locale

class MainActivity : AppCompatActivity(),
    FootSlamManager.OnPositionUpdateListener,
    VSlamManager.OnFrameProcessedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var footSlamManager: FootSlamManager
    private lateinit var vSlamManager: VSlamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lionel : FootSLAM
        footSlamManager = FootSlamManager(this)
        footSlamManager.setOnPositionUpdateListener(this)
        val modelLoaded = footSlamManager.initModel(assets, "ronin_model.tflite")
        if (modelLoaded) {
            binding.sampleText.text = "Geo-SLAM : Moteur IA Opérationnel"
        } else {
            binding.sampleText.text = "Erreur : ronin_model.tflite introuvable"
        }

        // Narcisse : vSLAM
        vSlamManager = VSlamManager(this)
        vSlamManager.setOnFrameProcessedListener(this)

        binding.btnReset.setOnClickListener {
            footSlamManager.reset()
            updateUI(0f, 0f)
            Toast.makeText(this, "Trajectoire réinitialisée", Toast.LENGTH_SHORT).show()
        }

        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            vSlamManager.startCamera()
            Log.i("GeoSlam_Main", "Permission caméra OK — pipeline vSLAM démarré.")
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            vSlamManager.startCamera()
            Log.i("GeoSlam_Main", "Permission caméra accordée — pipeline vSLAM démarré.")
        } else {
            Toast.makeText(this, "Permission caméra refusée — vSLAM inactif.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        footSlamManager.startAcquisition()
    }

    override fun onPause() {
        super.onPause()
        footSlamManager.stopAcquisition()
        vSlamManager.stopCamera()
    }

    // Callback FootSLAM (Lionel → Sonia)
    override fun onPositionUpdate(x: Float, y: Float, z: Float) {
        runOnUiThread { updateUI(x, y) }
    }

    // Callback vSLAM (Narcisse → Sonia)
    override fun onFrameProcessed(x: Float, y: Float, z: Float) {
        Log.d("GeoSlam_Main", "Pose vSLAM reçue : x=$x y=$y z=$z")
    }

    private fun updateUI(x: Float, y: Float) {
        binding.tvPosX.text = String.format(Locale.US, "Position X : %.2f m", x)
        binding.tvPosY.text = String.format(Locale.US, "Position Y : %.2f m", y)
    }
}
