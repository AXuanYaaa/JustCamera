package top.r2dblog.justcamera.camera.application

import android.content.Context
import android.graphics.SurfaceTexture
import top.r2dblog.justcamera.camera.control.CameraControlState
import top.r2dblog.justcamera.camera.device.CameraEngine

class CameraController(context: Context) {
    private val engine = CameraEngine(context)

    val state = engine.state
    val captureStatus = engine.captureStatus
    val cameras = engine.cameras
    val selectedCamera = engine.selectedCamera
    val previewSize = engine.previewSize
    val controlState = engine.controlState
    val controlCapabilities = engine.controlCapabilities
    val controlError = engine.controlError
    val captureMetadata = engine.captureMetadata
    val rawCaptureAvailable = engine.rawCaptureAvailable

    fun updatePermissions(cameraGranted: Boolean, storageGranted: Boolean) =
        engine.updatePermissions(cameraGranted, storageGranted)

    fun start() = engine.start()
    fun stop() = engine.stop()
    fun release() = engine.release()

    fun attachPreview(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) = engine.attachPreview(texture, width, height, rotationDegrees)

    fun updatePreviewGeometry(width: Int, height: Int, rotationDegrees: Int) =
        engine.updatePreviewGeometry(width, height, rotationDegrees)

    fun detachPreview(texture: SurfaceTexture) = engine.detachPreview(texture)
    fun switchCamera() = engine.switchCamera()
    fun selectCamera(cameraId: String) = engine.selectCamera(cameraId)
    fun updateControls(state: CameraControlState) = engine.updateControls(state)
    fun focusAt(normalizedX: Float, normalizedY: Float) =
        engine.focusAt(normalizedX, normalizedY)
    fun capture() = engine.capture()
    fun captureJpeg() = engine.captureJpeg()
}
