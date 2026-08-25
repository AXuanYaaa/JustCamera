package top.r2dblog.justcamera.imaging.frame

import java.nio.ByteBuffer

enum class FrameFormat { JPEG, YUV_420_888, RAW_SENSOR, RGBA_8888, UNKNOWN }

enum class BufferOwnership {
    /** The frame may read the buffer only while its producer-defined lifetime is active. */
    BORROWED,

    /** The frame/pipeline owns the buffer and may pass ownership to its output. */
    PIPELINE_OWNED,
}

data class ImagePlane(
    val buffer: ByteBuffer,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
    val offsetBytes: Int = 0,
) {
    init {
        require(rowStrideBytes > 0) { "rowStrideBytes must be positive" }
        require(pixelStrideBytes > 0) { "pixelStrideBytes must be positive" }
        require(offsetBytes >= 0) { "offsetBytes must not be negative" }
    }
}

sealed interface FrameMetadataValue {
    data class Integer(val value: Long) : FrameMetadataValue
    data class Decimal(val value: Double) : FrameMetadataValue
    data class Text(val value: String) : FrameMetadataValue
    data class Flag(val value: Boolean) : FrameMetadataValue
}

data class ImageFrame(
    val width: Int,
    val height: Int,
    val format: FrameFormat,
    val timestampNanos: Long,
    val rotationDegrees: Int,
    val planes: List<ImagePlane>,
    val metadata: Map<String, FrameMetadataValue> = emptyMap(),
    val ownership: BufferOwnership = BufferOwnership.BORROWED,
) {
    init {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be 0, 90, 180, or 270"
        }
        require(planes.isNotEmpty()) { "An ImageFrame must contain at least one plane" }
    }
}
