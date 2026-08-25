package top.r2dblog.justcamera.hdr.alignment

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.ceil
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame
import top.r2dblog.justcamera.hdr.model.Translation
import top.r2dblog.justcamera.hdr.model.ValidRegion

data class AlignmentEstimate(
    val translation: Translation,
    val confidence: Float,
    val validRegion: ValidRegion,
)

class TranslationAlignmentEstimator(
    private val maximumShiftPixels: Int = 12,
    private val maximumPyramidLevels: Int = 4,
) {
    init {
        require(maximumShiftPixels >= 0)
        require(maximumPyramidLevels > 0)
    }

    suspend fun estimate(
        reference: SceneLinearFrame,
        candidate: SceneLinearFrame,
    ): AlignmentEstimate {
        require(reference.width == candidate.width && reference.height == candidate.height)
        val referencePyramid = buildPyramid(reference)
        val candidatePyramid = buildPyramid(candidate)
        val levels = minOf(referencePyramid.size, candidatePyramid.size)
        if (luminanceVariance(referencePyramid.first()) < LOW_TEXTURE_VARIANCE) {
            return AlignmentEstimate(
                Translation.IDENTITY,
                0f,
                validRegion(reference.width, reference.height, Translation.IDENTITY),
            )
        }

        var dx = 0
        var dy = 0
        var finalBest = Float.POSITIVE_INFINITY
        var finalSecond = Float.POSITIVE_INFINITY
        for (level in levels - 1 downTo 0) {
            currentCoroutineContext().ensureActive()
            val referenceLevel = referencePyramid[level]
            val candidateLevel = candidatePyramid[level]
            val scale = 1 shl level
            val centerX = if (level == levels - 1) 0 else dx * 2
            val centerY = if (level == levels - 1) 0 else dy * 2
            val radius = if (level == levels - 1) {
                ceil(maximumShiftPixels.toDouble() / scale).toInt().coerceAtLeast(1)
            } else {
                2
            }
            var bestX = centerX
            var bestY = centerY
            var best = Float.POSITIVE_INFINITY
            var second = Float.POSITIVE_INFINITY
            for (candidateDy in centerY - radius..centerY + radius) {
                for (candidateDx in centerX - radius..centerX + radius) {
                    if (abs(candidateDx) * scale > maximumShiftPixels + scale ||
                        abs(candidateDy) * scale > maximumShiftPixels + scale
                    ) continue
                    val score = meanAbsoluteDifference(
                        referenceLevel,
                        candidateLevel,
                        candidateDx,
                        candidateDy,
                    )
                    when {
                        score < best -> {
                            second = best
                            best = score
                            bestX = candidateDx
                            bestY = candidateDy
                        }
                        score < second -> second = score
                    }
                }
            }
            dx = bestX
            dy = bestY
            finalBest = best
            finalSecond = second
        }
        val translation = Translation(
            dx.coerceIn(-maximumShiftPixels, maximumShiftPixels),
            dy.coerceIn(-maximumShiftPixels, maximumShiftPixels),
        )
        val confidence = if (!finalBest.isFinite() || !finalSecond.isFinite()) {
            0f
        } else {
            ((finalSecond - finalBest) / (finalSecond + SCORE_EPSILON)).coerceIn(0f, 1f)
        }
        return AlignmentEstimate(
            translation,
            confidence,
            validRegion(reference.width, reference.height, translation),
        )
    }

    private suspend fun buildPyramid(frame: SceneLinearFrame): List<LuminanceImage> {
        val levels = mutableListOf(toLuminance(frame))
        while (levels.size < maximumPyramidLevels &&
            levels.last().width >= 32 && levels.last().height >= 32
        ) {
            currentCoroutineContext().ensureActive()
            levels += downsample(levels.last())
        }
        return levels
    }

    private suspend fun toLuminance(frame: SceneLinearFrame): LuminanceImage {
        val values = FloatArray(frame.width * frame.height)
        var offset = 0
        for (y in 0 until frame.height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until frame.width) {
                values[offset++] = luminance(
                    frame.sampleUnchecked(x, y, 0),
                    frame.sampleUnchecked(x, y, 1),
                    frame.sampleUnchecked(x, y, 2),
                )
            }
        }
        return LuminanceImage(frame.width, frame.height, values)
    }

    private fun downsample(input: LuminanceImage): LuminanceImage {
        val width = input.width / 2
        val height = input.height / 2
        val output = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sourceX = x * 2
                val sourceY = y * 2
                output[y * width + x] = (
                    input[sourceX, sourceY] + input[sourceX + 1, sourceY] +
                        input[sourceX, sourceY + 1] + input[sourceX + 1, sourceY + 1]
                    ) * 0.25f
            }
        }
        return LuminanceImage(width, height, output)
    }

    private fun meanAbsoluteDifference(
        reference: LuminanceImage,
        candidate: LuminanceImage,
        dx: Int,
        dy: Int,
    ): Float {
        val region = validRegion(reference.width, reference.height, Translation(dx, dy))
        val count = (region.rightExclusive - region.left) * (region.bottomExclusive - region.top)
        if (count < MINIMUM_COMPARISON_PIXELS) return Float.POSITIVE_INFINITY
        var difference = 0.0
        for (y in region.top until region.bottomExclusive) {
            for (x in region.left until region.rightExclusive) {
                difference += abs(reference[x, y] - candidate[x + dx, y + dy])
            }
        }
        return (difference / count).toFloat()
    }

    private fun luminanceVariance(image: LuminanceImage): Double {
        val mean = image.values.sumOf { it.toDouble() } / image.values.size
        return image.values.sumOf { value ->
            val difference = value - mean
            difference * difference
        } / image.values.size
    }

    private data class LuminanceImage(val width: Int, val height: Int, val values: FloatArray) {
        operator fun get(x: Int, y: Int): Float = values[y * width + x]
    }

    companion object {
        fun validRegion(width: Int, height: Int, translation: Translation): ValidRegion =
            ValidRegion(
                left = maxOf(0, -translation.dx),
                top = maxOf(0, -translation.dy),
                rightExclusive = minOf(width, width - translation.dx),
                bottomExclusive = minOf(height, height - translation.dy),
            )

        fun luminance(red: Float, green: Float, blue: Float): Float =
            red * 0.2126f + green * 0.7152f + blue * 0.0722f

        private const val LOW_TEXTURE_VARIANCE = 1.0e-7
        private const val SCORE_EPSILON = 1.0e-8f
        private const val MINIMUM_COMPARISON_PIXELS = 16
    }
}
