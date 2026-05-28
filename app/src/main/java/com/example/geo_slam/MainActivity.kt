package com.example.geo_slam

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.geo_slam.databinding.ActivityMainBinding
import com.example.geo_slam.footslam.FootSlamManager
import java.util.Locale

class MainActivity : AppCompatActivity(), FootSlamManager.OnPositionUpdateListener {

    private lateinit var footSlamManager: FootSlamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Lionel : Initialisation du module FootSLAM (Lead IA)
        footSlamManager = FootSlamManager(this)
        footSlamManager.setOnPositionUpdateListener(this)

        // Chargement du modèle RoNIN
        val modelLoaded = footSlamManager.initModel(assets, "ronin_model.tflite")
        
        if (!modelLoaded) {
            Toast.makeText(this, "Erreur : ronin_model.tflite introuvable", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        footSlamManager.startAcquisition()
    }

    override fun onPause() {
        super.onPause()
        footSlamManager.stopAcquisition()
    }

    /**
     * Callback déclenché par le C++ (Lionel) pour mettre à jour l'UI (Sonia)
     */
    override fun onPositionUpdate(x: Float, y: Float, z: Float) {
        // TODO: Mettre à jour l'UI dans le bon Fragment
    }
}