package top.r2dblog.justcamera.hdr.processing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.hdr.alignment.TranslationAlignmentEstimator
import top.r2dblog.justcamera.hdr.merge.HdrRadianceMerger
import top.r2dblog.justcamera.hdr.model.HdrAlignedFrame
import top.r2dblog.justcamera.hdr.model.HdrFrameSet
import top.r2dblog.justcamera.hdr.model.HdrProcessingDiagnostics
import top.r2dblog.justcamera.hdr.model.HdrProcessingOutput
import top.r2dblog.justcamera.hdr.model.HdrProcessingStage
import top.r2dblog.justcamera.hdr.model.Translation
import top.r2dblog.justcamera.hdr.model.ValidRegion
import top.r2dblog.justcamera.hdr.tone.ReinhardToneMapper
import top.r2dblog.justcamera.hdr.yuv.Yuv420Converter

class HdrProcessingPipeline(
    private val alignmentEstimator: TranslationAlignmentEstimator = TranslationAlignmentEstimator(),
    private val merger: HdrRadianceMerger = HdrRadianceMerger(),
    private val toneMapper: ReinhardToneMapper = ReinhardToneMapper(),
) {
    suspend fun process(
        input: HdrFrameSet,
        rotationDegrees: Int = 0,
        onStage: (HdrProcessingStage) -> Unit = {},
    ): HdrProcessingOutput {
        val timings = linkedMapOf<HdrProcessingStage, Long>()
        fun begin(stage: HdrProcessingStage): Long {
            onStage(stage)
            return System.nanoTime()
        }
        fun end(stage: HdrProcessingStage, started: Long) {
            timings[stage] = (System.nanoTime() - started) / 1_000_000L
        }

        var started = begin(HdrProcessingStage.CONVERTING)
        val referenceExposure = input.reference.exposure
        val normalized = input.frames.sortedBy { it.exposure.frameIndex }.map { captured ->
            currentCoroutineContext().ensureActive()
            val linear = Yuv420Converter.toSceneLinear(captured.yuv)
            captured.exposure to ExposureNormalizer.normalize(
                linear,
                captured.exposure,
                referenceExposure,
            )
        }
        end(HdrProcessingStage.CONVERTING, started)

        started = begin(HdrProcessingStage.ALIGNING)
        val reference = normalized.single { it.first.isReference }
        val translations = linkedMapOf<Int, Translation>()
        val confidence = linkedMapOf<Int, Float>()
        val aligned = normalized.map { (exposure, frame) ->
            currentCoroutineContext().ensureActive()
            if (exposure.isReference) {
                translations[exposure.frameIndex] = Translation.IDENTITY
                confidence[exposure.frameIndex] = 1f
                HdrAlignedFrame(
                    frame,
                    exposure,
                    frame.normalization!!.exposureRatio,
                    Translation.IDENTITY,
                    1f,
                    ValidRegion(0, 0, frame.width, frame.height),
                )
            } else {
                val estimate = alignmentEstimator.estimate(reference.second, frame)
                translations[exposure.frameIndex] = estimate.translation
                confidence[exposure.frameIndex] = estimate.confidence
                HdrAlignedFrame(
                    frame,
                    exposure,
                    frame.normalization!!.exposureRatio,
                    estimate.translation,
                    estimate.confidence,
                    estimate.validRegion,
                )
            }
        }
        end(HdrProcessingStage.ALIGNING, started)

        currentCoroutineContext().ensureActive()
        started = begin(HdrProcessingStage.MERGING)
        val sceneHdr = merger.merge(aligned)
        end(HdrProcessingStage.MERGING, started)

        currentCoroutineContext().ensureActive()
        started = begin(HdrProcessingStage.TONE_MAPPING)
        val displayFrame = toneMapper.map(sceneHdr, rotationDegrees)
        end(HdrProcessingStage.TONE_MAPPING, started)
        return HdrProcessingOutput(
            sceneHdr,
            displayFrame,
            HdrProcessingDiagnostics(translations, confidence, timings),
        )
    }
}
