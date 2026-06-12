package com.example.geo_slam.ui.map

import android.graphics.PointF
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.*

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

    // Dernier point FootSLAM connu — pour calcul de cap
    private var lastFootX = 0f
    private var lastFootY = 0f

    // ── Injection depuis MainActivity (callbacks FootSLAM et vSLAM) ──────────

    fun injectFootSlamPosition(x: Float, y: Float, z: Float) {
        val dx = x - lastFootX
        val dy = y - lastFootY
        val heading = if (dx * dx + dy * dy > 1e-6f)
            Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        else
            _uiState.value.avatarHeading
        lastFootX = x
        lastFootY = y

        _uiState.update { state ->
            val footPath = state.footSlamPath + PointF(x, y)
            val avatar = when (state.displayMode) {
                DisplayMode.FOOT_SLAM -> PointF(x, y)
                DisplayMode.FUSION    -> PointF(x, y)
                DisplayMode.VSLAM     -> state.avatarPosition
            }
            state.copy(
                footSlamPath   = footPath,
                avatarPosition = avatar,
                avatarHeading  = heading
            )
        }
    }

    fun injectVslamPosition(x: Float, y: Float, z: Float) {
        _uiState.update { state ->
            val vslamPath = state.vslamPath + PointF(x, y)
            val avatar = if (state.displayMode == DisplayMode.VSLAM) PointF(x, y)
                         else state.avatarPosition
            state.copy(
                vslamPath      = vslamPath,
                avatarPosition = avatar
            )
        }
    }

    // ── Mode d'affichage ─────────────────────────────────────────────────────

    fun setDisplayMode(mode: DisplayMode) {
        _uiState.update { it.copy(displayMode = mode) }
    }

    // ── Stop / Reset ─────────────────────────────────────────────────────────

    fun stopLocalization() {
        _uiState.update { it.copy(isRunning = false) }
    }

    fun reset() {
        lastFootX = 0f
        lastFootY = 0f
        _uiState.value = MapUiState()
    }
}
