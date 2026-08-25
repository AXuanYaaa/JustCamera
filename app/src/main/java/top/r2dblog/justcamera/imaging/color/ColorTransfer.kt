package top.r2dblog.justcamera.imaging.color

import kotlin.math.pow

object ColorTransfer {
    fun linearToSrgb(value: Float): Float {
        val channel = finiteUnit(value)
        return if (channel <= 0.0031308f) {
            12.92f * channel
        } else {
            1.055f * channel.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        }.coerceIn(0f, 1f)
    }

    fun srgbToLinear(value: Float): Float {
        val channel = finiteUnit(value)
        return if (channel <= 0.04045f) {
            channel / 12.92f
        } else {
            ((channel + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }.coerceIn(0f, 1f)
    }

    fun finiteUnit(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}
