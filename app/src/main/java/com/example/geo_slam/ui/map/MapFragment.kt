package com.example.geo_slam.ui.map

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.geo_slam.R
import com.example.geo_slam.databinding.FragmentMapBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * MapFragment : Interface synchronisée avec sélecteur de mode.
 * Affiche les trajectoires et les statuts des moteurs.
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by activityViewModels()

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

        // Sélecteurs de mode avec indicateur visuel (Toast)
        binding.btnFootSlam?.setOnClickListener { 
            viewModel.setDisplayMode(DisplayMode.FOOT_SLAM)
            Toast.makeText(context, "Mode FootSLAM activé (IA)", Toast.LENGTH_SHORT).show()
        }
        binding.btnFusion?.setOnClickListener { 
            viewModel.setDisplayMode(DisplayMode.FUSION)
            Toast.makeText(context, "Mode Fusion activé (IA + Caméra)", Toast.LENGTH_SHORT).show()
        }
        binding.btnVSlam?.setOnClickListener { 
            viewModel.setDisplayMode(DisplayMode.VSLAM)
            Toast.makeText(context, "Mode vSLAM activé (Caméra)", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopLocalization()
            activity?.finish()
        }
    }

    private fun render(state: MapUiState) {
        binding.mapCanvasView.apply {
            floorPlan = state.floorPlan
            footSlamPath = state.footSlamPath
            vSlamPath = state.vSlamPath
            avatarPosition = state.avatarPosition
            avatarHeading = state.avatarHeading
            stepCount = state.stepCount
            displayMode = state.displayMode
            vSlamStatus = state.vSlamStatus // Liaison du statut vSLAM à la vue
        }

        updateButtonStyles(state.displayMode)

        if (state.countdown != null) {
            binding.calibrationOverlay.visibility = View.VISIBLE
            binding.txtCountdown.text = state.countdown.toString()
        } else {
            binding.calibrationOverlay.visibility = View.GONE
        }
    }

    private fun updateButtonStyles(activeMode: DisplayMode) {
        val activeColor = Color.parseColor("#26A69A")
        val inactiveColor = Color.parseColor("#757575")

        fun setButtonStyle(button: MaterialButton?, isActive: Boolean) {
            button?.let { btn ->
                if (isActive) {
                    btn.setTextColor(Color.WHITE)
                    btn.backgroundTintList = ColorStateList.valueOf(activeColor)
                    btn.strokeColor = ColorStateList.valueOf(activeColor)
                } else {
                    btn.setTextColor(inactiveColor)
                    btn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3F4F6"))
                    btn.strokeColor = ColorStateList.valueOf(Color.parseColor("#E0E0E0"))
                }
            }
        }

        setButtonStyle(binding.btnFootSlam, activeMode == DisplayMode.FOOT_SLAM)
        setButtonStyle(binding.btnFusion, activeMode == DisplayMode.FUSION)
        setButtonStyle(binding.btnVSlam, activeMode == DisplayMode.VSLAM)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
