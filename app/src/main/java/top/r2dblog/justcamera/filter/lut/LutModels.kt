package top.r2dblog.justcamera.filter.lut

import java.nio.FloatBuffer
import kotlin.math.floor

data class LutDomain(val red: Float, val green: Float, val blue: Float) {
    init {
        require(red.isFinite() && green.isFinite() && blue.isFinite())
    }

    companion object {
        val ZERO = LutDomain(0f, 0f, 0f)
        val ONE = LutDomain(1f, 1f, 1f)
    }
}

sealed interface CubeLut

class Lut1D(
    val size: Int,
    val domainMin: LutDomain = LutDomain.ZERO,
    val domainMax: LutDomain = LutDomain.ONE,
    samples: FloatArray,
) : CubeLut {
    private val values = samples.copyOf()

    init {
        require(size >= 2) { "1D LUT size must be at least 2" }
        validateDomain(domainMin, domainMax)
        require(values.size == size * 3) { "Expected ${size * 3} 1D samples, got ${values.size}" }
        require(values.all(Float::isFinite)) { "LUT samples must be finite" }
    }

    fun sample(red: Float, green: Float, blue: Float): FloatArray = FloatArray(3).also {
        it[0] = interpolate(red, 0, domainMin.red, domainMax.red)
        it[1] = interpolate(green, 1, domainMin.green, domainMax.green)
        it[2] = interpolate(blue, 2, domainMin.blue, domainMax.blue)
    }

    fun samplesCopy(): FloatArray = values.copyOf()

    private fun interpolate(input: Float, channel: Int, minimum: Float, maximum: Float): Float {
        val coordinate = normalizedCoordinate(input, minimum, maximum, size)
        val low = floor(coordinate).toInt()
        val high = (low + 1).coerceAtMost(size - 1)
        val fraction = coordinate - low
        val a = values[low * 3 + channel]
        val b = values[high * 3 + channel]
        return lerp(a, b, fraction).coerceIn(0f, 1f)
    }
}

/** Compact 3D LUT with IRIDAS ordering: red changes fastest, then green, then blue. */
class Lut3D(
    val size: Int,
    val domainMin: LutDomain = LutDomain.ZERO,
    val domainMax: LutDomain = LutDomain.ONE,
    samples: FloatArray,
) : CubeLut {
    private val values = samples.copyOf()

    init {
        require(size >= 2) { "3D LUT size must be at least 2" }
        validateDomain(domainMin, domainMax)
        val expected = size.toLong() * size * size * 3
        require(expected <= Int.MAX_VALUE && values.size == expected.toInt()) {
            "Expected $expected 3D samples, got ${values.size}"
        }
        require(values.all(Float::isFinite)) { "LUT samples must be finite" }
    }

    fun sample(red: Float, green: Float, blue: Float): FloatArray = FloatArray(3).also {
        sampleInto(red, green, blue, it, 0)
    }

    fun sampleInto(
        red: Float,
        green: Float,
        blue: Float,
        output: FloatArray,
        offset: Int,
    ) {
        require(offset >= 0 && offset + 2 < output.size)
        val x = normalizedCoordinate(red, domainMin.red, domainMax.red, size)
        val y = normalizedCoordinate(green, domainMin.green, domainMax.green, size)
        val z = normalizedCoordinate(blue, domainMin.blue, domainMax.blue, size)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val x1 = (x0 + 1).coerceAtMost(size - 1)
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val z1 = (z0 + 1).coerceAtMost(size - 1)
        val tx = x - x0
        val ty = y - y0
        val tz = z - z0

        for (channel in 0..2) {
            val c000 = value(x0, y0, z0, channel)
            val c100 = value(x1, y0, z0, channel)
            val c010 = value(x0, y1, z0, channel)
            val c110 = value(x1, y1, z0, channel)
            val c001 = value(x0, y0, z1, channel)
            val c101 = value(x1, y0, z1, channel)
            val c011 = value(x0, y1, z1, channel)
            val c111 = value(x1, y1, z1, channel)
            val c00 = lerp(c000, c100, tx)
            val c10 = lerp(c010, c110, tx)
            val c01 = lerp(c001, c101, tx)
            val c11 = lerp(c011, c111, tx)
            val c0 = lerp(c00, c10, ty)
            val c1 = lerp(c01, c11, ty)
            output[offset + channel] = lerp(c0, c1, tz).coerceIn(0f, 1f)
        }
    }

    fun samplesCopy(): FloatArray = values.copyOf()

    internal fun copySamplesTo(destination: FloatBuffer) {
        require(destination.remaining() >= values.size) { "Destination LUT buffer is too small" }
        destination.put(values)
    }

    internal val sampleCount: Int get() = values.size

    private fun value(red: Int, green: Int, blue: Int, channel: Int): Float =
        values[(((blue * size + green) * size + red) * 3) + channel]
}

private fun validateDomain(minimum: LutDomain, maximum: LutDomain) {
    require(minimum.red < maximum.red && minimum.green < maximum.green &&
        minimum.blue < maximum.blue) { "LUT domain maximum must exceed minimum on every channel" }
}

private fun normalizedCoordinate(input: Float, minimum: Float, maximum: Float, size: Int): Float {
    val finite = if (input.isFinite()) input else minimum
    return ((finite - minimum) / (maximum - minimum)).coerceIn(0f, 1f) * (size - 1)
}

private fun lerp(a: Float, b: Float, fraction: Float): Float = a + (b - a) * fraction
