package com.example.geo_slam

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.geo_slam.databinding.ActivityMainBinding
import com.example.geo_slam.footslam.FootSlamManager

class MainActivity : AppCompatActivity(), FootSlamManager.OnPositionUpdateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var footSlamManager: FootSlamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lionel : Initialisation de ton module
        footSlamManager = FootSlamManager(this)
        footSlamManager.setOnPositionUpdateListener(this)

        val modelLoaded = footSlamManager.initModel(assets, "ronin_model.tflite")
        
        if (modelLoaded) {
            binding.sampleText.text = "Geo-SLAM : Prêt (IA Chargée)"
        } else {
            binding.sampleText.text = "Erreur : ronin_model.tflite absent"
            Toast.makeText(this, "Lionel, place le modèle dans assets !", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        footSlamManager.startAcquisition() // Lionel : Tu lances la capture à 100Hz
    }

    override fun onPause() {
        super.onPause()
        footSlamManager.stopAcquisition()
    }

    /**
     * Sonia : C'est ici que tu reçois la position envoyée par Lionel
     * pour mettre à jour ton interface 3D.
     */
    override fun onPositionUpdate(x: Float, y: Float, z: Float) {
        runOnUiThread {
            // Exemple : Sonia met à jour un TextView ou son moteur 3D
            Log.d("GeoSlam_UI", "Nouvelle position reçue de Lionel : $x, $y, $z")
        }
    }
}