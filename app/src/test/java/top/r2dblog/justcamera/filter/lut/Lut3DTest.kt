package top.r2dblog.justcamera.filter.lut

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

class Lut3DTest {
    @Test
    fun trilinearIdentityHandlesInteriorAndEdges() {
        val lut = identityLut()
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f), lut.sample(0.25f, 0.5f, 0.75f), EPSILON)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), lut.sample(-2f, 3f, 1f), EPSILON)
    }

    @Test
    fun lutFilterStrengthBlendsInEncodedSrgb() = runTest {
        val filter = Lut3DFilter("test.invert", "Invert", invertLut())
        val input = RgbFloatFrame.create(1, 1, RgbChannelLayout.RGB, floatArrayOf(0f, 0f, 0f))
        val context = FilterExecutionContext(FilterExecutionMode.FINAL_CAPTURE)
        val identity = filter.process(input, strength(0f), context)
        val half = filter.process(input, strength(0.5f), context)
        val full = filter.process(input, strength(1f), context)

        assertArrayEquals(input.copyPixels(), identity.copyPixels(), EPSILON)
        assertEquals(0.214f, half.sample(0, 0), 0.002f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), full.copyPixels(), EPSILON)
    }

    private fun strength(value: Float) = FilterParameters(
        mapOf("strength" to FilterParameterValue.FloatValue(value)),
    )

    private fun identityLut() = Lut3D(2, samples = IDENTITY)
    private fun invertLut() = Lut3D(2, samples = INVERT)

    private companion object {
        const val EPSILON = 0.0001f
        val IDENTITY = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f,
        )
        val INVERT = floatArrayOf(
            1f, 1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 0f,
        )
    }
}
