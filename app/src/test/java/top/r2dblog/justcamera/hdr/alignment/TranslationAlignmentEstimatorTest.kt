package top.r2dblog.justcamera.hdr.alignment

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame
import top.r2dblog.justcamera.hdr.model.Translation

class TranslationAlignmentEstimatorTest {
    @Test
    fun recoversPositiveAndNegativeIntegerTranslation() = runTest {
        val reference = texturedFrame(40, 36)
        val candidate = shifted(reference, dx = 3, dy = -2)

        val estimate = TranslationAlignmentEstimator(maximumShiftPixels = 6).estimate(
            reference,
            candidate,
        )

        assertEquals(Translation(3, -2), estimate.translation)
        assertTrue(estimate.confidence > 0f)
        assertEquals(0, estimate.validRegion.left)
        assertEquals(2, estimate.validRegion.top)
        assertEquals(37, estimate.validRegion.rightExclusive)
    }

    @Test
    fun lowTextureFrameReturnsIdentityWithZeroConfidence() = runTest {
        val flat = SceneLinearFrame.create(16, 16, FloatArray(16 * 16 * 3) { 0.2f })

        val estimate = TranslationAlignmentEstimator().estimate(flat, flat)

        assertEquals(Translation.IDENTITY, estimate.translation)
        assertEquals(0f, estimate.confidence, 0f)
    }

    @Test
    fun texturedNoShiftReturnsIdentity() = runTest {
        val frame = texturedFrame(32, 32)

        val estimate = TranslationAlignmentEstimator(maximumShiftPixels = 5).estimate(frame, frame)

        assertEquals(Translation.IDENTITY, estimate.translation)
        assertTrue(estimate.validRegion.contains(31, 31))
    }

    private fun texturedFrame(width: Int, height: Int): SceneLinearFrame {
        val values = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = (((x * 17 + y * 31 + x * y * 3) % 101) / 100f)
                val offset = (y * width + x) * 3
                values[offset] = value
                values[offset + 1] = value
                values[offset + 2] = value
            }
        }
        return SceneLinearFrame.create(width, height, values)
    }

    /** Writes reference(x,y) at candidate(x+dx,y+dy), matching estimator translation semantics. */
    private fun shifted(reference: SceneLinearFrame, dx: Int, dy: Int): SceneLinearFrame {
        val output = FloatArray(reference.width * reference.height * 3)
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                val targetX = x + dx
                val targetY = y + dy
                if (targetX !in 0 until reference.width || targetY !in 0 until reference.height) continue
                val target = (targetY * reference.width + targetX) * 3
                for (channel in 0..2) output[target + channel] = reference.sample(x, y, channel)
            }
        }
        return SceneLinearFrame.create(reference.width, reference.height, output)
    }
}
