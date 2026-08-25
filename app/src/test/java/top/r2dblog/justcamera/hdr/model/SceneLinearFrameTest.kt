package top.r2dblog.justcamera.hdr.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

class SceneLinearFrameTest {
    @Test
    fun retainsFiniteValuesAboveDisplayRangeAndDefensivelyCopies() {
        val source = floatArrayOf(0.1f, 1f, 4.5f)
        val frame = SceneLinearFrame.create(1, 1, source)
        source[2] = 0f

        assertEquals(4.5f, frame.sample(0, 0, 2), 0f)
        assertEquals(SceneColorContract.ISP_DERIVED_LINEAR_SRGB_BT709, frame.colorContract)
    }

    @Test
    fun rejectsNegativeAndNonFiniteSceneSamples() {
        assertThrows(IllegalArgumentException::class.java) {
            SceneLinearFrame.create(1, 1, floatArrayOf(0f, -0.1f, 1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SceneLinearFrame.create(1, 1, floatArrayOf(0f, Float.NaN, 1f))
        }
    }

    @Test
    fun displayWorkingFrameStillRejectsHdrValues() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbFloatFrame.create(1, 1, RgbChannelLayout.RGB, floatArrayOf(0f, 1f, 1.01f))
        }
    }
}
