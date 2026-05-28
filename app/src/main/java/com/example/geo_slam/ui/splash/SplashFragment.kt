package com.example.geo_slam.ui.splash

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.geo_slam.BuildConfig
import com.example.geo_slam.R
import com.example.geo_slam.databinding.FragmentSplashBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class SplashFragment : Fragment() {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SplashViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.versionText.text = "v${BuildConfig.VERSION_NAME} — build ${BuildConfig.BUILD_TYPE}"

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderSensors(state.sensors)
                    binding.progressBar.setProgressCompat(state.progress, true)
                    if (state.navigateToStatus) {
                        findNavController().navigate(
                            R.id.action_splashFragment_to_statusFragment
                        )
                    }
                }
            }
        }
    }

    private fun renderSensors(sensors: List<SensorState>) {

        // Gonfle les items une seule fois
        if (binding.statusList.childCount == 0) {
            sensors.forEach { _ ->
                layoutInflater.inflate(
                    R.layout.item_sensor_status, binding.statusList, true
                )
            }
        }

        sensors.forEachIndexed { i, sensor ->
            val itemView = binding.statusList.getChildAt(i) ?: return@forEachIndexed

            // Icône
            val iconRes = when (sensor.label) {
                "IMU (100 Hz)"        -> R.drawable.ic_imu
                "Caméra"              -> R.drawable.ic_camera
                "Modèle IA (TFLite)"  -> R.drawable.ic_cpu
                else                  -> R.drawable.ic_geo_slam_logo
            }
            itemView.findViewById<ImageView>(R.id.sensorIcon)
                ?.setImageResource(iconRes)

            // Nom
            itemView.findViewById<TextView>(R.id.statusLabel)
                ?.text = sensor.label

            // Détail selon statut
            itemView.findViewById<TextView>(R.id.statusDetail)
                ?.text = when (sensor.status) {
                SensorStatus.OK      -> "Opérationnel"
                SensorStatus.LOADING -> "Initialisation…"
                SensorStatus.ERROR   -> "Erreur détectée"
                SensorStatus.PENDING -> "En attente"
            }

            // Chip
            val chip = itemView.findViewById<Chip>(R.id.statusChip)
            val (labelStr, colorRes) = when (sensor.status) {
                SensorStatus.OK      -> "OK"           to R.color.status_ok
                SensorStatus.LOADING -> "En cours…"    to R.color.status_loading
                SensorStatus.ERROR   -> "Erreur"       to R.color.status_error
                SensorStatus.PENDING -> "En attente"   to R.color.status_pending
            }
            chip?.text = labelStr
            chip?.chipBackgroundColor = ContextCompat.getColorStateList(
                requireContext(), colorRes
            )?.withAlpha(30)
            chip?.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}