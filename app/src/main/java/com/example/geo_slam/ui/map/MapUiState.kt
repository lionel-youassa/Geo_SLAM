package com.example.geo_slam.ui.map

import android.graphics.PointF

data class MapUiState(
    val floorPlan: FloorPlan = FloorPlan.laboVectoriel(),
    val footSlamPath: List<PointF> = emptyList(),
    val vSlamPath: List<PointF> = emptyList(),
    val avatarPosition: PointF? = null,
    val avatarHeading: Float = 0f,
    val stepCount: Int = 0,
    val displayMode: DisplayMode = DisplayMode.FOOT_SLAM,
    val vSlamStatus: String = "INACTIVE",
    val countdown: Int? = null,
    val isRunning: Boolean = true
)
