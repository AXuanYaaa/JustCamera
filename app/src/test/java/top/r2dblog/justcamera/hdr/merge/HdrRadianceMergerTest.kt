package top.r2dblog.justcamera.hdr.merge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.hdr.model.HdrAlignedFrame
import top.r2dblog.justcamera.hdr.model.HdrExposureMetadata
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame
import top.r2dblog.justcamera.hdr.model.Translation
import top.r2dblog.justcamera.hdr.model.ValidRegion

class HdrRadianceMergerTest {
    @Test
    fun shorterExposureRecoversHighlightAboveDisplayRange() = runTest {
        val reference = aligned(value = 1f, ratio = 1.0, reference = true, index = 1)
        val short = aligned(value = 1.6f, ratio = 0.25, reference = false, index = 0)

        val merged = HdrRadianceMerger().merge(listOf(short, reference))

        assertTrue(merged.sample(0, 0, 0) > 1.5f)
        assertTrue(merged.copySamples().all { it.isFinite() && it >= 0f })
    }

    @Test
    fun identicalNormalizedFramesMergeDeterministicallyWithoutDivisionByZero() = runTest {
        val reference = aligned(value = 0f, ratio = 1.0, reference = true, index = 1)
        val candidate = aligned(value = 0f, ratio = 4.0, reference = false, index = 2)
        val merger = HdrRadianceMerger()

        val first = merger.merge(listOf(reference, candidate))
        val second = merger.merge(listOf(reference, candidate))

        assertEquals(0f, first.sample(0, 0, 0), 0f)
        assertTrue(first.copySamples().contentEquals(second.copySamples()))
    }

    @Test
    fun motionDisagreementSuppressesCandidateGhost() = runTest {
        val reference = aligned(value = 0.4f, ratio = 1.0, reference = true, index = 1)
        val movedObject = aligned(value = 0.9f, ratio = 1.0, reference = false, index = 2)

        val merged = HdrRadianceMerger().merge(listOf(reference, movedObject))

        assertEquals(0.4f, merged.sample(0, 0, 0), 0.002f)
    }

    @Test
    fun longerExposureRecoversReferenceDeepShadow() = runTest {
        val reference = aligned(value = 0.001f, ratio = 1.0, reference = true, index = 1)
        val long = aligned(value = 0.05f, ratio = 4.0, reference = false, index = 2)

        val merged = HdrRadianceMerger().merge(listOf(reference, long))

        assertTrue(merged.sample(0, 0, 0) > 0.045f)
    }

    @Test
    fun staticBackgroundMergesWhileMovingPatchPrefersReference() = runTest {
        val reference = alignedPixels(floatArrayOf(0.4f, 0.4f), true, 1)
        val candidate = alignedPixels(floatArrayOf(0.4f, 0.9f), false, 2)

        val merged = HdrRadianceMerger().merge(listOf(reference, candidate))

        assertEquals(0.4f, merged.sample(0, 0, 0), 0.002f)
        assertEquals(0.4f, merged.sample(1, 0, 0), 0.002f)
    }

    private fun aligned(value: Float, ratio: Double, reference: Boolean, index: Int): HdrAlignedFrame {
        val frame = SceneLinearFrame.create(1, 1, floatArrayOf(value, value, value))
        return HdrAlignedFrame(
            frame,
            HdrExposureMetadata(1, 100, index.toLong(), index, 0.0, reference),
            ratio,
            Translation.IDENTITY,
            1f,
            ValidRegion(0, 0, 1, 1),
        )
    }

    private fun alignedPixels(values: FloatArray, reference: Boolean, index: Int): HdrAlignedFrame {
        val samples = FloatArray(values.size * 3)
        values.forEachIndexed { pixel, value ->
            samples[pixel * 3] = value
            samples[pixel * 3 + 1] = value
            samples[pixel * 3 + 2] = value
        }
        return HdrAlignedFrame(
            SceneLinearFrame.create(values.size, 1, samples),
            HdrExposureMetadata(1, 100, index.toLong(), index, 0.0, reference),
            1.0,
            Translation.IDENTITY,
            1f,
            ValidRegion(0, 0, values.size, 1),
        )
    }
}
