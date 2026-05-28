// com/example/geo_slam/ui/map/MapViewModel.kt
package com.example.geo_slam.ui.map

import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class MapUiState(
    val displayMode: DisplayMode    = DisplayMode.FUSION,
    val footSlamPath: List<PointF>  = emptyList(),
    val vslamPath: List<PointF>     = emptyList(),
    val avatarPosition: PointF?     = null,
    val avatarHeading: Float        = 0f,
    val floorPlan: FloorPlan        = FloorPlan.laboVectoriel(),
    val isRunning: Boolean          = true
)

class MapViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        startFakeLocalization()
    }

    // ---- Mode d'affichage ----
    fun setDisplayMode(mode: DisplayMode) {
        _uiState.update { it.copy(displayMode = mode) }
    }

    // ---- Stop ----
    fun stopLocalization() {
        _uiState.update { it.copy(isRunning = false) }
    }

    // ---- Simulation — remplacer par les vrais flows de Lionel et Narcisse ----
    // TODO Lionel  : remplacer startFakeLocalization() par positionFlow de PositionProvider
    // TODO Narcisse: brancher le vslamPath depuis MapDataProvider.newPointsFlow
    private fun startFakeLocalization() = viewModelScope.launch {
        var t = 0f
        val footPath = mutableListOf<PointF>()
        val vslamPath = mutableListOf<PointF>()

        while (isActive && _uiState.value.isRunning) {
            // Trajectoire simulée en forme de courbe dans le labo
            val x = 1f + 5f * (t / 30f)
            val y = 4f + 3f * sin(t * 0.3f)

            // FootSLAM — légèrement bruité
            footPath.add(PointF(x, y))

            // vSLAM — légèrement décalé (simule la différence entre les deux)
            val nx = x + 0.05f * cos(t * 2f)
            val ny = y + 0.05f * sin(t * 2f)
            vslamPath.add(PointF(nx, ny))

            _uiState.update { state ->
                state.copy(
                    footSlamPath   = footPath.toList(),
                    vslamPath      = vslamPath.toList(),
                    avatarPosition = PointF(x, y),
                    avatarHeading  = (t * 6f) % 360f
                )
            }

            t += 0.5f
            delay(200L)   // mise à jour 5 fois par seconde
        }
    }

    // Exemple d'implémentation dans MapViewModel.kt
//    private fun startRealLocalization() = viewModelScope.launch {
//        // On écoute les vraies données (ex: Flow de PointF)
//        footSlamProvider.positionFlow.collect { realPos ->
//            _uiState.update { state ->
//                // En fonction du mode, vous décidez ce qui s'affiche
//                val finalPos = when (state.displayMode) {
//                    DisplayMode.FOOT_SLAM -> realPos
//                    DisplayMode.VSLAM -> vslamProvider.currentPos
//                    DisplayMode.FUSION -> fusePositions(realPos, vslamProvider.currentPos)
//                }
//
//                state.copy(
//                    footSlamPath = state.footSlamPath + realPos, // Ajoute au tracé
//                    avatarPosition = finalPos, // Fait bouger l'avatar
//                    avatarHeading = footSlamProvider.currentHeading
//                )
//            }
//        }
//    }
}