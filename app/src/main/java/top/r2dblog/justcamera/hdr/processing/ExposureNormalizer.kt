package top.r2dblog.justcamera.hdr.processing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.hdr.model.ExposureNormalizationMetadata
import top.r2dblog.justcamera.hdr.model.HdrExposureMetadata
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame

object ExposureNormalizer {
    suspend fun normalize(
        frame: SceneLinearFrame,
        actual: HdrExposureMetadata,
        reference: HdrExposureMetadata,
    ): SceneLinearFrame {
        require(frame.timestampNanos == actual.timestampNanos)
        val scalar = actual.exposureScalar
        val referenceScalar = reference.exposureScalar
        val ratio = scalar / referenceScalar
        require(ratio.isFinite() && ratio > 0.0)
        val output = FloatArray(frame.width * frame.height * frame.channelCount)
        var outputOffset = 0
        for (row in 0 until frame.height) {
            currentCoroutineContext().ensureActive()
            for (column in 0 until frame.width) {
                for (channel in 0 until frame.channelCount) {
                    val normalized = frame.sampleUnchecked(column, row, channel).toDouble() / ratio
                    require(normalized.isFinite() && normalized >= 0.0) {
                        "Exposure normalization produced an invalid scene-linear value"
                    }
                    output[outputOffset++] = normalized.coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
                }
            }
        }
        return SceneLinearFrame.fromOwnedSamples(
            frame.width,
            frame.height,
            output,
            timestampNanos = frame.timestampNanos,
            normalization = ExposureNormalizationMetadata(
                actual.exposureTimeNanos,
                actual.sensitivityIso,
                scalar,
                referenceScalar,
                ratio,
            ),
            metadata = frame.metadata,
        )
    }
}
