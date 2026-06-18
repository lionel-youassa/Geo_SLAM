package com.example.geo_slam.vslam

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size

/**
 * VSlamManager : Gère le moteur de vision avec réutilisation de buffer pour la stabilité.
 */
class VSlamManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    enum class TrackingState(val value: Int) {
        INIT(0), INITIALIZING(1), TRACKING(2), RECENTLY_LOST(3), LOST(4);
        companion object {
            fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: INIT
        }
    }

    interface OnFrameProcessedListener {
        fun onFrameProcessed(x: Float, y: Float, z: Float)
    }

    private var frameListener: OnFrameProcessedListener? = null
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var isCameraRunning = false
    
    // Buffer réutilisable pour éviter les crashs liés à la mémoire (GC)
    private var frameBuffer: ByteArray? = null

    private val cameraThread = HandlerThread("CameraThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val targetSize = Size(640, 480)

    fun setOnFrameProcessedListener(listener: OnFrameProcessedListener?) {
        this.frameListener = listener
    }

    fun startCamera() {
        if (isCameraRunning) return
        val id = getBackCameraId() ?: return
        isCameraRunning = true

        val intrinsics = computeIntrinsics(id)
        setCameraIntrinsics(intrinsics[0], intrinsics[1], intrinsics[2], intrinsics[3])

        imageReader = ImageReader.newInstance(targetSize.width, targetSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val remaining = buffer.remaining()

                    if (frameBuffer == null || frameBuffer!!.size != remaining) {
                        frameBuffer = ByteArray(remaining)
                    }
                    buffer.get(frameBuffer!!)
                    
                    if (isCameraRunning) {
                        processFrame(frameBuffer!!, image.width, image.height, plane.rowStride)
                    }
                } catch (e: Exception) {
                    Log.e("VSlamManager", "Frame processing error: ${e.message}")
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { stopCamera() }
                override fun onError(camera: CameraDevice, error: Int) { stopCamera() }
            }, cameraHandler)
        } catch (e: SecurityException) {
            isCameraRunning = false
        }
    }

    fun stopCamera() {
        isCameraRunning = false
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        cameraHandler.post { nativeRelease() }
    }

    fun reset() = resetTracking()

    fun getTrackingStatus(): TrackingState {
        return TrackingState.fromInt(getTrackingState())
    }

    fun updateScale(dx: Float, dy: Float, dz: Float) {
        if (isCameraRunning) updateFootSlamDisplacement(dx, dy, dz)
    }

    private fun onPoseEstimated(x: Float, y: Float, z: Float) {
        frameListener?.onFrameProcessed(x, y, z)
    }

    private fun onPointCloudUpdated(points: FloatArray) {}

    private fun computeIntrinsics(cameraId: String): List<Double> {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        if (sensorSize != null && focalMm != null) {
            val fx = (focalMm * targetSize.width / sensorSize.width).toDouble()
            val fy = (focalMm * targetSize.height / sensorSize.height).toDouble()
            return listOf(fx, fy, targetSize.width / 2.0, targetSize.height / 2.0)
        }
        return listOf(500.0, 500.0, 320.0, 240.0)
    }

    private fun createCaptureSession() {
        val surface = imageReader?.surface ?: return
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                    }.build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                } catch (e: Exception) {}
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, cameraHandler)
    }

    private fun getBackCameraId(): String? = cameraManager.cameraIdList.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private external fun setCameraIntrinsics(fx: Double, fy: Double, cx: Double, cy: Double)
    private external fun processFrame(frameData: ByteArray, width: Int, height: Int, rowStride: Int)
    private external fun updateFootSlamDisplacement(dx: Float, dy: Float, dz: Float)
    private external fun resetTracking()
    private external fun nativeRelease()
    private external fun getTrackingState(): Int

    companion object {
        @Volatile private var INSTANCE: VSlamManager? = null
        fun getInstance(context: Context): VSlamManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: VSlamManager(context).also { INSTANCE = it }
        }
        init { System.loadLibrary("geo_slam") }
    }
}
