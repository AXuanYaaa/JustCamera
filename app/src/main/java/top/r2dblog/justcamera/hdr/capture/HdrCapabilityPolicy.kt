package top.r2dblog.justcamera.hdr.capture

import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraOutputFormat
import top.r2dblog.justcamera.camera.model.ImageSize

enum class HdrSupportLevel {
    UNSUPPORTED,
    BASIC_SEQUENTIAL,
    BURST_CAPABLE,
    MANUAL_BRACKET_CAPABLE,
}

data class HdrCapabilityAssessment(
    val level: HdrSupportLevel,
    val processingSize: ImageSize?,
    val burstCapture: Boolean,
    val manualSensor: Boolean,
    val captureEnabled: Boolean,
    val reason: String,
    val minimumFrameDurationNanos: Long? = null,
    val syncMaxLatency: Int? = null,
)

object HdrProcessingSizeSelector {
    const val MAX_PROCESSING_PIXELS = 1_100_000L

    fun select(sizes: List<ImageSize>): ImageSize? = sizes.asSequence()
        .filter { it.area <= MAX_PROCESSING_PIXELS }
        .maxWithOrNull(compareBy<ImageSize> { it.area }.thenBy { it.width }.thenBy { it.height })
}

object HdrCapabilityPolicy {
    fun assess(camera: CameraCapabilities): HdrCapabilityAssessment {
        val output = camera.outputs.firstOrNull { it.format == CameraOutputFormat.YUV_420_888 }
        val size = HdrProcessingSizeSelector.select(output?.sizes.orEmpty())
            ?: return HdrCapabilityAssessment(
                HdrSupportLevel.UNSUPPORTED,
                null,
                camera.supportsBurst,
                camera.supportsManualSensor,
                captureEnabled = false,
                reason = "No YUV_420_888 size fits the PH5 HDR memory budget",
                syncMaxLatency = camera.syncMaxLatency,
            )
        val duration = output?.minimumFrameDurationNanos?.get(size)
        val manualReady = camera.supportsManualSensor && camera.sensitivityRange != null &&
            camera.exposureTimeRangeNanos != null && camera.maxFrameDurationNanos != null
        if (manualReady) {
            return HdrCapabilityAssessment(
                HdrSupportLevel.MANUAL_BRACKET_CAPABLE,
                size,
                camera.supportsBurst,
                manualSensor = true,
                captureEnabled = true,
                reason = if (camera.supportsBurst) {
                    "Manual-sensor YUV bracket with burst submission"
                } else {
                    "Manual-sensor YUV bracket with sequential submission"
                },
                minimumFrameDurationNanos = duration,
                syncMaxLatency = camera.syncMaxLatency,
            )
        }
        val usefulAeCompensation = camera.aeCompensationRange?.let { it.lower < 0 && it.upper > 0 } == true &&
            camera.aeCompensationStep?.value?.let { it > 0.0 } == true
        val level = when {
            camera.supportsBurst -> HdrSupportLevel.BURST_CAPABLE
            usefulAeCompensation -> HdrSupportLevel.BASIC_SEQUENTIAL
            else -> HdrSupportLevel.UNSUPPORTED
        }
        return HdrCapabilityAssessment(
            level,
            size,
            camera.supportsBurst,
            manualSensor = false,
            captureEnabled = false,
            reason = when (level) {
                HdrSupportLevel.BURST_CAPABLE ->
                    "YUV burst is available, but PH5 requires manual sensor metadata for deterministic bracketing"
                HdrSupportLevel.BASIC_SEQUENTIAL ->
                    "Only AE-compensation sequencing is available; deterministic PH5 capture is disabled"
                HdrSupportLevel.MANUAL_BRACKET_CAPABLE -> error("unreachable")
                else -> "No reliable exposure bracket controls are available"
            },
            minimumFrameDurationNanos = duration,
            syncMaxLatency = camera.syncMaxLatency,
        )
    }
}
