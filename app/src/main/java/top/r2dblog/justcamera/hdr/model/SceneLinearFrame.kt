package top.r2dblog.justcamera.hdr.model

import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue

enum class SceneColorContract { ISP_DERIVED_LINEAR_SRGB_BT709 }

enum class SceneChannelLayout(val channelCount: Int) { RGB(3) }

data class ExposureNormalizationMetadata(
    val actualExposureTimeNanos: Long,
    val actualSensitivityIso: Int,
    val exposureScalar: Double,
    val referenceExposureScalar: Double,
    val exposureRatio: Double,
) {
    init {
        require(actualExposureTimeNanos > 0)
        require(actualSensitivityIso > 0)
        require(exposureScalar.isFinite() && exposureScalar > 0.0)
        require(referenceExposureScalar.isFinite() && referenceExposureScalar > 0.0)
        require(exposureRatio.isFinite() && exposureRatio > 0.0)
    }
}

/**
 * Immutable scene-referred HDR working frame. Values are finite and non-negative but deliberately
 * unbounded above; this type must never be substituted for PH3's display-referred RgbFloatFrame.
 */
class SceneLinearFrame private constructor(
    val width: Int,
    val height: Int,
    val rowStrideFloats: Int,
    val layout: SceneChannelLayout,
    val timestampNanos: Long,
    val colorContract: SceneColorContract,
    val normalization: ExposureNormalizationMetadata?,
    val metadata: Map<String, FrameMetadataValue>,
    samples: FloatArray,
    copySamples: Boolean,
) {
    private val values = if (copySamples) samples.copyOf() else samples

    init {
        require(width > 0 && height > 0) { "Scene frame dimensions must be positive" }
        require(rowStrideFloats >= width * layout.channelCount) { "Scene row stride is too small" }
        val required = (height - 1L) * rowStrideFloats + width.toLong() * layout.channelCount
        require(required <= Int.MAX_VALUE && values.size >= required.toInt()) {
            "Scene frame buffer is too small: required=$required actual=${values.size}"
        }
        require(values.all { it.isFinite() && it >= 0f }) {
            "Scene-linear values must be finite and non-negative"
        }
    }

    val pixelCount: Int get() = width * height
    val channelCount: Int get() = layout.channelCount

    fun sample(x: Int, y: Int, channel: Int): Float {
        require(x in 0 until width && y in 0 until height)
        require(channel in 0 until channelCount)
        return values[y * rowStrideFloats + x * channelCount + channel]
    }

    fun copySamples(): FloatArray = values.copyOf()

    internal fun sampleUnchecked(x: Int, y: Int, channel: Int): Float =
        values[y * rowStrideFloats + x * channelCount + channel]

    internal fun withOwnedSamples(
        output: FloatArray,
        normalization: ExposureNormalizationMetadata? = this.normalization,
        metadata: Map<String, FrameMetadataValue> = this.metadata,
    ): SceneLinearFrame = SceneLinearFrame(
        width,
        height,
        width * channelCount,
        layout,
        timestampNanos,
        colorContract,
        normalization,
        metadata,
        output,
        copySamples = false,
    )

    companion object {
        fun create(
            width: Int,
            height: Int,
            samples: FloatArray,
            rowStrideFloats: Int = width * SceneChannelLayout.RGB.channelCount,
            timestampNanos: Long = 0L,
            colorContract: SceneColorContract = SceneColorContract.ISP_DERIVED_LINEAR_SRGB_BT709,
            normalization: ExposureNormalizationMetadata? = null,
            metadata: Map<String, FrameMetadataValue> = emptyMap(),
        ): SceneLinearFrame = SceneLinearFrame(
            width,
            height,
            rowStrideFloats,
            SceneChannelLayout.RGB,
            timestampNanos,
            colorContract,
            normalization,
            metadata,
            samples,
            copySamples = true,
        )

        internal fun fromOwnedSamples(
            width: Int,
            height: Int,
            samples: FloatArray,
            timestampNanos: Long = 0L,
            normalization: ExposureNormalizationMetadata? = null,
            metadata: Map<String, FrameMetadataValue> = emptyMap(),
        ): SceneLinearFrame = SceneLinearFrame(
            width,
            height,
            width * SceneChannelLayout.RGB.channelCount,
            SceneChannelLayout.RGB,
            timestampNanos,
            SceneColorContract.ISP_DERIVED_LINEAR_SRGB_BT709,
            normalization,
            metadata,
            samples,
            copySamples = false,
        )
    }
}
