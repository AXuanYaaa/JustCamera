package top.r2dblog.justcamera.hdr.processing

import org.junit.Assert.assertEquals
import org.junit.Test
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame

class LuminanceHistogramTest {
    @Test
    fun countsDeepShadowsAndSceneHighlightsWithoutClippingTheSource() {
        val frame = SceneLinearFrame.create(
            3,
            1,
            floatArrayOf(0f, 0f, 0f, 0.5f, 0.5f, 0.5f, 2f, 2f, 2f),
        )

        val histogram = LuminanceHistogramCalculator.calculate(frame, binCount = 8)

        assertEquals(3, histogram.sampleCount)
        assertEquals(1, histogram.deepShadows)
        assertEquals(1, histogram.clippedHighlights)
        assertEquals(3, histogram.bins.sum())
        assertEquals(2f, frame.sample(2, 0, 0), 0f)
    }
}
