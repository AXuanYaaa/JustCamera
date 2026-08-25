package top.r2dblog.justcamera.hdr.capture

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import top.r2dblog.justcamera.camera.model.ValueRange

private const val DEFAULT_MOTION_EXPOSURE_CAP_NANOS = 100_000_000L

data class HdrExposureBaseline(
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val frameDurationNanos: Long? = null,
) {
    init {
        require(exposureTimeNanos > 0 && sensitivityIso > 0)
        require(frameDurationNanos == null || frameDurationNanos > 0)
    }

    val exposureScalar: Double
        get() = exposureTimeNanos.toDouble() * sensitivityIso.toDouble()
}

data class HdrBracketConstraints(
    val manualSensor: Boolean,
    val sensitivityRange: ValueRange<Int>?,
    val exposureTimeRangeNanos: ValueRange<Long>?,
    val maxFrameDurationNanos: Long?,
    val minimumFrameDurationNanos: Long? = null,
    val motionExposureCapNanos: Long? = DEFAULT_MOTION_EXPOSURE_CAP_NANOS,
)

data class HdrBracketEntry(
    val frameIndex: Int,
    val requestedEv: Double,
    val actualPlannedEv: Double,
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val frameDurationNanos: Long,
    val isReference: Boolean,
)

data class HdrBracketPlan(
    val entries: List<HdrBracketEntry>,
    val referenceFrameIndex: Int,
    val baseline: HdrExposureBaseline,
) {
    init {
        require(entries.size >= 2)
        require(entries.map { it.requestedEv }.zipWithNext().all { (a, b) -> a < b })
        require(entries.count { it.isReference } == 1)
        require(entries.single { it.isReference }.frameIndex == referenceFrameIndex)
    }
}

sealed interface HdrBracketPlanningResult {
    data class Planned(val plan: HdrBracketPlan) : HdrBracketPlanningResult
    data class Unsupported(val reason: String) : HdrBracketPlanningResult
}

object HdrBracketPlanner {
    val DEFAULT_EV_OFFSETS = listOf(-2.0, 0.0, 2.0)

    fun plan(
        baseline: HdrExposureBaseline,
        constraints: HdrBracketConstraints,
        requestedEvOffsets: List<Double> = DEFAULT_EV_OFFSETS,
    ): HdrBracketPlanningResult {
        if (!constraints.manualSensor) {
            return HdrBracketPlanningResult.Unsupported(
                "Manual sensor control is required for the deterministic PH5 bracket",
            )
        }
        val isoRange = constraints.sensitivityRange
            ?: return HdrBracketPlanningResult.Unsupported("Sensitivity range is unavailable")
        val exposureRange = constraints.exposureTimeRangeNanos
            ?: return HdrBracketPlanningResult.Unsupported("Exposure-time range is unavailable")
        val maxFrameDuration = constraints.maxFrameDurationNanos
            ?: return HdrBracketPlanningResult.Unsupported("Maximum frame duration is unavailable")
        if (constraints.minimumFrameDurationNanos?.let { it > maxFrameDuration } == true) {
            return HdrBracketPlanningResult.Unsupported(
                "YUV minimum frame duration exceeds the sensor maximum frame duration",
            )
        }
        val offsets = requestedEvOffsets.distinct().sorted()
        if (offsets.size < 2 || offsets.any { !it.isFinite() }) {
            return HdrBracketPlanningResult.Unsupported("At least two finite EV offsets are required")
        }
        val maxExposure = listOfNotNull(
            exposureRange.upper,
            maxFrameDuration,
            constraints.motionExposureCapNanos,
        ).min()
        if (maxExposure < exposureRange.lower) {
            return HdrBracketPlanningResult.Unsupported("Frame duration cannot contain a valid exposure")
        }
        val baselineTime = baseline.exposureTimeNanos.coerceIn(exposureRange.lower, maxExposure)
        val baselineIso = baseline.sensitivityIso.coerceIn(isoRange.lower, isoRange.upper)
        val referenceOffset = offsets.minBy { kotlin.math.abs(it) }
        val entries = offsets.mapIndexed { index, offset ->
            val multiplier = 2.0.pow(offset)
            val targetScalar = baselineTime.toDouble() * baselineIso * multiplier
            var exposure = (baselineTime * multiplier).roundToLong()
                .coerceIn(exposureRange.lower, maxExposure)
            var iso = (targetScalar / exposure).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
            exposure = (targetScalar / iso).roundToLong().coerceIn(exposureRange.lower, maxExposure)
            val plannedScalar = exposure.toDouble() * iso
            val actualEv = log2(plannedScalar / (baselineTime.toDouble() * baselineIso))
            val frameDuration = maxOf(
                exposure,
                baseline.frameDurationNanos ?: exposure,
                constraints.minimumFrameDurationNanos ?: exposure,
            ).coerceAtMost(maxFrameDuration)
            HdrBracketEntry(
                frameIndex = index,
                requestedEv = offset,
                actualPlannedEv = actualEv,
                exposureTimeNanos = exposure,
                sensitivityIso = iso,
                frameDurationNanos = frameDuration,
                isReference = offset == referenceOffset,
            )
        }
        return HdrBracketPlanningResult.Planned(
            HdrBracketPlan(entries, entries.single { it.isReference }.frameIndex, baseline),
        )
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}
