package top.r2dblog.justcamera.camera.model

enum class AutoFocusState {
    INACTIVE,
    PASSIVE_SCAN,
    PASSIVE_FOCUSED,
    ACTIVE_SCAN,
    FOCUSED_LOCKED,
    NOT_FOCUSED_LOCKED,
    PASSIVE_UNFOCUSED,
    UNKNOWN,
}

enum class AutoExposureState {
    INACTIVE,
    SEARCHING,
    CONVERGED,
    LOCKED,
    FLASH_REQUIRED,
    PRECAPTURE,
    UNKNOWN,
}

enum class AutoWhiteBalanceState { INACTIVE, SEARCHING, CONVERGED, LOCKED, UNKNOWN }

data class CameraCaptureMetadata(
    val timestampNanos: Long? = null,
    val sensitivityIso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val frameDurationNanos: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val autoFocusState: AutoFocusState = AutoFocusState.UNKNOWN,
    val autoExposureState: AutoExposureState = AutoExposureState.UNKNOWN,
    val autoWhiteBalanceState: AutoWhiteBalanceState = AutoWhiteBalanceState.UNKNOWN,
    val exposureCompensationSteps: Int? = null,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val zoomRatio: Float? = null,
    val cropRegion: SensorRect? = null,
)
