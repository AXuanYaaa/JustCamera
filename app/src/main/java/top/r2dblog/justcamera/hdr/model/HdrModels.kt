package top.r2dblog.justcamera.hdr.model

import top.r2dblog.justcamera.hdr.yuv.OwnedYuvFrame
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

data class HdrExposureMetadata(
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val timestampNanos: Long,
    val frameIndex: Int,
    val evRelativeToReference: Double,
    val isReference: Boolean,
    val focusDistanceDiopters: Float? = null,
) {
    init {
        require(exposureTimeNanos > 0)
        require(sensitivityIso > 0)
        require(timestampNanos >= 0)
        require(frameIndex >= 0)
        require(evRelativeToReference.isFinite())
        require(focusDistanceDiopters == null || focusDistanceDiopters.isFinite())
    }

    val exposureScalar: Double
        get() = exposureTimeNanos.toDouble() * sensitivityIso.toDouble()
}

data class HdrInputFrame(
    val yuv: OwnedYuvFrame,
    val exposure: HdrExposureMetadata,
) {
    init {
        require(yuv.timestampNanos == exposure.timestampNanos) {
            "YUV and exposure metadata timestamps must match"
        }
    }
}

data class HdrFrameSet(val frames: List<HdrInputFrame>, val referenceFrameIndex: Int) {
    init {
        require(frames.size >= 2) { "HDR requires at least two frames" }
        require(frames.map { it.exposure.frameIndex }.distinct().size == frames.size)
        require(frames.count { it.exposure.isReference } == 1)
        require(frames.any { it.exposure.frameIndex == referenceFrameIndex && it.exposure.isReference })
        val first = frames.first().yuv
        require(frames.all { it.yuv.width == first.width && it.yuv.height == first.height }) {
            "HDR frames must have identical dimensions"
        }
    }

    val reference: HdrInputFrame
        get() = frames.single { it.exposure.frameIndex == referenceFrameIndex }
}

data class HdrAlignedFrame(
    val frame: SceneLinearFrame,
    val exposure: HdrExposureMetadata,
    val exposureRatio: Double,
    val translation: Translation,
    val alignmentConfidence: Float,
    val validRegion: ValidRegion,
)

data class Translation(val dx: Int, val dy: Int) {
    companion object { val IDENTITY = Translation(0, 0) }
}

data class ValidRegion(val left: Int, val top: Int, val rightExclusive: Int, val bottomExclusive: Int) {
    init {
        require(left <= rightExclusive && top <= bottomExclusive)
    }

    fun contains(x: Int, y: Int): Boolean =
        x in left until rightExclusive && y in top until bottomExclusive
}

enum class HdrProcessingStage { CONVERTING, ALIGNING, MERGING, TONE_MAPPING }

data class HdrProcessingDiagnostics(
    val translations: Map<Int, Translation>,
    val alignmentConfidence: Map<Int, Float>,
    val stageMillis: Map<HdrProcessingStage, Long>,
)

data class HdrProcessingOutput(
    val sceneLinearHdr: SceneLinearFrame,
    val toneMapped: RgbFloatFrame,
    val diagnostics: HdrProcessingDiagnostics,
)
