package top.r2dblog.justcamera.filter.processing

import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.filter.lut.Lut3D
import top.r2dblog.justcamera.filter.processing.backend.NativeFilterOperation
import top.r2dblog.justcamera.filter.processing.backend.NativeOperationEncoder
import top.r2dblog.justcamera.filter.processing.backend.NativeOperationType

class NativeOperationEncoderTest {
    @Test
    fun descriptorsAndLutUseDirectNativeOrderBuffers() {
        val samples = FloatArray(24) { it / 23f }
        val encoded = NativeOperationEncoder.encode(
            listOf(
                NativeFilterOperation(NativeOperationType.EXPOSURE, 1f, 0.5f),
                NativeFilterOperation(NativeOperationType.LUT_3D, strength = 0.75f, lut = Lut3D(2, samples = samples)),
            ),
        )

        assertTrue(encoded.descriptors.isDirect)
        assertEquals(ByteOrder.nativeOrder(), encoded.descriptors.order())
        assertEquals(16 + 2 * 48, encoded.descriptors.capacity())
        assertEquals(0x4A435034, encoded.descriptors.getInt(0))
        assertEquals(2, encoded.descriptors.getInt(12))
        assertEquals(NativeOperationType.LUT_3D.code, encoded.descriptors.getInt(16 + 48))
        assertEquals(2, encoded.descriptors.getInt(16 + 48 + 4))
        assertEquals(0L, encoded.descriptors.getLong(16 + 48 + 8))
        val lutBuffer = requireNotNull(encoded.lutSamples)
        assertTrue(lutBuffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), lutBuffer.order())
        val actual = FloatArray(samples.size)
        lutBuffer.asFloatBuffer().get(actual)
        assertArrayEquals(samples, actual, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteOperationParameterBeforeJni() {
        NativeOperationEncoder.encode(
            listOf(NativeFilterOperation(NativeOperationType.EXPOSURE, Float.NaN, 1f)),
        )
    }
}
