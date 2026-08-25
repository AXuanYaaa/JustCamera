package top.r2dblog.justcamera.camera.application

import android.content.Context
import android.graphics.SurfaceTexture
import top.r2dblog.justcamera.camera.device.CameraEngine

class CameraController(context: Context) {
    private val engine = CameraEngine(context)

    val state = engine.state
    val captureStatus = engine.captureStatus
    val cameras = engine.cameras
    val selectedCamera = engine.selectedCamera
    val previewSize = engine.previewSize

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
    fun captureJpeg() = engine.captureJpeg()
}
