package com.example.geo_slam.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.geo_slam.R
import com.example.geo_slam.databinding.FragmentMapBinding
import kotlinx.coroutines.launch

/**
 * MapFragment : Interface finale synchronisée.
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels()

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
            avatarPosition = state.avatarPosition
            avatarHeading = state.avatarHeading
            stepCount = state.stepCount
            displayMode = state.displayMode
        }

        val overlay = view?.findViewById<View>(R.id.calibrationOverlay)
        val txtCountdown = view?.findViewById<TextView>(R.id.txtCountdown)

        if (state.countdown != null) {
            overlay?.visibility = View.VISIBLE
            txtCountdown?.text = state.countdown.toString()
        } else {
            overlay?.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
