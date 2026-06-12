package com.example.geo_slam.vslam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size

class VSlamManager(private val context: Context) {

    // ── États du tracker ─────────────────────────────────────────────────────

    enum class TrackingState(val value: Int) {
        INIT(0), INITIALIZING(1), TRACKING(2), RECENTLY_LOST(3), LOST(4);
        companion object {
            fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: INIT
        }
    }

    // ── Interfaces publiques ─────────────────────────────────────────────────

    interface OnFrameProcessedListener {
        fun onFrameProcessed(x: Float, y: Float, z: Float)
    }

    interface OnPointCloudListener {
        fun onPointCloudUpdated(points: FloatArray)
    }

    interface OnTrackingStateListener {
        fun onTrackingStateChanged(state: TrackingState)
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    private var frameListener: OnFrameProcessedListener? = null
    private var cloudListener: OnPointCloudListener? = null
    private var stateListener: OnTrackingStateListener? = null
    private var lastState = TrackingState.INIT

    fun setOnFrameProcessedListener(listener: OnFrameProcessedListener) {
        frameListener = listener
    }

    fun setOnPointCloudListener(listener: OnPointCloudListener) {
        cloudListener = listener
    }

    fun setOnTrackingStateListener(listener: OnTrackingStateListener) {
        stateListener = listener
    }

    // ── Camera2 ──────────────────────────────────────────────────────────────

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val targetSize = Size(640, 480)

    fun startCamera(cameraId: String? = null) {
        val resolvedId = cameraId ?: run {
            val id = getBackCameraId()
            if (id == null) {
                Log.e(TAG, "Aucune caméra arrière trouvée — vSLAM inactif.")
                return
            }
            id
        }

        val (fx, fy, cx, cy) = computeIntrinsics(resolvedId)
        Log.i(TAG, "Intrinsèques — fx=${"%.1f".format(fx)} fy=${"%.1f".format(fy)} cx=${"%.1f".format(cx)} cy=${"%.1f".format(cy)}")
        setCameraIntrinsics(fx, fy, cx, cy)

        imageReader = ImageReader.newInstance(
            targetSize.width, targetSize.height, ImageFormat.YUV_420_888, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane     = image.planes[0]
                    val rowStride = plane.rowStride
                    val buffer    = plane.buffer
                    val bufSize   = buffer.remaining()
                    if (bufSize == 0) return@setOnImageAvailableListener
                    val frameBytes = ByteArray(bufSize)
                    buffer.get(frameBytes)
                    processFrame(frameBytes, image.width, image.height, rowStride)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur traitement frame : ${e.message}")
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        try {
            cameraManager.openCamera(resolvedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null
                    Log.w(TAG, "Caméra déconnectée.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    Log.e(TAG, "Erreur caméra code=$error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission CAMERA manquante.")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur openCamera : ${e.message}")
        }
    }

    fun stopCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice = null
        imageReader?.close();    imageReader = null
        // nativeRelease() posté sur cameraHandler pour s'exécuter après tout processFrame en cours
        cameraHandler.post { nativeRelease() }
        Log.i(TAG, "Caméra arrêtée.")
    }

    // ── API publique pour l'équipe ────────────────────────────────────────────

    /**
     * Réinitialise le tracking vSLAM (pose, keyframes, trajectoire, échelle).
     * À appeler depuis MainActivity sur le bouton Reset.
     */
    fun reset() = resetTracking()

    /**
     * Transmet le déplacement FootSLAM pour calibrer l'échelle monoculaire.
     * À appeler depuis MainActivity dans onPositionUpdate avec (x-prevX, y-prevY, z-prevZ).
     */
    external fun updateFootSlamDisplacement(dx: Float, dy: Float, dz: Float)

    // ── Callbacks C++ → Kotlin ───────────────────────────────────────────────

    // Appelé depuis processFrame() C++ après chaque frame traitée
    private fun onPoseEstimated(x: Float, y: Float, z: Float) {
        frameListener?.onFrameProcessed(x, y, z)
        val current = TrackingState.fromInt(getTrackingState())
        if (current != lastState) {
            lastState = current
            currentTrackingState = current.name   // visible depuis StatusViewModel
            stateListener?.onTrackingStateChanged(current)
        }
    }

    // Appelé depuis processFrame() C++ tous les 5 keyframes — envoie les MapPoints 3D
    private fun onPointCloudUpdated(points: FloatArray) {
        cloudListener?.onPointCloudUpdated(points)
    }

    // ── Intrinsèques ─────────────────────────────────────────────────────────

    private fun computeIntrinsics(cameraId: String): List<Double> {
        val chars       = cameraManager.getCameraCharacteristics(cameraId)
        val sensorSize  = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorPx    = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val focalMm     = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

        if (sensorSize != null && sensorPx != null && focalMm != null) {
            val pxPerMmX = sensorPx.width  / sensorSize.width
            val pxPerMmY = sensorPx.height / sensorSize.height
            val scaleX   = targetSize.width.toDouble()  / sensorPx.width
            val scaleY   = targetSize.height.toDouble() / sensorPx.height
            return listOf(
                focalMm * pxPerMmX * scaleX,
                focalMm * pxPerMmY * scaleY,
                targetSize.width  / 2.0,
                targetSize.height / 2.0
            )
        }
        Log.w(TAG, "Métadonnées capteur absentes — intrinsèques approchées.")
        return listOf(500.0, 500.0, 320.0, 240.0)
    }

    // ── Session Camera2 ──────────────────────────────────────────────────────

    private fun createCaptureSession() {
        val surface = imageReader!!.surface
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!
                        .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                    Log.i(TAG, "Pipeline Camera2 démarré — ${targetSize.width}x${targetSize.height} YUV.")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Échec configuration CaptureSession.")
                }
            },
            cameraHandler
        )
    }

    private fun getBackCameraId(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    // ── JNI ──────────────────────────────────────────────────────────────────

    private external fun setCameraIntrinsics(fx: Double, fy: Double, cx: Double, cy: Double)
    private external fun processFrame(frameData: ByteArray, width: Int, height: Int, rowStride: Int)
    private external fun resetTracking()
    private external fun nativeRelease()
    external fun getTrackingState(): Int

    companion object {
        private const val TAG = "GeoSlam_vSLAM"

        // Lu par StatusViewModel (thread-safe lecture)
        @Volatile
        var currentTrackingState: String = "INIT"

        init {
            try {
                System.loadLibrary("geo_slam")
                Log.i(TAG, "libgeo_slam.so chargée avec succès.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Échec chargement libgeo_slam.so : ${e.message}")
            }
        }
    }
}
