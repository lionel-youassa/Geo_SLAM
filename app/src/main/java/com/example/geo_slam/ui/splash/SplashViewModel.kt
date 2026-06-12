package com.example.geo_slam.ui.splash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SensorStatus { PENDING, LOADING, OK, ERROR }

data class SensorState(
    val label: String,
    val status: SensorStatus
)

data class SplashUiState(
    val sensors: List<SensorState> = listOf(
        SensorState("IMU (100 Hz)",        SensorStatus.PENDING),
        SensorState("Caméra",              SensorStatus.PENDING),
        SensorState("Modèle IA (TFLite)",  SensorStatus.PENDING),
    ),
    val progress: Int = 0,          // 0..100
    val navigateToStatus: Boolean = false
)

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        runInitSequence()
    }

    private fun runInitSequence() = viewModelScope.launch {

        // Étape 1 — IMU
        updateSensor(0, SensorStatus.LOADING)
        delay(600)
        updateSensor(0, SensorStatus.OK)
        setProgress(33)

        // Étape 2 — Caméra
        updateSensor(1, SensorStatus.LOADING)
        delay(800)
        updateSensor(1, SensorStatus.OK)
        setProgress(66)

        // Étape 3 — Modèle IA
        updateSensor(2, SensorStatus.LOADING)
        delay(900)
        // TODO : remplacer par le vrai chargement TFLite de Lionel
        updateSensor(2, SensorStatus.OK)
        setProgress(100)

        delay(400) // pause courte avant navigation
        _uiState.value = _uiState.value.copy(navigateToStatus = true)
    }

    private fun updateSensor(index: Int, status: SensorStatus) {
        val updated = _uiState.value.sensors.toMutableList()
        updated[index] = updated[index].copy(status = status)
        _uiState.value = _uiState.value.copy(sensors = updated)
    }

    private fun setProgress(value: Int) {
        _uiState.value = _uiState.value.copy(progress = value)
    }
}