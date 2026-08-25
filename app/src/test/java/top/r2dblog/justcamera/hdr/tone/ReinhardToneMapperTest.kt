package top.r2dblog.justcamera.hdr.tone

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame

class ReinhardToneMapperTest {
    @Test
    fun mapsWideDynamicRangeToFiniteDisplayRangeMonotonically() = runTest {
        val input = SceneLinearFrame.create(
            5,
            1,
            floatArrayOf(
                0f, 0f, 0f,
                0.18f, 0.18f, 0.18f,
                1f, 1f, 1f,
                4f, 4f, 4f,
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            ),
        )

        val output = ReinhardToneMapper().map(input)
        val red = (0 until output.pixelCount).map { output.sample(it, 0) }

        assertTrue(red.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(output.copyPixels().all { it.isFinite() && it in 0f..1f })
        assertEquals(0.5f, output.sample(2, 0), 1e-5f)
    }

    @Test
    fun luminanceMappingPreservesUnsaturatedChannelRatios() = runTest {
        val output = ReinhardToneMapper().map(
            SceneLinearFrame.create(1, 1, floatArrayOf(0.1f, 0.2f, 0.4f)),
        )

        assertEquals(2f, output.sample(0, 1) / output.sample(0, 0), 1e-4f)
        assertEquals(2f, output.sample(0, 2) / output.sample(0, 1), 1e-4f)
    }
}
