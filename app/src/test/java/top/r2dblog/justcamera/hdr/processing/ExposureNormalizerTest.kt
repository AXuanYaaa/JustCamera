package top.r2dblog.justcamera.hdr.processing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.hdr.model.HdrExposureMetadata
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame

class ExposureNormalizerTest {
    @Test
    fun normalizesUsingActualExposureTimeAndIsoAndRetainsHighlights() = runTest {
        val reference = exposure(timestamp = 1, time = 10, iso = 100, reference = true)
        val longer = exposure(timestamp = 2, time = 20, iso = 100, reference = false)
        val normalizedReference = ExposureNormalizer.normalize(
            frame(1, 0.75f), reference, reference,
        )
        val normalizedLong = ExposureNormalizer.normalize(
            frame(2, 3f), longer, reference,
        )

        assertEquals(0.75f, normalizedReference.sample(0, 0, 0), 1e-6f)
        assertEquals(1.5f, normalizedLong.sample(0, 0, 0), 1e-6f)
        assertEquals(2.0, normalizedLong.normalization!!.exposureRatio, 0.0)
        assertTrue(normalizedLong.sample(0, 0, 0) > 1f)
    }

    @Test
    fun equalSceneAtDoubleExposureNormalizesToSameRadiance() = runTest {
        val reference = exposure(timestamp = 1, time = 10, iso = 100, reference = true)
        val double = exposure(timestamp = 2, time = 20, iso = 100, reference = false)

        val a = ExposureNormalizer.normalize(frame(1, 0.25f), reference, reference)
        val b = ExposureNormalizer.normalize(frame(2, 0.5f), double, reference)

        assertEquals(a.sample(0, 0, 0), b.sample(0, 0, 0), 1e-6f)
    }

    private fun frame(timestamp: Long, value: Float) = SceneLinearFrame.create(
        1, 1, floatArrayOf(value, value, value), timestampNanos = timestamp,
    )

    private fun exposure(timestamp: Long, time: Long, iso: Int, reference: Boolean) =
        HdrExposureMetadata(time, iso, timestamp, timestamp.toInt(), 0.0, reference)
}
