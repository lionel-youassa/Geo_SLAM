package com.example.geo_slam.ui.map

import android.app.Application
import android.graphics.PointF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.geo_slam.footslam.FootSlamManager
import com.example.geo_slam.vslam.VSlamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val footSlamManager = FootSlamManager.getInstance(application)
    private val vSlamManager = VSlamManager.getInstance(application)
    
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private var initialAnchor: PointF? = null
    private var lastFootPosForScale: PointF? = null
    private var smoothedPos: PointF? = null
    
    // Position accumulée pour le vSLAM (calculée à partir des deltas)
    private var vSlamAccumulatedPos: PointF? = null
    private var fusionAccumulatedPos: PointF? = null
    
    // Orientation au moment du reset pour aligner le vSLAM avec la carte
    private var initialHeading: Float = 0f
    
    private val stabilityCounter = AtomicInteger(0)
    private var isFirstFrameAfterStabilization = false

    init {
        footSlamManager.setFloorPlan(_uiState.value.floorPlan)
        startCalibrationCountdown()
        observeFootSlam()
        observeHeading()
        observeStepCount()
        pollVSlamStatus()

        vSlamManager.setOnFrameProcessedListener(object : VSlamManager.OnFrameProcessedListener {
            override fun onFrameProcessed(dx: Float, dy: Float, dz: Float) {
                if (stabilityCounter.get() > 0) {
                    stabilityCounter.decrementAndGet()
                    isFirstFrameAfterStabilization = true
                    return
                }

                val anchor = initialAnchor ?: return
                if (vSlamAccumulatedPos == null) vSlamAccumulatedPos = PointF(anchor.x, anchor.y)
                if (fusionAccumulatedPos == null) fusionAccumulatedPos = PointF(anchor.x, anchor.y)

                val currentHeading = _uiState.value.avatarHeading
                val angleRad = Math.toRadians(currentHeading.toDouble())
                val cosH = cos(angleRad).toFloat()
                val sinH = sin(angleRad).toFloat()
                
                // Rotation du pas relatif vSLAM : dz=avant/arrière, dx=gauche/droite
                val stepX = dx * cosH + dz * sinH
                val stepY = -dx * sinH + dz * cosH
                
                // 1. Mise à jour vSLAM pur
                vSlamAccumulatedPos = constrainToPerimeter(PointF(vSlamAccumulatedPos!!.x + stepX, vSlamAccumulatedPos!!.y + stepY))

                // 2. Mise à jour FUSION (On utilise le vSLAM pour la fluidité)
                if (_uiState.value.vSlamStatus == "TRACKING") {
                    fusionAccumulatedPos = constrainToPerimeter(PointF(fusionAccumulatedPos!!.x + stepX, fusionAccumulatedPos!!.y + stepY))
                }

                _uiState.update { state ->
                    val mode = state.displayMode
                    if (mode == DisplayMode.FOOT_SLAM) return@update state
                    
                    val activePos = if (mode == DisplayMode.FUSION) fusionAccumulatedPos!! else vSlamAccumulatedPos!!
                    
                    val currentPath = if (mode == DisplayMode.FUSION) state.fusionPath.toMutableList() else state.vSlamPath.toMutableList()
                    val last = currentPath.lastOrNull()
                    
                    if (isFirstFrameAfterStabilization) {
                        isFirstFrameAfterStabilization = false
                        currentPath.add(activePos)
                        return@update if (mode == DisplayMode.FUSION) state.copy(fusionPath = currentPath, avatarPosition = activePos)
                                      else state.copy(vSlamPath = currentPath, avatarPosition = activePos)
                    }

                    val updatedPath = if (last == null || dist(last, activePos) > 0.05f) {
                        currentPath + activePos
                    } else {
                        currentPath
                    }

                    if (mode == DisplayMode.FUSION) {
                        state.copy(fusionPath = updatedPath, avatarPosition = activePos)
                    } else {
                        state.copy(vSlamPath = updatedPath, avatarPosition = activePos)
                    }
                }
            }
        })
    }

    private fun dist(p1: PointF, p2: PointF) = sqrt(((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y)).toDouble()).toFloat()

    fun setInitialPosition(x: Float, y: Float) {
        val startPos = PointF(x, y)
        initialAnchor = startPos
        lastFootPosForScale = startPos
        smoothedPos = startPos
        vSlamAccumulatedPos = startPos
        fusionAccumulatedPos = startPos
        initialHeading = _uiState.value.avatarHeading
        
        stabilityCounter.set(15)
        
        footSlamManager.setInitialPosition(x, y)
        vSlamManager.reset() 
        
        _uiState.update { it.copy(
            footSlamPath = listOf(startPos),
            vSlamPath = listOf(startPos),
            fusionPath = listOf(startPos),
            avatarPosition = startPos,
            countdown = null
        ) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        val oldMode = _uiState.value.displayMode
        _uiState.update { it.copy(displayMode = mode) }
        
        if (mode != DisplayMode.FOOT_SLAM && oldMode == DisplayMode.FOOT_SLAM) {
            stabilityCounter.set(15)
            vSlamManager.startCamera()
        } else if (mode == DisplayMode.FOOT_SLAM) {
            vSlamManager.stopCamera()
        }
    }

    private fun pollVSlamStatus() = viewModelScope.launch {
        while (true) {
            if (_uiState.value.displayMode != DisplayMode.FOOT_SLAM) {
                _uiState.update { it.copy(vSlamStatus = vSlamManager.getTrackingStatus().name) }
            }
            delay(500)
        }
    }

    private fun observeFootSlam() = viewModelScope.launch {
        footSlamManager.positionFlow.collect { realPos ->
            if (realPos.x == 0f && realPos.y == 0f && initialAnchor != null) return@collect
            
            lastFootPosForScale?.let { last ->
                vSlamManager.updateScale(realPos.x - last.x, realPos.y - last.y, 0f)
            }
            lastFootPosForScale = realPos
            
            val constrainedPos = constrainToPerimeter(realPos)

            _uiState.update { state ->
                // LOGIQUE DE FUSION : Le FootSLAM recadre la fusion si le vSLAM dérive ou est perdu
                if (state.displayMode == DisplayMode.FUSION) {
                    val currentFusion = fusionAccumulatedPos ?: constrainedPos
                    if (state.vSlamStatus != "TRACKING") {
                        fusionAccumulatedPos = constrainedPos
                    } else {
                        // Correction forte de la dérive (80% FootSLAM / 20% vSLAM)
                        // On donne la priorité au FootSLAM pour la vérité de terrain
                        fusionAccumulatedPos = PointF(
                            currentFusion.x * 0.2f + constrainedPos.x * 0.8f,
                            currentFusion.y * 0.2f + constrainedPos.y * 0.8f
                        )
                    }
                }

                if (state.displayMode == DisplayMode.VSLAM) return@update state
                
                val currentPath = state.footSlamPath.toMutableList()
                val last = currentPath.lastOrNull()
                if (last == null || dist(last, constrainedPos) > 0.1f) currentPath.add(constrainedPos)
                
                state.copy(
                    footSlamPath = currentPath, 
                    avatarPosition = if (state.displayMode == DisplayMode.FUSION) fusionAccumulatedPos ?: constrainedPos 
                                     else if (state.displayMode == DisplayMode.FOOT_SLAM) constrainedPos 
                                     else state.avatarPosition
                )
            }
        }
    }

    private fun startCalibrationCountdown() = viewModelScope.launch {
        for (i in 3 downTo 1) {
            _uiState.update { it.copy(countdown = i) }
            delay(1000)
        }
        _uiState.update { it.copy(countdown = null) }
    }

    fun stopLocalization() {
        _uiState.update { it.copy(isRunning = false) }
        footSlamManager.stopAcquisition()
        vSlamManager.stopCamera()
    }

    private fun observeHeading() = viewModelScope.launch {
        footSlamManager.headingFlow.collect { heading -> _uiState.update { it.copy(avatarHeading = heading) } }
    }

    private fun observeStepCount() = viewModelScope.launch {
        footSlamManager.stepCountFlow.collect { count -> _uiState.update { it.copy(stepCount = count) } }
    }

    /**
     * Contraint une position à rester à l'intérieur du périmètre extérieur de la carte.
     */
    private fun constrainToPerimeter(pt: PointF): PointF {
        val fp = _uiState.value.floorPlan
        var mx = pt.x + fp.doorX
        var my = fp.doorY - pt.y
        
        val totalW = fp.widthInMeters
        val totalH = fp.heightInMeters
        val splitX = 12.65f
        val rightH = 9.17f
        
        // Bornes de base
        mx = mx.coerceIn(0.1f, totalW - 0.1f)
        my = my.coerceIn(0.1f, totalH - 0.1f)
        
        // Contrainte de la forme en L (cutout en bas à droite : y > 9.17 et x > 12.65)
        if (mx > splitX && my > rightH) {
             if (mx - splitX > my - rightH) {
                 my = rightH
             } else {
                 mx = splitX
             }
        }
        
        return PointF(mx - fp.doorX, fp.doorY - my)
    }
}
