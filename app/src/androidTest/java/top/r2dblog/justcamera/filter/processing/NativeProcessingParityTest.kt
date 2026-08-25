package top.r2dblog.justcamera.filter.processing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.builtin.ContrastFilter
import top.r2dblog.justcamera.filter.builtin.ExposureFilter
import top.r2dblog.justcamera.filter.builtin.SaturationFilter
import top.r2dblog.justcamera.filter.lut.Lut3D
import top.r2dblog.justcamera.filter.lut.Lut3DFilter
import top.r2dblog.justcamera.filter.lut.LutDomain
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.processing.backend.JniNativeProcessingBridge
import top.r2dblog.justcamera.filter.processing.backend.NativeBackendResult
import top.r2dblog.justcamera.filter.processing.backend.NativeOperationProvider
import top.r2dblog.justcamera.filter.processing.backend.NativeProcessingJni
import top.r2dblog.justcamera.filter.processing.backend.NativeStatus
import top.r2dblog.justcamera.imaging.color.ColorTransfer
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.nativecore.NativeCore

@RunWith(AndroidJUnit4::class)
class NativeProcessingParityTest {
    private val context = FilterExecutionContext(FilterExecutionMode.FINAL_CAPTURE)

    @Test
    fun exposureContrastSaturationMatchKotlinAndPreserveAlpha() = runBlocking {
        val input = RgbFloatFrame.create(
            3,
            1,
            RgbChannelLayout.RGBA,
            floatArrayOf(
                0f, 0.25f, 0.5f, 0.13f,
                0.18f, 0.7f, 1f, 0.47f,
                0.9f, 0.4f, 0.1f, 0.91f,
            ),
        )
        assertParity(input, ExposureFilter(), parameters("exposure", 0f))
        assertParity(input, ExposureFilter(), parameters("exposure", 1f))
        assertParity(input, ExposureFilter(), parameters("exposure", -1f))
        assertParity(input, ContrastFilter(), parameters("contrast", 0f))
        assertParity(input, ContrastFilter(), parameters("contrast", 2f))
        assertParity(input, SaturationFilter(), parameters("saturation", 0f))
        assertParity(input, SaturationFilter(), parameters("saturation", 2f))
    }

    @Test
    fun syntheticLutsMatchKotlinTrilinearOracle() = runBlocking {
        val input = RgbFloatFrame.create(
            4,
            1,
            RgbChannelLayout.RGB,
            floatArrayOf(0f, 0f, 0f, 0.18f, 0.5f, 0.8f, 1f, 1f, 1f, 0.4f, 0.2f, 0.7f),
        )
        val transforms: List<(Float, Float, Float) -> FloatArray> = listOf(
            { r, g, b -> floatArrayOf(r, g, b) },
            { r, g, b -> floatArrayOf(1f - r, 1f - g, 1f - b) },
            { r, g, b -> floatArrayOf(b, r, g) },
            { _, _, _ -> floatArrayOf(0.25f, 0.5f, 0.75f) },
        )
        transforms.forEachIndexed { index, transform ->
            val lut = Lut3D(2, samples = cube2(transform))
            assertParity(
                input,
                Lut3DFilter("test.lut_$index", "Test LUT $index", lut),
                parameters("strength", 0.65f),
            )
        }
        val nonDefaultDomain = Lut3D(
            2,
            LutDomain(0.2f, 0.1f, 0.3f),
            LutDomain(0.8f, 0.9f, 0.7f),
            cube2 { r, g, b -> floatArrayOf(1f - r, 1f - g, 1f - b) },
        )
        assertParity(
            input,
            Lut3DFilter("test.lut_domain", "Domain LUT", nonDefaultDomain),
            parameters("strength", 1f),
        )
    }

    @Test
    fun fusedNativeChainMatchesSequentialKotlinOracle() = runBlocking {
        val input = RgbFloatFrame.create(
            2,
            1,
            RgbChannelLayout.RGBA,
            floatArrayOf(0.05f, 0.2f, 0.8f, 0.33f, 0.9f, 0.4f, 0.1f, 0.77f),
        )
        val filtersAndParameters = listOf(
            ExposureFilter() to parameters("exposure", 0.7f),
            ContrastFilter() to parameters("contrast", 1.3f),
            SaturationFilter() to parameters("saturation", 0.4f),
        )
        var expected = input
        val nativeOperations = filtersAndParameters.map { (filter, supplied) ->
            val normalized = supplied.validateAndClamp(filter.descriptor).parameters
            expected = filter.process(expected, normalized, context)
            (filter as NativeOperationProvider).nativeOperation(normalized)
        }

        val result = JniNativeProcessingBridge.process(input, nativeOperations)
        assertTrue(result is NativeBackendResult.Success)
        assertArrayEquals(
            expected.copyPixels(),
            (result as NativeBackendResult.Success).output.copyPixels(),
            TOLERANCE,
        )
    }

    @Test
    fun colorTransferMatchesAroundBothBranchThresholds() {
        assertTransferParity(
            floatArrayOf(0f, 0.0031307f, 0.0031308f, 0.0031309f, 0.18f, 0.8f, 1f),
            direction = 0,
            reference = ColorTransfer::linearToSrgb,
        )
        assertTransferParity(
            floatArrayOf(0f, 0.040449f, 0.04045f, 0.040451f, 0.18f, 0.8f, 1f),
            direction = 1,
            reference = ColorTransfer::srgbToLinear,
        )
    }

    @Test
    fun jniRejectsHeapUndersizedNanAndInvalidLutBuffers() {
        val operation = top.r2dblog.justcamera.filter.processing.backend.NativeOperationEncoder.encode(
            listOf(
                (ExposureFilter() as NativeOperationProvider).nativeOperation(parameters("exposure", 1f)),
            ),
        )
        val heap = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        assertEquals(
            NativeStatus.INVALID_BUFFER.code,
            NativeProcessingJni.nativeProcessInPlace(heap, 1, 1, 3, 3, 3, operation.descriptors, 1, null),
        )
        val undersized = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        assertEquals(
            NativeStatus.INVALID_BUFFER.code,
            NativeProcessingJni.nativeProcessInPlace(
                undersized, 1, 1, 3, 3, 3, operation.descriptors, 1, null,
            ),
        )
        assertEquals(
            NativeStatus.INVALID_ARGUMENT.code,
            NativeProcessingJni.nativeProcessInPlace(
                directFloats(floatArrayOf(0.2f, 0.3f, 0.4f)),
                0,
                1,
                3,
                3,
                3,
                operation.descriptors,
                1,
                null,
            ),
        )
        val nanFrame = directFloats(floatArrayOf(0.2f, Float.NaN, 0.4f))
        assertEquals(
            NativeStatus.INVALID_ARGUMENT.code,
            NativeProcessingJni.nativeProcessInPlace(
                nanFrame, 1, 1, 3, 3, 3, operation.descriptors, 1, null,
            ),
        )

        val invalidLut = top.r2dblog.justcamera.filter.processing.backend.NativeOperationEncoder.encode(
            listOf(
                (Lut3DFilter("test.invalid_lut", "Invalid", Lut3D(2, samples = cube2 { r, g, b ->
                    floatArrayOf(r, g, b)
                })) as NativeOperationProvider).nativeOperation(parameters("strength", 1f)),
            ),
        )
        invalidLut.descriptors.putInt(16 + 4, 1)
        assertEquals(
            NativeStatus.INVALID_LUT.code,
            NativeProcessingJni.nativeProcessInPlace(
                directFloats(floatArrayOf(0.2f, 0.3f, 0.4f)),
                1,
                1,
                3,
                3,
                3,
                invalidLut.descriptors,
                1,
                invalidLut.lutSamples,
            ),
        )
    }

    private suspend fun assertParity(
        input: RgbFloatFrame,
        filter: ImageFilter,
        supplied: FilterParameters,
    ) {
        val normalized = supplied.validateAndClamp(filter.descriptor).parameters
        val expected = filter.process(input, normalized, context)
        val provider = filter as NativeOperationProvider
        val result = JniNativeProcessingBridge.process(input, listOf(provider.nativeOperation(normalized)))
        assertTrue(result is NativeBackendResult.Success)
        val actual = (result as NativeBackendResult.Success).output
        assertArrayEquals(expected.copyPixels(), actual.copyPixels(), TOLERANCE)
        if (input.channelCount == 4) {
            for (pixel in 0 until input.pixelCount) {
                assertEquals(input.sample(pixel, 3), actual.sample(pixel, 3), 0f)
            }
        }
    }

    private fun assertTransferParity(
        samples: FloatArray,
        direction: Int,
        reference: (Float) -> Float,
    ) {
        val buffer = directFloats(samples)
        assertEquals(
            NativeStatus.OK.code,
            NativeProcessingJni.nativeColorTransferInPlace(buffer, samples.size, direction),
        )
        val actual = FloatArray(samples.size)
        buffer.position(0)
        buffer.asFloatBuffer().get(actual)
        assertArrayEquals(samples.map(reference).toFloatArray(), actual, TOLERANCE)
    }

    private fun parameters(key: String, value: Float) = FilterParameters(
        mapOf(key to FilterParameterValue.FloatValue(value)),
    )

    private fun directFloats(values: FloatArray): ByteBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).also {
            it.asFloatBuffer().put(values)
        }

    private fun cube2(transform: (Float, Float, Float) -> FloatArray): FloatArray {
        val output = FloatArray(24)
        var offset = 0
        for (blue in 0..1) for (green in 0..1) for (red in 0..1) {
            val value = transform(red.toFloat(), green.toFloat(), blue.toFloat())
            output[offset++] = value[0]
            output[offset++] = value[1]
            output[offset++] = value[2]
        }
        return output
    }

    companion object {
        private const val TOLERANCE = 2.0e-5f

        @JvmStatic
        @BeforeClass
        fun requireNativeLibrary() {
            assertTrue("Native library must load for JNI parity tests", NativeCore.isAvailable())
        }
    }
}
