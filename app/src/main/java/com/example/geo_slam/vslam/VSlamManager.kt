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

    // Résolution cible pour ORB-SLAM3 (640x480 = bon compromis vitesse/précision)
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

        Log.i(TAG, "Ouverture caméra $resolvedId — résolution ${targetSize.width}x${targetSize.height}.")

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
                    val pixelStride = plane.pixelStride
                    val buffer = plane.buffer
                    val bufSize = buffer.remaining()

                    if (image.timestamp % (30L * 1_000_000_000L / 30) < 1_000_000L) {
                        // Log diagnostic une fois par seconde environ
                        Log.i(TAG, "Plane[0] — w=${image.width} h=${image.height} " +
                              "rowStride=$rowStride pixelStride=$pixelStride bufSize=$bufSize")
                    }

                    if (bufSize == 0) {
                        Log.e(TAG, "buffer.remaining()==0, frame ignorée.")
                        return@setOnImageAvailableListener
                    }

                    // Copie explicite vers ByteArray — garantit l'accès CPU
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
                    camera.close()
                    cameraDevice = null
                    Log.w(TAG, "Caméra déconnectée.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Erreur caméra code=$error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission CAMERA manquante : ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur openCamera : ${e.message}")
        }
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
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        Log.i(TAG, "Caméra arrêtée.")
    }

    private fun getBackCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    // Appelé depuis le C++ après traitement ORB-SLAM3
    private fun onPoseEstimated(x: Float, y: Float, z: Float) {
        frameListener?.onFrameProcessed(x, y, z)
    }

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
