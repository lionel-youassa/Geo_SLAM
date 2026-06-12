// com/example/geo_slam/ui/status/StatusViewModel.kt
package com.example.geo_slam.ui.status

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.geo_slam.R
import com.example.geo_slam.vslam.VSlamManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ComponentStatus { OK, LOADING, ERROR, UNAVAILABLE }

data class SensorRowState(
    val id: String,
    val name: String,
    val detail: String,
    val status: ComponentStatus,
    val iconRes: Int
)

data class SystemMetrics(
    val batteryPercent: Int = 0,
    val ramFreeMb: Int = 0,
    val cpuTempCelsius: Float = 0f
)

data class StatusUiState(
    val sensors: List<SensorRowState> = emptyList(),
    val metrics: SystemMetrics = SystemMetrics(),
    val canStart: Boolean = false,
    val lastRefreshLabel: String = ""
)

class StatusViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(StatusUiState())
    val uiState: StateFlow<StatusUiState> = _uiState.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() = viewModelScope.launch {
        while (isActive) {
            refresh()
            delay(2_000L)
        }
    }

    fun refresh() {
        val context = getApplication<Application>()

        val imuState    = checkImu()
        val cameraState = checkCamera()
        val aiState     = checkAiModel()
        val slamState   = checkSlam()

        val sensors = listOf(imuState, cameraState, aiState, slamState)

        _uiState.value = StatusUiState(
            sensors        = sensors,
            metrics        = readSystemMetrics(context),
            canStart       = sensors.filter { it.id != "slam" }.all { it.status == ComponentStatus.OK },
            lastRefreshLabel = "Dernière vérif. à l'instant"
        )
    }

    // --- Vérifications capteurs ---
    // TODO Lionel : remplacer par la vraie vérification SensorManager

    private fun checkImu() = SensorRowState(
        id       = "imu",
        name     = "IMU",
        detail   = "100 Hz · Accél + Gyro",
        status   = ComponentStatus.OK,
        iconRes  = R.drawable.ic_imu
    )

    private fun checkCamera() = SensorRowState(
        id       = "camera",
        name     = "Caméra",
        detail   = "30 fps · Camera2 API",
        status   = ComponentStatus.OK,
        iconRes  = R.drawable.ic_camera
    )

    // TODO Lionel : remplacer par TFLiteInterpreter.isInitialized
    private fun checkAiModel() = SensorRowState(
        id       = "ai",
        name     = "Modèle IA (TFLite)",
        detail   = "RoNIN · GPU delegate",
        status   = ComponentStatus.OK,
        iconRes  = R.drawable.ic_cpu
    )

    private fun checkSlam(): SensorRowState {
        val stateStr = VSlamManager.currentTrackingState
        val (detail, status) = when (stateStr) {
            "TRACKING"      -> Pair("Tracking actif",      ComponentStatus.OK)
            "RECENTLY_LOST" -> Pair("Tracking instable",   ComponentStatus.LOADING)
            "INITIALIZING"  -> Pair("Initialisation...",   ComponentStatus.LOADING)
            "LOST"          -> Pair("Tracking perdu",      ComponentStatus.ERROR)
            else            -> Pair("En attente caméra",   ComponentStatus.LOADING)
        }
        return SensorRowState(
            id      = "slam",
            name    = "ORB-SLAM3",
            detail  = detail,
            status  = status,
            iconRes = R.drawable.ic_map
        )
    }

    // --- Métriques système ---

    private fun readSystemMetrics(context: Context): SystemMetrics {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val ramFree = (info.availMem / 1_048_576L).toInt()

        return SystemMetrics(
            batteryPercent  = battery,
            ramFreeMb       = ramFree,
            cpuTempCelsius  = readCpuTemp()
        )
    }

    private fun readCpuTemp(): Float {
        // Lecture directe du fichier sysfs — varie selon les constructeurs
        return try {
            val raw = java.io.File("/sys/class/thermal/thermal_zone0/temp")
                .readText().trim().toFloat()
            if (raw > 1000f) raw / 1000f else raw
        } catch (e: Exception) {
            0f
        }
    }
}