package com.example.geo_slam.ui.status

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
import com.example.geo_slam.R
import com.example.geo_slam.databinding.FragmentStatusBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StatusViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStart.setOnClickListener {
            findNavController().navigate(R.id.action_statusFragment_to_mapFragment)
        }

        // binding.btnCalibrate.setOnClickListener {
            // findNavController().navigate(R.id.action_statusFragment_to_calibrationSheet)
        //}

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: StatusUiState) {
        binding.lastRefreshText.text = state.lastRefreshLabel
        binding.btnStart.isEnabled   = state.canStart

        // Métriques
        binding.batteryValue.text = "${state.metrics.batteryPercent}%"
        binding.ramValue.text     = "${state.metrics.ramFreeMb} MB"
        binding.tempValue.text    = "${"%.0f".format(state.metrics.cpuTempCelsius)}°C"

        // Capteurs — gonfle les items si nécessaire, met à jour ensuite
        if (binding.sensorList.childCount != state.sensors.size) {
            binding.sensorList.removeAllViews()
            state.sensors.forEach { _ ->
                layoutInflater.inflate(R.layout.item_sensor_row, binding.sensorList, true)
            }
        }
        state.sensors.forEachIndexed { i, sensor ->
            val row = binding.sensorList.getChildAt(i) ?: return@forEachIndexed
            row.findViewById<ImageView>(R.id.sensorIcon).setImageResource(sensor.iconRes)
            row.findViewById<TextView>(R.id.sensorName).text   = sensor.name
            row.findViewById<TextView>(R.id.sensorDetail).text = sensor.detail
            row.findViewById<Chip>(R.id.statusChip).apply {
                val (label, colorRes) = when (sensor.status) {
                    ComponentStatus.OK          -> "Actif"        to R.color.status_ok
                    ComponentStatus.LOADING     -> "Chargement…"  to R.color.status_loading
                    ComponentStatus.ERROR       -> "Erreur"       to R.color.status_error
                    ComponentStatus.UNAVAILABLE -> "Non initialisé" to R.color.status_pending
                }
                text = label
                chipBackgroundColor = ContextCompat.getColorStateList(context, colorRes)
                    ?.withAlpha(30)
                setTextColor(ContextCompat.getColor(context, colorRes))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}