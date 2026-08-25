package top.r2dblog.justcamera.camera.model

enum class CameraErrorCode {
    PERMISSION_DENIED,
    CAMERA_UNAVAILABLE,
    DISCONNECTED,
    ACCESS_FAILURE,
    SESSION_CONFIGURATION_FAILED,
    CAPTURE_FAILED,
    STORAGE_FAILED,
    INVALID_SURFACE,
    UNSUPPORTED_CAPABILITY,
    UNKNOWN,
}

data class CameraError(
    val code: CameraErrorCode,
    val message: String,
    val cause: Throwable? = null,
    val recoverable: Boolean = true,
)

sealed interface CameraState {
    data object PermissionRequired : CameraState
    data object Closed : CameraState
    data class Opening(val cameraId: String) : CameraState
    data class Opened(val cameraId: String) : CameraState
    data class Configuring(val cameraId: String) : CameraState
    data class Previewing(val cameraId: String) : CameraState
    data class Capturing(val cameraId: String) : CameraState
    data class Error(val error: CameraError) : CameraState
}

sealed interface CameraEvent {
    data object PermissionMissing : CameraEvent
    data object Close : CameraEvent
    data class Open(val cameraId: String) : CameraEvent
    data class DeviceOpened(val cameraId: String) : CameraEvent
    data class Configure(val cameraId: String) : CameraEvent
    data class PreviewStarted(val cameraId: String) : CameraEvent
    data class CaptureStarted(val cameraId: String) : CameraEvent
    data class Failed(val error: CameraError) : CameraEvent
}

object CameraStateReducer {
    fun reduce(current: CameraState, event: CameraEvent): CameraState = when (event) {
        CameraEvent.PermissionMissing -> CameraState.PermissionRequired
        CameraEvent.Close -> CameraState.Closed
        is CameraEvent.Failed -> CameraState.Error(event.error)
        is CameraEvent.Open -> when (current) {
            CameraState.Closed, CameraState.PermissionRequired ->
                CameraState.Opening(event.cameraId)
            is CameraState.Error -> if (current.error.recoverable) {
                CameraState.Opening(event.cameraId)
            } else {
                current
            }
            else -> current
        }
        is CameraEvent.DeviceOpened -> when (current) {
            is CameraState.Opening -> CameraState.Opened(event.cameraId)
            else -> current
        }
        is CameraEvent.Configure -> when (current) {
            is CameraState.Opened, is CameraState.Configuring ->
                CameraState.Configuring(event.cameraId)
            else -> current
        }
        is CameraEvent.PreviewStarted -> when (current) {
            is CameraState.Opened,
            is CameraState.Configuring,
            is CameraState.Capturing,
            -> CameraState.Previewing(event.cameraId)
            else -> current
        }
        is CameraEvent.CaptureStarted -> when (current) {
            is CameraState.Previewing -> CameraState.Capturing(event.cameraId)
            else -> current
        }
    }
}

sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object Capturing : CaptureStatus
    data object Saving : CaptureStatus
    data class Saved(val displayName: String) : CaptureStatus
    data class Failed(val error: CameraError) : CaptureStatus
}
