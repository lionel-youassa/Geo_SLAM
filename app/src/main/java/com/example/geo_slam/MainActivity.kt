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
            binding.sampleText.text = "Geo-SLAM : Moteur IA Opérationnel"
        } else {
            binding.sampleText.text = "Erreur : ronin_model.tflite introuvable"
            Toast.makeText(this, "Lionel, vérifie le dossier assets !", Toast.LENGTH_LONG).show()
        }

        // Action du bouton Reset (Semaine 2)
        binding.btnReset.setOnClickListener {
            footSlamManager.reset()
            updateUI(0f, 0f)
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
     */
    override fun onPositionUpdate(x: Float, y: Float, z: Float) {
        runOnUiThread {
            updateUI(x, y)
        }
    }

    private fun updateUI(x: Float, y: Float) {
        binding.tvPosX.text = String.format(Locale.US, "Position X : %.2f m", x)
        binding.tvPosY.text = String.format(Locale.US, "Position Y : %.2f m", y)
    }
}