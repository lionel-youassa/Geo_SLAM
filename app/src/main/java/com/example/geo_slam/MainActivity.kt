package com.example.geo_slam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.geo_slam.footslam.FootSlamManager

/**
 * MainActivity : Hôte du système de navigation Geo-SLAM.
 * Initialise le moteur IA de Lionel et affiche l'interface de Sonia via les Fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var footSlamManager: FootSlamManager
    private lateinit var vSlamManager: VSlamManager

    // ViewModel partagé avec MapFragment via activityViewModels()
    private val mapViewModel: MapViewModel by viewModels()

    private var prevFootX = 0f
    private var prevFootY = 0f
    private var prevFootZ = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // On charge le layout qui contient UNIQUEMENT le NavHostFragment
        setContentView(R.layout.activity_main)

        // Request Activity Recognition permission if needed on Android 10+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACTIVITY_RECOGNITION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION),
                    1001
                )
            }
        }
    }

        // Initialisation du moteur FootSLAM (Lionel)
        footSlamManager = FootSlamManager.getInstance(this)
        footSlamManager.initModel(assets, "ronin_model.tflite")
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
}
