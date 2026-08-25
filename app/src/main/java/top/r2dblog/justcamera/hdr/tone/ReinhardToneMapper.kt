package top.r2dblog.justcamera.hdr.tone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame
import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

data class ReinhardToneMapSettings(
    val key: Float = 1f,
    val whitePoint: Float? = null,
) {
    init {
        require(key.isFinite() && key > 0f)
        require(whitePoint == null || whitePoint.isFinite() && whitePoint > 0f)
    }
}

class ReinhardToneMapper(private val settings: ReinhardToneMapSettings = ReinhardToneMapSettings()) {
    suspend fun map(input: SceneLinearFrame, rotationDegrees: Int = 0): RgbFloatFrame {
        val output = FloatArray(input.width * input.height * 3)
        var offset = 0
        for (y in 0 until input.height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until input.width) {
                val red = input.sampleUnchecked(x, y, 0)
                val green = input.sampleUnchecked(x, y, 1)
                val blue = input.sampleUnchecked(x, y, 2)
                val luminance = red.toDouble() * 0.2126 + green.toDouble() * 0.7152 +
                    blue.toDouble() * 0.0722
                val scaled = (luminance * settings.key.toDouble()).coerceAtLeast(0.0)
                val mappedLuminance = settings.whitePoint?.let { white ->
                    val whiteSquared = white.toDouble() * white.toDouble()
                    scaled * (1.0 + scaled / whiteSquared) / (1.0 + scaled)
                } ?: (scaled / (1.0 + scaled))
                val scale = if (luminance > LUMINANCE_EPSILON) mappedLuminance / luminance else 0.0
                output[offset++] = finiteUnit(red * scale)
                output[offset++] = finiteUnit(green * scale)
                output[offset++] = finiteUnit(blue * scale)
            }
        }
        return RgbFloatFrame.fromOwnedPixels(
            input.width,
            input.height,
            RgbChannelLayout.RGB,
            output,
            timestampNanos = input.timestampNanos,
            rotationDegrees = rotationDegrees,
            metadata = input.metadata + mapOf(
                "hdr.tone_mapper" to FrameMetadataValue.Text("global_reinhard_luminance"),
            ),
        )
    }

    private fun finiteUnit(value: Double): Float =
        if (value.isFinite()) value.coerceIn(0.0, 1.0).toFloat() else 0f

    private companion object { const val LUMINANCE_EPSILON = 1.0e-12 }
}
