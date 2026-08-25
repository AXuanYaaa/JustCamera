package top.r2dblog.justcamera.camera.capability

data class RawSize(val width: Int, val height: Int)
data class RawRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class RawIntRange(val lower: Int, val upper: Int)
data class RawLongRange(val lower: Long, val upper: Long)
data class RawFloatRange(val lower: Float, val upper: Float)
data class RawRational(val numerator: Int, val denominator: Int)
data class RawFpsRange(val lower: Int, val upper: Int)
data class RawOutput(
    val format: Int,
    val sizes: List<RawSize>,
    val minimumFrameDurationNanos: Map<RawSize, Long> = emptyMap(),
)

data class RawCameraCharacteristics(
    val lensFacing: Int?,
    val sensorOrientation: Int?,
    val hardwareLevel: Int?,
    val activeArray: RawRect?,
    val pixelArraySize: RawSize?,
    val sensitivityRange: RawIntRange?,
    val exposureTimeRange: RawLongRange?,
    val maxFrameDuration: Long?,
    val aeCompensationRange: RawIntRange?,
    val aeCompensationStep: RawRational?,
    val minimumFocusDistance: Float?,
    val focalLengths: List<Float>,
    val apertures: List<Float>,
    val afModes: List<Int>,
    val aeModes: List<Int>,
    val awbModes: List<Int>,
    val targetFpsRanges: List<RawFpsRange>,
    val maxDigitalZoom: Float?,
    val zoomRatioRange: RawFloatRange?,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val maxAfMeteringRegions: Int,
    val maxAeMeteringRegions: Int,
    val maxAwbMeteringRegions: Int,
    val requestCapabilities: List<Int>,
    val opticalStabilizationModes: List<Int>,
    val videoStabilizationModes: List<Int>,
    val physicalCameraIds: Set<String>,
    val outputs: List<RawOutput>,
    val syncMaxLatency: Int? = null,
)
