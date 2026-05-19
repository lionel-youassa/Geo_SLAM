package com.example.geo_slam

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.geo_slam.databinding.ActivityMainBinding
import com.example.geo_slam.footslam.FootSlamManager
import java.util.Locale

class MainActivity : AppCompatActivity(), FootSlamManager.OnPositionUpdateListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var footSlamManager: FootSlamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lionel : Initialisation du module FootSLAM (Lead IA)
        footSlamManager = FootSlamManager(this)
        footSlamManager.setOnPositionUpdateListener(this)

        // Chargement du modèle RoNIN
        val modelLoaded = footSlamManager.initModel(assets, "ronin_model.tflite")
        
        if (modelLoaded) {
            binding.sampleText.text = "Geo-SLAM : Mode Test Temps Réel"
        } else {
            binding.sampleText.text = "Erreur : ronin_model.tflite introuvable"
            Toast.makeText(this, "Lionel, vérifie le dossier assets !", Toast.LENGTH_LONG).show()
        }

        // Action du bouton Reset (Semaine 2)
        binding.btnReset.setOnClickListener {
            footSlamManager.reset()
            binding.trajectoryView.clear() // Efface le tracé visuel
            updateUI(0f, 0f, 0f)
            Toast.makeText(this, "Trajectoire réinitialisée", Toast.LENGTH_SHORT).show()
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
     * Maintenant avec la gestion de l'altitude Z.
     */
    override fun onPositionUpdate(x: Float, y: Float, z: Float) {
        runOnUiThread {
            updateUI(x, y, z)
        }
    }

    private fun updateUI(x: Float, y: Float, z: Float) {
        // Mise à jour des coordonnées textuelles
        binding.tvPosX.text = String.format(Locale.US, "Position X : %.2f m", x)
        binding.tvPosY.text = String.format(Locale.US, "Position Y : %.2f m", y)
        binding.tvPosZ.text = String.format(Locale.US, "Altitude Z : %.2f m", z)
        
        // Mise à jour visuelle du tracé (Lionel Test View)
        binding.trajectoryView.updatePosition(x, y)
    }
}