package top.r2dblog.justcamera.camera.session

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.media.ImageReader
import android.os.Looper
import android.view.Surface
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory

/**
 * Camera-thread-confined owner for every live Camera2 session resource.
 *
 * The TextureView owns its [SurfaceTexture]; this controller owns only the [Surface] wrapper
 * created from it. Closing this controller never releases the SurfaceTexture.
 */
internal class CameraSessionController(private val cameraLooper: Looper) {
    var cameraDevice: CameraDevice? = null
        private set
    var captureSession: CameraCaptureSession? = null
        private set
    var imageReader: ImageReader? = null
        private set
    var rawImageReader: ImageReader? = null
        private set
    var previewSurface: Surface? = null
        private set

    fun adoptCameraDevice(device: CameraDevice) {
        checkCameraThread()
        check(cameraDevice == null || cameraDevice === device) {
            "A different CameraDevice is already owned"
        }
        cameraDevice = device
    }

    fun replacePreviewSurface(texture: SurfaceTexture): Surface {
        checkCameraThread()
        previewSurface?.release()
        return Surface(texture).also { previewSurface = it }
    }

    fun replaceImageReader(reader: ImageReader) {
        checkCameraThread()
        imageReader?.close()
        imageReader = reader
    }

    fun replaceRawImageReader(reader: ImageReader?, retirePrevious: (ImageReader) -> Unit) {
        checkCameraThread()
        rawImageReader?.let(retirePrevious)
        rawImageReader = reader
    }

    fun adoptCaptureSession(session: CameraCaptureSession) {
        checkCameraThread()
        check(captureSession == null || captureSession === session) {
            "A different CameraCaptureSession is already owned"
        }
        captureSession = session
    }

    fun closeUnownedDevice(device: CameraDevice) {
        checkCameraThread()
        if (cameraDevice !== device) device.close()
    }

    fun closeUnownedSession(session: CameraCaptureSession) {
        checkCameraThread()
        if (captureSession !== session) session.close()
    }

    /**
     * Returns true when at least one owned resource was closed or retired. RAW reader ownership is
     * transferred to [retireRawReader], which may defer physical close for an in-flight DNG lease.
     */
    fun closeAll(retireRawReader: (ImageReader) -> Unit): Boolean {
        checkCameraThread()
        val hadResources = captureSession != null || cameraDevice != null ||
            imageReader != null || rawImageReader != null || previewSurface != null

        try {
            captureSession?.stopRepeating()
        } catch (error: CameraAccessException) {
            JcLog.warn(LogCategory.CAMERA, "Unable to stop repeating preview", error)
        } catch (error: IllegalStateException) {
            JcLog.debug(LogCategory.CAMERA) { "Session already closed: ${error.message}" }
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawImageReader?.let(retireRawReader)
        rawImageReader = null
        previewSurface?.release()
        previewSurface = null
        return hadResources
    }

    private fun checkCameraThread() {
        check(Looper.myLooper() === cameraLooper) {
            "Camera resources must only be mutated on the camera thread"
        }
    }
}
