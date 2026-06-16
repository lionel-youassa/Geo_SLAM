package com.example.geo_slam.ui.map

import android.app.Application
import android.graphics.PointF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.geo_slam.footslam.FootSlamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MapUiState(
    val displayMode: DisplayMode    = DisplayMode.FOOT_SLAM,
    val footSlamPath: List<PointF>  = emptyList(),
    val avatarPosition: PointF?     = null,
    val avatarHeading: Float        = 0f,
    val stepCount: Int              = 0,
    val floorPlan: FloorPlan        = FloorPlan.laboVectoriel(),
    val isRunning: Boolean          = true,
    val countdown: Int?             = null // null means ready
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val footSlamManager = FootSlamManager.getInstance(application)
    
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        // Synchronisation du plan avec le moteur natif
        footSlamManager.setFloorPlan(_uiState.value.floorPlan)
        
        startCalibrationCountdown()
        startRealLocalization()
        observeHeading()
        observeStepCount()
    }

    private fun startCalibrationCountdown() = viewModelScope.launch {
        for (i in 3 downTo 1) {
            _uiState.update { it.copy(countdown = i) }
            delay(1000)
        }
        _uiState.update { it.copy(countdown = null) }
    }

    fun setInitialPosition(x: Float, y: Float) {
        footSlamManager.setInitialPosition(x, y)
        _uiState.update { it.copy(footSlamPath = listOf(PointF(x, y)), avatarPosition = PointF(x, y)) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _uiState.update { it.copy(displayMode = mode) }
    }

    fun stopLocalization() {
        _uiState.update { it.copy(isRunning = false) }
        footSlamManager.stopAcquisition()
    }

    private fun startRealLocalization() = viewModelScope.launch {
        footSlamManager.positionFlow.collect { realPos ->
            _uiState.update { state ->
                val lastPos = state.footSlamPath.lastOrNull()
                if (lastPos == null || lastPos.x != realPos.x || lastPos.y != realPos.y) {
                    state.copy(
                        footSlamPath = state.footSlamPath + realPos,
                        avatarPosition = realPos
                    )
                } else {
                    state.copy(avatarPosition = realPos)
                }
            }
        }
    }

    private fun observeHeading() = viewModelScope.launch {
        footSlamManager.headingFlow.collect { heading ->
            _uiState.update { it.copy(avatarHeading = heading) }
        }
    }

    private fun observeStepCount() = viewModelScope.launch {
        footSlamManager.stepCountFlow.collect { count ->
            _uiState.update { it.copy(stepCount = count) }
        }
    }
}
