package top.r2dblog.justcamera.hdr.merge

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import top.r2dblog.justcamera.hdr.alignment.TranslationAlignmentEstimator
import top.r2dblog.justcamera.hdr.model.HdrAlignedFrame
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame

data class HdrMergeSettings(
    val shadowStart: Float = 0.005f,
    val shadowFull: Float = 0.08f,
    val highlightStart: Float = 0.85f,
    val highlightReject: Float = 0.98f,
    val motionStart: Float = 0.10f,
    val motionReject: Float = 0.35f,
) {
    init {
        require(0f <= shadowStart && shadowStart < shadowFull)
        require(shadowFull < highlightStart && highlightStart < highlightReject && highlightReject <= 1f)
        require(0f <= motionStart && motionStart < motionReject)
    }
}

class HdrRadianceMerger(private val settings: HdrMergeSettings = HdrMergeSettings()) {
    suspend fun merge(frames: List<HdrAlignedFrame>): SceneLinearFrame {
        require(frames.size >= 2)
        val reference = frames.single { it.exposure.isReference }
        val width = reference.frame.width
        val height = reference.frame.height
        require(frames.all { it.frame.width == width && it.frame.height == height })
        val output = FloatArray(width * height * 3)
        var outputOffset = 0
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width) {
                val referenceRed = reference.frame.sampleUnchecked(x, y, 0)
                val referenceGreen = reference.frame.sampleUnchecked(x, y, 1)
                val referenceBlue = reference.frame.sampleUnchecked(x, y, 2)
                val referenceLuminance = TranslationAlignmentEstimator.luminance(
                    referenceRed, referenceGreen, referenceBlue,
                )
                val referenceObservedMaximum = maxOf(
                    referenceRed,
                    referenceGreen,
                    referenceBlue,
                ) * reference.exposureRatio.toFloat()
                var weightSum = 0.0
                var redSum = 0.0
                var greenSum = 0.0
                var blueSum = 0.0
                frames.forEach { aligned ->
                    if (!aligned.validRegion.contains(x, y)) return@forEach
                    val sampleX = x + aligned.translation.dx
                    val sampleY = y + aligned.translation.dy
                    val red = aligned.frame.sampleUnchecked(sampleX, sampleY, 0)
                    val green = aligned.frame.sampleUnchecked(sampleX, sampleY, 1)
                    val blue = aligned.frame.sampleUnchecked(sampleX, sampleY, 2)
                    val normalizedLuminance = TranslationAlignmentEstimator.luminance(red, green, blue)
                    val exposureRatio = aligned.exposureRatio.toFloat()
                    val observedMaximum = maxOf(red, green, blue) * exposureRatio
                    val observedLuminance = normalizedLuminance * exposureRatio
                    var weight = exposureWeight(observedLuminance, observedMaximum)
                    if (!aligned.exposure.isReference) {
                        weight *= aligned.alignmentConfidence.coerceIn(0f, 1f)
                        val rescuesClippedHighlight =
                            referenceObservedMaximum >= settings.highlightStart &&
                                observedMaximum < settings.highlightStart
                        val rescuesDeepShadow = referenceLuminance <= settings.shadowFull &&
                            observedLuminance > settings.shadowFull
                        if (!rescuesClippedHighlight && !rescuesDeepShadow) {
                            weight *= motionConfidence(normalizedLuminance, referenceLuminance)
                        }
                    }
                    if (weight > 0f && weight.isFinite()) {
                        redSum += weight * red
                        greenSum += weight * green
                        blueSum += weight * blue
                        weightSum += weight
                    }
                }
                output[outputOffset++] = finiteMerged(redSum, weightSum, referenceRed)
                output[outputOffset++] = finiteMerged(greenSum, weightSum, referenceGreen)
                output[outputOffset++] = finiteMerged(blueSum, weightSum, referenceBlue)
            }
        }
        return SceneLinearFrame.fromOwnedSamples(
            width,
            height,
            output,
            timestampNanos = reference.frame.timestampNanos,
            normalization = reference.frame.normalization,
            metadata = reference.frame.metadata,
        )
    }

    fun exposureWeight(observedLuminance: Float, observedMaximum: Float): Float {
        val shadow = smoothStep(settings.shadowStart, settings.shadowFull, observedLuminance)
        val highlight = 1f - smoothStep(
            settings.highlightStart,
            settings.highlightReject,
            observedMaximum,
        )
        return (shadow * highlight).coerceIn(MINIMUM_WEIGHT, 1f)
    }

    fun motionConfidence(candidateLuminance: Float, referenceLuminance: Float): Float {
        val relativeDifference = abs(candidateLuminance - referenceLuminance) /
            maxOf(referenceLuminance, MOTION_LUMA_FLOOR)
        return (1f - smoothStep(settings.motionStart, settings.motionReject, relativeDifference))
            .coerceIn(0f, 1f)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun finiteMerged(sum: Double, weightSum: Double, fallback: Float): Float {
        val merged = if (weightSum > WEIGHT_EPSILON) sum / weightSum else fallback.toDouble()
        return if (merged.isFinite() && merged >= 0.0) {
            merged.coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
        } else {
            fallback
        }
    }

    private companion object {
        const val MINIMUM_WEIGHT = 1.0e-4f
        const val WEIGHT_EPSILON = 1.0e-10
        const val MOTION_LUMA_FLOOR = 0.02f
    }
}
