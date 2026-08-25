package top.r2dblog.justcamera.imaging.frame

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RgbFloatFrameTest {
    @Test
    fun rgbaWorkingFrameRoundTripsWithExplicitColorContract() {
        val source = RgbFloatFrame.create(
            1,
            1,
            RgbChannelLayout.RGBA,
            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f),
            timestampNanos = 42,
            rotationDegrees = 90,
        )

        val imageFrame = source.toImageFrame()
        val decoded = RgbFloatFrame.fromImageFrame(imageFrame)

        assertEquals(FrameFormat.RGBA_F32, imageFrame.format)
        assertEquals(ImageFrame.LINEAR_SRGB, imageFrame.colorInfo)
        assertEquals(AlphaSemantics.STRAIGHT, imageFrame.alphaSemantics)
        assertArrayEquals(source.copyPixels(), decoded.copyPixels(), 0f)
    }

    @Test
    fun rawSensorFrameCannotEnterDisplayFilterWorkingSpace() {
        val raw = ImageFrame(
            width = 1,
            height = 1,
            format = FrameFormat.RAW_SENSOR,
            timestampNanos = 0,
            rotationDegrees = 0,
            planes = listOf(ImagePlane(ByteBuffer.allocate(2), 2, 2)),
            bitDepth = 16,
            channelLayout = ChannelLayout.RAW_MOSAIC,
        )

        assertThrows(IllegalArgumentException::class.java) {
            RgbFloatFrame.fromImageFrame(raw)
        }
    }
}
