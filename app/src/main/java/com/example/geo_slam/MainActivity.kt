package com.example.geo_slam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.geo_slam.footslam.FootSlamManager
import com.example.geo_slam.ui.map.MapViewModel
import com.example.geo_slam.vslam.VSlamManager

class MainActivity : AppCompatActivity(),
    FootSlamManager.OnPositionUpdateListener,
    VSlamManager.OnFrameProcessedListener,
    VSlamManager.OnPointCloudListener,
    VSlamManager.OnTrackingStateListener {

    private lateinit var footSlamManager: FootSlamManager
    private lateinit var vSlamManager: VSlamManager

    // ViewModel partagé avec MapFragment via activityViewModels()
    private val mapViewModel: MapViewModel by viewModels()

    private var prevFootX = 0f
    private var prevFootY = 0f
    private var prevFootZ = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)   // NavHostFragment défini dans nav_graph.xml

        // ── FootSLAM (Lionel) ────────────────────────────────────────────────
        footSlamManager = FootSlamManager(this)
        footSlamManager.setOnPositionUpdateListener(this)
        val modelLoaded = footSlamManager.initModel(assets, "ronin_model.tflite")
        if (!modelLoaded) Log.w(TAG, "ronin_model.tflite introuvable — FootSLAM inactif.")

        // ── vSLAM (Narcisse) ─────────────────────────────────────────────────
        vSlamManager = VSlamManager(this)
        vSlamManager.setOnFrameProcessedListener(this)
        vSlamManager.setOnPointCloudListener(this)
        vSlamManager.setOnTrackingStateListener(this)

        requestCameraPermission()
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            vSlamManager.startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), RC_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            vSlamManager.startCamera()
        } else {
            Toast.makeText(this, "Permission caméra refusée — vSLAM inactif.", Toast.LENGTH_LONG).show()
        }
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        footSlamManager.startAcquisition()
    }

    override fun onPause() {
        super.onPause()
        footSlamManager.stopAcquisition()
        vSlamManager.stopCamera()
    }

    // ── Callback FootSLAM ────────────────────────────────────────────────────

    override fun onPositionUpdate(x: Float, y: Float, z: Float) {
        val dx = x - prevFootX
        val dy = y - prevFootY
        val dz = z - prevFootZ
        prevFootX = x; prevFootY = y; prevFootZ = z
        vSlamManager.updateFootSlamDisplacement(dx, dy, dz)
        runOnUiThread { mapViewModel.injectFootSlamPosition(x, y, z) }
    }

    // ── Callbacks vSLAM ──────────────────────────────────────────────────────

    override fun onFrameProcessed(x: Float, y: Float, z: Float) {
        runOnUiThread { mapViewModel.injectVslamPosition(x, y, z) }
    }

    override fun onPointCloudUpdated(points: FloatArray) {
        // MapCanvasView 2D ne rend pas encore le nuage 3D
    }

    override fun onTrackingStateChanged(state: VSlamManager.TrackingState) {
        Log.i(TAG, "Tracking : ${state.name}")
    }

    // ── Appelé par les fragments (ex: bouton Stop dans MapFragment) ───────────

    fun resetAll() {
        footSlamManager.reset()
        vSlamManager.reset()
        mapViewModel.reset()
        prevFootX = 0f; prevFootY = 0f; prevFootZ = 0f
    }

    companion object {
        private const val TAG      = "GeoSlam_Main"
        private const val RC_CAMERA = 100
    }
}
