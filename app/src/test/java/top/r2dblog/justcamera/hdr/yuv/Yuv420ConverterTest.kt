package top.r2dblog.justcamera.hdr.yuv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.imaging.color.ColorTransfer

class Yuv420ConverterTest {
    @Test
    fun fullRangeNeutralEndpointsConvertToBlackAndWhite() = runTest {
        val black = convertPixel(0, 128, 128)
        val white = convertPixel(255, 128, 128)

        assertTrue(black.copySamples().all { it == 0f })
        assertTrue(white.copySamples().all { it == 1f })
    }

    @Test
    fun neutralGrayUsesTheEntireByteRangeBeforeInverseSrgb() = runTest {
        listOf(16, 64, 128, 235).forEach { y ->
            val gray = convertPixel(y, 128, 128)
            val expected = ColorTransfer.srgbToLinear(y / 255f)
            assertEquals(expected, gray.sample(0, 0, 0), 1e-6f)
            assertEquals(expected, gray.sample(0, 0, 1), 1e-6f)
            assertEquals(expected, gray.sample(0, 0, 2), 1e-6f)
        }

        // These assertions reject the previous endpoint-remapping behavior.
        assertTrue(convertPixel(16, 128, 128).sample(0, 0, 0) > 0f)
        assertTrue(convertPixel(235, 128, 128).sample(0, 0, 0) < 1f)
    }

    @Test
    fun respectsPaddedRowsChromaPixelStrideTwoAndOddDimensions() = runTest {
        val y = byteArrayOf(
            0, 128.toByte(), 255.toByte(), 0, 0,
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
    fun respectsYPixelStrideTwoAndPaddedPixelStrideOneChromaRows() = runTest {
        val width = 3
        val height = 3
        val yData = ByteArray(19)
        val yValues = arrayOf(
            intArrayOf(0, 64, 128),
            intArrayOf(32, 96, 160),
            intArrayOf(128, 200, 235),
        )
        yValues.forEachIndexed { row, values ->
            values.forEachIndexed { column, value -> yData[row * 7 + column * 2] = value.toByte() }
        }
        val uData = byteArrayOf(90, 128.toByte(), 0, 0, 200.toByte(), 128.toByte())
        val vData = byteArrayOf(200.toByte(), 128.toByte(), 0, 0, 90, 128.toByte())
        val frame = OwnedYuvFrame.create(
            width,
            height,
            10,
            OwnedYuvPlane.create(yData, rowStrideBytes = 7, pixelStrideBytes = 2),
            OwnedYuvPlane.create(uData, rowStrideBytes = 4, pixelStrideBytes = 1),
            OwnedYuvPlane.create(vData, rowStrideBytes = 4, pixelStrideBytes = 1),
        )

        val converted = Yuv420Converter.toSceneLinear(frame)
        val neutral235 = ColorTransfer.srgbToLinear(235 / 255f)

        assertEquals(neutral235, converted.sample(2, 2, 0), 1e-6f)
        assertTrue(converted.sample(0, 0, 0) > converted.sample(0, 0, 2))
        assertTrue(converted.sample(0, 2, 2) > converted.sample(0, 2, 0))
    }

    @Test
    fun jfifRec601ChromaUsesFullRangeReferenceCoefficients() = runTest {
        val encoded = Yuv420Converter.jfifRec601FullRange(128, 90, 200)
        val luminance = 128f / 255f
        val cb = (90f - 128f) / 255f
        val cr = (200f - 128f) / 255f

        assertEquals((luminance + 1.402f * cr).coerceIn(0f, 1f), encoded[0], 1e-6f)
        assertEquals(
            (luminance - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 1f),
            encoded[1],
            1e-6f,
        )
        assertEquals((luminance + 1.772f * cb).coerceIn(0f, 1f), encoded[2], 1e-6f)

        val converted = convertPixel(128, 90, 200)
        assertEquals(ColorTransfer.srgbToLinear(encoded[0]), converted.sample(0, 0, 0), 1e-6f)
        assertEquals(ColorTransfer.srgbToLinear(encoded[1]), converted.sample(0, 0, 1), 1e-6f)
        assertEquals(ColorTransfer.srgbToLinear(encoded[2]), converted.sample(0, 0, 2), 1e-6f)

        val blueBiased = Yuv420Converter.jfifRec601FullRange(128, 200, 90)

        assertTrue(encoded[0] > encoded[2])
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
