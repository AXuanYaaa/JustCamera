package top.r2dblog.justcamera.hdr.processing

import top.r2dblog.justcamera.hdr.alignment.TranslationAlignmentEstimator
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame

data class LuminanceHistogram(
    val bins: IntArray,
    val sampleCount: Int,
    val clippedHighlights: Int,
    val deepShadows: Int,
)

object LuminanceHistogramCalculator {
    fun calculate(frame: SceneLinearFrame, binCount: Int = 256): LuminanceHistogram {
        require(binCount >= 2)
        val bins = IntArray(binCount)
        var highlights = 0
        var shadows = 0
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val luminance = TranslationAlignmentEstimator.luminance(
                    frame.sampleUnchecked(x, y, 0),
                    frame.sampleUnchecked(x, y, 1),
                    frame.sampleUnchecked(x, y, 2),
                )
                val displayRangeLuminance = luminance.coerceIn(0f, 1f)
                bins[(displayRangeLuminance * (binCount - 1)).toInt()]++
                if (luminance >= 0.98f) highlights++
                if (luminance <= 0.02f) shadows++
            }
        }
        return LuminanceHistogram(bins, frame.pixelCount, highlights, shadows)
    }
}
