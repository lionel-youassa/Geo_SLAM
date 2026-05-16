package com.example.geo_slam.vslam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SizeF

class VSlamManager(private val context: Context) {

    interface OnFrameProcessedListener {
        fun onFrameProcessed(x: Float, y: Float, z: Float)
    }

    private var frameListener: OnFrameProcessedListener? = null

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val targetSize = Size(640, 480)

    fun setOnFrameProcessedListener(listener: OnFrameProcessedListener) {
        frameListener = listener
    }

    fun startCamera(cameraId: String? = null) {
        val resolvedId = cameraId ?: run {
            val id = getBackCameraId()
            if (id == null) {
                Log.e(TAG, "Aucune caméra arrière trouvée — vSLAM inactif.")
                return
            }
            id
        }

        // Calcul des intrinsèques réelles depuis les métadonnées Camera2
        val (fx, fy, cx, cy) = computeIntrinsics(resolvedId)
        Log.i(TAG, "Intrinsèques — fx=${"%.1f".format(fx)} fy=${"%.1f".format(fy)} cx=${"%.1f".format(cx)} cy=${"%.1f".format(cy)}")
        setCameraIntrinsics(fx, fy, cx, cy)

        Log.i(TAG, "Ouverture caméra $resolvedId — ${targetSize.width}x${targetSize.height}.")

        imageReader = ImageReader.newInstance(
            targetSize.width, targetSize.height,
            ImageFormat.YUV_420_888,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowStride = plane.rowStride
                    val buffer = plane.buffer
                    val bufSize = buffer.remaining()
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
                    Log.i(TAG, "Caméra ouverte.")
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
            Log.e(TAG, "Permission CAMERA manquante : ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur openCamera : ${e.message}")
        }
    }

    /**
     * Dérive fx, fy, cx, cy à partir de LENS_INFO_AVAILABLE_FOCAL_LENGTHS et
     * SENSOR_INFO_PHYSICAL_SIZE, ramenés à la résolution 640×480.
     */
    private fun computeIntrinsics(cameraId: String): List<Double> {
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        // Taille physique du capteur (mm)
        val sensorSize: SizeF? = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        // Résolution native maximale du capteur (pixels)
        val sensorPixels: android.util.Size? = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        // Longueur focale (mm)
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

        if (sensorSize != null && sensorPixels != null && focalMm != null) {
            // Pixels par mm dans la résolution native
            val pxPerMmX = sensorPixels.width  / sensorSize.width
            val pxPerMmY = sensorPixels.height / sensorSize.height
            // Focale en pixels (résolution native)
            val fxNative = focalMm * pxPerMmX
            val fyNative = focalMm * pxPerMmY
            // Mise à l'échelle vers 640×480
            val scaleX = targetSize.width.toDouble()  / sensorPixels.width
            val scaleY = targetSize.height.toDouble() / sensorPixels.height
            return listOf(
                fxNative * scaleX,
                fyNative * scaleY,
                targetSize.width  / 2.0,
                targetSize.height / 2.0
            )
        }

        // Fallback raisonnable si les métadonnées sont absentes
        Log.w(TAG, "Métadonnées capteur absentes — intrinsèques approchées.")
        return listOf(500.0, 500.0, 320.0, 240.0)
    }

    private fun createCaptureSession() {
        val surface = imageReader!!.surface
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!
                        .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply {
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

    fun stopCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice = null
        imageReader?.close();    imageReader = null
        Log.i(TAG, "Caméra arrêtée.")
    }

    private fun getBackCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    // Appelé depuis le C++ après traitement vSLAM
    private fun onPoseEstimated(x: Float, y: Float, z: Float) {
        frameListener?.onFrameProcessed(x, y, z)
    }

    private external fun setCameraIntrinsics(fx: Double, fy: Double, cx: Double, cy: Double)
    private external fun processFrame(frameData: ByteArray, width: Int, height: Int, rowStride: Int)

    companion object {
        private const val TAG = "GeoSlam_vSLAM"
        init {
            try {
                System.loadLibrary("geo_slam")
                android.util.Log.i(TAG, "libgeo_slam.so chargée avec succès.")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e(TAG, "Échec chargement libgeo_slam.so : ${e.message}")
            }
        }
    }
}
