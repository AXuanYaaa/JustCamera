package top.r2dblog.justcamera.filter.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

class CpuAdjustmentFiltersTest {
    private val context = FilterExecutionContext(FilterExecutionMode.FINAL_CAPTURE)

    @Test
    fun exposureUsesEvSemantics() = runTest {
        val input = frame(floatArrayOf(0.2f, 0.3f, 0.4f))
        val identity = ExposureFilter().process(input, parameter("exposure", 0f), context)
        val plusOne = ExposureFilter().process(input, parameter("exposure", 1f), context)
        val minusOne = ExposureFilter().process(input, parameter("exposure", -1f), context)

        assertArrayEquals(input.copyPixels(), identity.copyPixels(), EPSILON)
        assertArrayEquals(floatArrayOf(0.4f, 0.6f, 0.8f), plusOne.copyPixels(), EPSILON)
        assertArrayEquals(floatArrayOf(0.1f, 0.15f, 0.2f), minusOne.copyPixels(), EPSILON)
    }

    @Test
    fun contrastUsesStableLinearPivotAndClamps() = runTest {
        val input = frame(floatArrayOf(0.1f, 0.18f, 0.8f))
        val output = ContrastFilter().process(input, parameter("contrast", 2f), context)

        assertArrayEquals(floatArrayOf(0.02f, 0.18f, 1f), output.copyPixels(), EPSILON)
        assertTrue(output.copyPixels().all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun saturationIdentityGrayscaleAndMaximumStayFinite() = runTest {
        val input = frame(floatArrayOf(1f, 0f, 0f))
        val identity = SaturationFilter().process(input, parameter("saturation", 1f), context)
        val gray = SaturationFilter().process(input, parameter("saturation", 0f), context)
        val maximum = SaturationFilter().process(input, parameter("saturation", 2f), context)

        assertArrayEquals(input.copyPixels(), identity.copyPixels(), EPSILON)
        assertEquals(gray.sample(0, 0), gray.sample(0, 1), EPSILON)
        assertEquals(gray.sample(0, 1), gray.sample(0, 2), EPSILON)
        assertTrue(maximum.copyPixels().all { it.isFinite() && it in 0f..1f })
    }

    private fun parameter(key: String, value: Float) = FilterParameters(
        mapOf(key to FilterParameterValue.FloatValue(value)),
    )

    private fun frame(pixels: FloatArray) = RgbFloatFrame.create(
        1,
        1,
        RgbChannelLayout.RGB,
        pixels,
    )

    private companion object { const val EPSILON = 0.0001f }
}
