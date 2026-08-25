package top.r2dblog.justcamera.camera.model

data class ImageSize(val width: Int, val height: Int) {
    val area: Long = width.toLong() * height

    init {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
    }

    override fun toString(): String = "${width}×$height"
}

data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int = right - left
    val height: Int = bottom - top
}

data class ValueRange<T : Comparable<T>>(val lower: T, val upper: T)

data class FrameRateRange(val lower: Int, val upper: Int) {
    override fun toString(): String = "$lower–$upper fps"
}

enum class CameraFacing { FRONT, BACK, EXTERNAL, UNKNOWN }

enum class HardwareLevel { LEGACY, LIMITED, FULL, LEVEL_3, EXTERNAL, UNKNOWN }

enum class CameraCapability {
    RAW,
    MANUAL_SENSOR,
    MANUAL_POST_PROCESSING,
    BURST_CAPTURE,
    YUV_REPROCESSING,
    PRIVATE_REPROCESSING,
    LOGICAL_MULTI_CAMERA,
    DEPTH_OUTPUT,
}

enum class CameraOutputFormat { JPEG, YUV_420_888, RAW_SENSOR, PRIVATE, DEPTH, OTHER }

data class SupportedOutput(
    val format: CameraOutputFormat,
    val platformFormat: Int,
    val sizes: List<ImageSize>,
)

data class CameraCapabilities(
    val cameraId: String,
    val facing: CameraFacing,
    val sensorOrientation: Int,
    val hardwareLevel: HardwareLevel,
    val activeArray: SensorRect?,
    val pixelArraySize: ImageSize?,
    val sensitivityRange: ValueRange<Int>?,
    val exposureTimeRangeNanos: ValueRange<Long>?,
    val maxFrameDurationNanos: Long?,
    val minimumFocusDistanceDiopters: Float?,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val afModes: List<String>,
    val aeModes: List<String>,
    val awbModes: List<String>,
    val targetFpsRanges: List<FrameRateRange>,
    val maxDigitalZoom: Float,
    val capabilities: Set<CameraCapability>,
    val platformRequestCapabilities: List<Int>,
    val opticalStabilization: Boolean,
    val videoStabilization: Boolean,
    val physicalCameraIds: Set<String>,
    val outputs: List<SupportedOutput>,
) {
    val supportsRaw: Boolean get() = CameraCapability.RAW in capabilities
    val supportsManualSensor: Boolean get() = CameraCapability.MANUAL_SENSOR in capabilities
    val supportsManualPostProcessing: Boolean
        get() = CameraCapability.MANUAL_POST_PROCESSING in capabilities
    val supportsBurst: Boolean get() = CameraCapability.BURST_CAPTURE in capabilities
    val supportsLogicalMultiCamera: Boolean
        get() = CameraCapability.LOGICAL_MULTI_CAMERA in capabilities
    val supportsDepth: Boolean get() = CameraCapability.DEPTH_OUTPUT in capabilities
    val canFocus: Boolean get() = (minimumFocusDistanceDiopters ?: 0f) > 0f

    fun sizesFor(format: CameraOutputFormat): List<ImageSize> =
        outputs.firstOrNull { it.format == format }?.sizes.orEmpty()
}
