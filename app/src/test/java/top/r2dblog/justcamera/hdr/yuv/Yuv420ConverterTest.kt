package top.r2dblog.justcamera.hdr.yuv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Yuv420ConverterTest {
    @Test
    fun convertsLimitedRangeBlackWhiteAndNeutralGray() = runTest {
        assertEquals(0f, convertPixel(16, 128, 128).sample(0, 0, 0), 1e-6f)
        assertEquals(1f, convertPixel(235, 128, 128).sample(0, 0, 1), 1e-5f)
        val gray = convertPixel(126, 128, 128)
        assertEquals(gray.sample(0, 0, 0), gray.sample(0, 0, 1), 1e-5f)
        assertEquals(gray.sample(0, 0, 1), gray.sample(0, 0, 2), 1e-5f)
    }

    @Test
    fun respectsPaddedRowsPixelStrideAndOddDimensions() = runTest {
        val y = byteArrayOf(
            16, 126, 235.toByte(), 0, 0,
            32, 64, 96, 0, 0,
            160.toByte(), 192.toByte(), 220.toByte(), 0, 0,
        )
        val u = byteArrayOf(128.toByte(), 0, 128.toByte(), 0, 0, 128.toByte(), 0, 128.toByte())
        val v = byteArrayOf(128.toByte(), 0, 128.toByte(), 0, 0, 128.toByte(), 0, 128.toByte())
        val frame = OwnedYuvFrame.create(
            3,
            3,
            9,
            OwnedYuvPlane.create(y, 5, 1),
            OwnedYuvPlane.create(u, 5, 2),
            OwnedYuvPlane.create(v, 5, 2),
        )

        val converted = Yuv420Converter.toSceneLinear(frame)

        assertEquals(3, converted.width)
        assertEquals(3, converted.height)
        assertEquals(0f, converted.sample(0, 0, 0), 1e-6f)
        assertEquals(1f, converted.sample(2, 0, 0), 1e-5f)
        assertTrue(converted.copySamples().all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun bt601ChromaProducesExpectedChannelOrdering() {
        val redBiased = Yuv420Converter.limitedBt601(126, 90, 200)
        val blueBiased = Yuv420Converter.limitedBt601(126, 200, 90)

        assertTrue(redBiased[0] > redBiased[2])
        assertTrue(blueBiased[2] > blueBiased[0])
    }

    private suspend fun convertPixel(y: Int, u: Int, v: Int) = Yuv420Converter.toSceneLinear(
        OwnedYuvFrame.create(
            1,
            1,
            1,
            OwnedYuvPlane.create(byteArrayOf(y.toByte()), 1, 1),
            OwnedYuvPlane.create(byteArrayOf(u.toByte()), 1, 1),
            OwnedYuvPlane.create(byteArrayOf(v.toByte()), 1, 1),
        ),
    )
}
