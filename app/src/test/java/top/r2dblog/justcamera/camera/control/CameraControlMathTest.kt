package top.r2dblog.justcamera.camera.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.SensorRect
import top.r2dblog.justcamera.camera.model.ValueRange
import top.r2dblog.justcamera.camera.model.RationalValue

class CameraControlMathTest {
    @Test
    fun convertsAndFormatsArbitraryShutterValues() {
        assertEquals(4_000_000L, ShutterSpeedFormatter.fractionToNanos(1, 250))
        assertEquals("1/250", ShutterSpeedFormatter.format(4_000_000L))
        assertEquals("2s", ShutterSpeedFormatter.format(2_000_000_000L))
        val range = ValueRange(100_000L, 1_000_000_000L)
        val value = ShutterSpeedFormatter.fromSlider(0.63f, range)
        val restored = ShutterSpeedFormatter.toSlider(value, range)
        assertEquals(0.63f, restored, 0.001f)
    }

    @Test
    fun convertsCompensationStepsUsingReportedRational() {
        assertEquals(1.0, ExposureCompensation.evForSteps(3, RationalValue(1, 3)), 0.0001)
        assertEquals("-1.00 EV", ExposureCompensation.label(-2, RationalValue(1, 2)))
    }

    @Test
    fun calculatesCenteredZoomCropAndBoundedMeteringRegion() {
        val active = SensorRect(0, 0, 4000, 3000)
        val crop = ZoomCropCalculator.crop(active, 2f)
        assertEquals(SensorRect(1000, 750, 3000, 2250), crop)
        assertEquals(2f, ZoomCropCalculator.ratio(active, crop), 0.001f)

        val metering = MeteringRegionMapper.map(1f, 1f, crop, 0, false)
        assertTrue(metering.right <= crop.right)
        assertTrue(metering.bottom <= crop.bottom)
        assertTrue(metering.left >= crop.left)
        assertTrue(metering.top >= crop.top)

        val rotatedFront = MeteringRegionMapper.map(0f, 0f, crop, 90, true)
        assertTrue(rotatedFront.left >= crop.left)
        assertTrue(rotatedFront.right <= crop.right)
        assertTrue(rotatedFront.top >= crop.top)
        assertTrue(rotatedFront.bottom <= crop.bottom)
    }
}
