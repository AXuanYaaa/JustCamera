package top.r2dblog.justcamera.imaging.frame

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class RgbChannelLayout(val channelCount: Int) { RGB(3), RGBA(4) }

/**
 * Immutable CPU working frame: display-referred sRGB primaries, linear transfer, normalized
 * finite channels in [0, 1], and optional straight alpha. RAW/YUV/encoded data must be explicitly
 * converted before entering this representation.
 */
class RgbFloatFrame private constructor(
    val width: Int,
    val height: Int,
    val layout: RgbChannelLayout,
    private val samples: FloatArray,
    val timestampNanos: Long,
    val rotationDegrees: Int,
    val metadata: Map<String, FrameMetadataValue>,
    copySamples: Boolean,
) {
    private val expectedPixelCount = width.toLong() * height
    private val expectedSampleCount = if (
        expectedPixelCount <= Int.MAX_VALUE / layout.channelCount
    ) {
        expectedPixelCount * layout.channelCount
    } else {
        Long.MAX_VALUE
    }
    private val pixels = if (copySamples) samples.copyOf() else samples

    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Invalid frame rotation" }
        require(expectedSampleCount <= Int.MAX_VALUE && samples.size == expectedSampleCount.toInt()) {
            "Expected $expectedSampleCount float samples, got ${samples.size}"
        }
        require(samples.all { it.isFinite() && it in 0f..1f }) {
            "Working RGB samples must be finite and normalized to [0, 1]"
        }
    }

    val pixelCount: Int get() = expectedPixelCount.toInt()
    val channelCount: Int get() = layout.channelCount

    fun sample(pixelIndex: Int, channel: Int): Float {
        require(pixelIndex in 0 until pixelCount)
        require(channel in 0 until channelCount)
        return pixels[pixelIndex * channelCount + channel]
    }

    fun copyPixels(): FloatArray = pixels.copyOf()

    /** Copies immutable samples into a caller-owned working buffer without an intermediate array. */
    internal fun copyPixelsTo(destination: FloatBuffer) {
        require(destination.remaining() >= pixels.size) { "Destination float buffer is too small" }
        destination.put(pixels)
    }

    internal fun withOwnedPixels(output: FloatArray): RgbFloatFrame = RgbFloatFrame(
        width = width,
        height = height,
        layout = layout,
        samples = output,
        timestampNanos = timestampNanos,
        rotationDegrees = rotationDegrees,
        metadata = metadata,
        copySamples = false,
    )

    fun toImageFrame(): ImageFrame {
        val buffer = ByteBuffer.allocate(pixels.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(pixels)
        return ImageFrame(
            width = width,
            height = height,
            format = if (layout == RgbChannelLayout.RGB) {
                FrameFormat.RGB_F32
            } else {
                FrameFormat.RGBA_F32
            },
            timestampNanos = timestampNanos,
            rotationDegrees = rotationDegrees,
            planes = listOf(
                ImagePlane(
                    buffer = buffer,
                    rowStrideBytes = width * channelCount * Float.SIZE_BYTES,
                    pixelStrideBytes = channelCount * Float.SIZE_BYTES,
                ),
            ),
            metadata = metadata,
            ownership = BufferOwnership.PIPELINE_OWNED,
            bitDepth = 32,
            channelLayout = if (layout == RgbChannelLayout.RGB) {
                ChannelLayout.RGB
            } else {
                ChannelLayout.RGBA
            },
            colorInfo = ImageFrame.LINEAR_SRGB,
            alphaSemantics = if (layout == RgbChannelLayout.RGB) {
                AlphaSemantics.NONE
            } else {
                AlphaSemantics.STRAIGHT
            },
        )
    }

    companion object {
        fun create(
            width: Int,
            height: Int,
            layout: RgbChannelLayout,
            pixels: FloatArray,
            timestampNanos: Long = 0,
            rotationDegrees: Int = 0,
            metadata: Map<String, FrameMetadataValue> = emptyMap(),
        ): RgbFloatFrame = RgbFloatFrame(
            width,
            height,
            layout,
            pixels,
            timestampNanos,
            rotationDegrees,
            metadata,
            copySamples = true,
        )

        fun fromImageFrame(frame: ImageFrame): RgbFloatFrame {
            val layout = when (frame.format) {
                FrameFormat.RGB_F32 -> RgbChannelLayout.RGB
                FrameFormat.RGBA_F32 -> RgbChannelLayout.RGBA
                else -> throw IllegalArgumentException(
                    "Filter processing requires RGB_F32/RGBA_F32, not ${frame.format}",
                )
            }
            require(frame.colorInfo == ImageFrame.LINEAR_SRGB) {
                "Filter processing requires an explicitly declared linear-sRGB frame"
            }
            val plane = frame.planes.singleOrNull()
                ?: throw IllegalArgumentException("Float RGB frame must have exactly one plane")
            val expectedRow = frame.width * layout.channelCount * Float.SIZE_BYTES
            require(plane.pixelStrideBytes == layout.channelCount * Float.SIZE_BYTES) {
                "Unsupported float RGB pixel stride"
            }
            require(plane.rowStrideBytes == expectedRow) { "Packed float RGB rows are required" }
            val duplicate = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            duplicate.position(plane.offsetBytes)
            val output = FloatArray(frame.width * frame.height * layout.channelCount)
            duplicate.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(output)
            return RgbFloatFrame(
                frame.width,
                frame.height,
                layout,
                output,
                frame.timestampNanos,
                frame.rotationDegrees,
                frame.metadata,
                copySamples = false,
            )
        }
    }
}
