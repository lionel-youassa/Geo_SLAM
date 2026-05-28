// com/example/geo_slam/ui/map/MapFragment.kt
package com.example.geo_slam.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.geo_slam.R
import com.example.geo_slam.databinding.FragmentMapBinding
import kotlinx.coroutines.launch

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
        setupModeDropdown()
        setupButtons()
        observeState()
    }

    private fun setupModeDropdown() {
        // btnModeDropdown n'existe qu'en landscape — on vérifie avant d'utiliser
        binding.btnModeDropdown?.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add(0, R.id.menuFootSlam, 0, getString(R.string.mode_foot))
            popup.menu.add(0, R.id.menuFusion,   1, getString(R.string.mode_fusion))
            popup.menu.add(0, R.id.menuVSlam,    2, getString(R.string.mode_vslam))

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menuFootSlam -> {
                        viewModel.setDisplayMode(DisplayMode.FOOT_SLAM)
                        binding.btnModeDropdown?.text = getString(R.string.mode_foot)
                        true
                    }
                    R.id.menuFusion -> {
                        viewModel.setDisplayMode(DisplayMode.FUSION)
                        binding.btnModeDropdown?.text = getString(R.string.mode_fusion)
                        true
                    }
                    R.id.menuVSlam -> {
                        viewModel.setDisplayMode(DisplayMode.VSLAM)
                        binding.btnModeDropdown?.text = getString(R.string.mode_vslam)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // btnModeFootSlam/Fusion/VSlam n'existent qu'en portrait
        binding.btnModeFootSlam?.setOnClickListener {
            viewModel.setDisplayMode(DisplayMode.FOOT_SLAM)
        }
        binding.btnModeFusion?.setOnClickListener {
            viewModel.setDisplayMode(DisplayMode.FUSION)
        }
        binding.btnModeVSlam?.setOnClickListener {
            viewModel.setDisplayMode(DisplayMode.VSLAM)
        }
    }

    private fun setupButtons() {
        binding.btnStop.setOnClickListener {
            viewModel.stopLocalization()
            findNavController().popBackStack()
        }
        binding.btnSave.setOnClickListener {
            // TODO : sauvegarder la session
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: MapUiState) {
        binding.mapCanvasView.apply {
            floorPlan      = state.floorPlan
            footSlamPath   = state.footSlamPath
            //vslamPath      = state.vslamPath
            avatarPosition = state.avatarPosition
            avatarHeading  = state.avatarHeading
            displayMode    = state.displayMode
        }

        // Dropdown (landscape)
        binding.btnModeDropdown?.text = when (state.displayMode) {
            DisplayMode.FOOT_SLAM -> getString(R.string.mode_foot)
            DisplayMode.FUSION    -> getString(R.string.mode_fusion)
            DisplayMode.VSLAM     -> getString(R.string.mode_vslam)
        }

        // Boutons portrait — met à jour l'apparence du bouton actif
        updatePortraitButtons(state.displayMode)
    }

    private fun updatePortraitButtons(mode: DisplayMode) {
        val ctx = requireContext()

        listOf(
            binding.btnModeFootSlam to DisplayMode.FOOT_SLAM,
            binding.btnModeFusion   to DisplayMode.FUSION,
            binding.btnModeVSlam    to DisplayMode.VSLAM
        ).forEach { (btn, btnMode) ->
            btn?.isSelected = (mode == btnMode)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}