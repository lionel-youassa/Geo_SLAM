package com.example.geo_slam.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.geo_slam.databinding.FragmentMapBinding
import kotlinx.coroutines.launch

/**
 * MapFragment : Interface synchronisée avec sélecteur de mode via un Spinner.
 * Utilise un flag pour éviter les boucles infinies entre le ViewModel et la Vue.
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by activityViewModels()
    
    // Flag pour savoir si le changement vient du code (render) ou de l'utilisateur
    private var isProgrammaticSelection = false
    private var lastRenderedMode: DisplayMode? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.mapCanvasView.onInitialPositionSelected = { x, y ->
            viewModel.setInitialPosition(x, y)
            Toast.makeText(context, "Position synchronisée !", Toast.LENGTH_SHORT).show()
        }

        setupModeSpinner()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopLocalization()
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun setupModeSpinner() {
        val modes = listOf("FootSLAM (IA)", "Fusion (Hybride)", "vSLAM (Vision)")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerMode.adapter = adapter
        
        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Si la sélection vient du render(), on ne fait rien
                if (isProgrammaticSelection) return

                val mode = when(position) {
                    0 -> DisplayMode.FOOT_SLAM
                    1 -> DisplayMode.FUSION
                    else -> DisplayMode.VSLAM
                }

                if (viewModel.uiState.value.displayMode != mode) {
                    viewModel.setDisplayMode(mode)
                    val modeName = modes[position]
                    Toast.makeText(context, "Mode $modeName activé", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun render(state: MapUiState) {
        binding.mapCanvasView.apply {
            floorPlan = state.floorPlan
            footSlamPath = state.footSlamPath
            vSlamPath = state.vSlamPath
            fusionPath = state.fusionPath
            avatarPosition = state.avatarPosition
            avatarHeading = state.avatarHeading
            stepCount = state.stepCount
            displayMode = state.displayMode
            vSlamStatus = state.vSlamStatus
        }

        // Synchronisation du Spinner uniquement si le mode a changé dans le ViewModel
        if (state.displayMode != lastRenderedMode) {
            lastRenderedMode = state.displayMode
            val targetPos = when(state.displayMode) {
                DisplayMode.FOOT_SLAM -> 0
                DisplayMode.FUSION -> 1
                DisplayMode.VSLAM -> 2
            }
            isProgrammaticSelection = true
            binding.spinnerMode.setSelection(targetPos, false)
            binding.spinnerMode.post { isProgrammaticSelection = false }
        }

        if (state.countdown != null) {
            binding.calibrationOverlay.visibility = View.VISIBLE
            binding.txtCountdown.text = state.countdown.toString()
        } else {
            binding.calibrationOverlay.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
