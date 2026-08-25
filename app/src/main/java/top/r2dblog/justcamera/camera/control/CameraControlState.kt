package top.r2dblog.justcamera.camera.control

import top.r2dblog.justcamera.camera.model.SensorRect
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode

enum class ExposureMode { AUTO, MANUAL }

data class CameraControlState(
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val exposureCompensationSteps: Int = 0,
    val focusMode: FocusMode = FocusMode.CONTINUOUS_PICTURE,
    val focusDistanceDiopters: Float? = null,
    val aeLocked: Boolean = false,
    val afLockRequested: Boolean = false,
    val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val awbLocked: Boolean = false,
    val zoomRatio: Float = 1f,
    val captureMode: CaptureMode = CaptureMode.JPEG_ONLY,
    val meteringRegion: SensorRect? = null,
)

data class CameraControlUpdate(
    val state: CameraControlState,
    val accepted: Boolean,
    val messages: List<String> = emptyList(),
)
