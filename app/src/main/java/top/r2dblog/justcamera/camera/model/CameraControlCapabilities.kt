package top.r2dblog.justcamera.camera.model

enum class FocusMode(val label: String) {
    CONTINUOUS_PICTURE("Continuous"),
    AUTO("Auto"),
    MACRO("Macro"),
    CONTINUOUS_VIDEO("Video"),
    EDOF("EDOF"),
    MANUAL("Manual"),
}

enum class WhiteBalanceMode(val label: String) {
    AUTO("Auto"),
    INCANDESCENT("Incandescent"),
    FLUORESCENT("Fluorescent"),
    WARM_FLUORESCENT("Warm fluorescent"),
    DAYLIGHT("Daylight"),
    CLOUDY_DAYLIGHT("Cloudy"),
    TWILIGHT("Twilight"),
    SHADE("Shade"),
}

enum class CaptureMode { JPEG_ONLY, RAW_ONLY, JPEG_AND_RAW }

data class RationalValue(val numerator: Int, val denominator: Int) {
    init {
        require(denominator != 0) { "Rational denominator must not be zero" }
    }

    val value: Double get() = numerator.toDouble() / denominator
}

data class CameraControlCapabilities(
    val manualSensor: Boolean = false,
    val sensitivityRange: ValueRange<Int>? = null,
    val exposureTimeRangeNanos: ValueRange<Long>? = null,
    val maxFrameDurationNanos: Long? = null,
    val exposureCompensationRange: ValueRange<Int>? = null,
    val exposureCompensationStep: RationalValue? = null,
    val focusModes: Set<FocusMode> = emptySet(),
    val minimumFocusDistanceDiopters: Float? = null,
    val whiteBalanceModes: Set<WhiteBalanceMode> = emptySet(),
    val aeLockAvailable: Boolean = false,
    val awbLockAvailable: Boolean = false,
    val maxAfMeteringRegions: Int = 0,
    val maxAeMeteringRegions: Int = 0,
    val maxAwbMeteringRegions: Int = 0,
    val zoomRatioRange: ValueRange<Float>? = null,
    val maxDigitalZoom: Float = 1f,
    val activeArray: SensorRect? = null,
    val rawAvailable: Boolean = false,
) {
    val manualFocusAvailable: Boolean
        get() = FocusMode.MANUAL in focusModes &&
            (minimumFocusDistanceDiopters ?: 0f) > 0f

    val effectiveZoomRange: ValueRange<Float>
        get() = zoomRatioRange ?: ValueRange(1f, maxDigitalZoom.coerceAtLeast(1f))
}
