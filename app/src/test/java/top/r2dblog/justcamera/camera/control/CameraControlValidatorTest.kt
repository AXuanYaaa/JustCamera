package top.r2dblog.justcamera.camera.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.ValueRange
import top.r2dblog.justcamera.camera.model.CameraControlCapabilities
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.RationalValue
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode

class CameraControlValidatorTest {
    private val capabilities = CameraControlCapabilities(
        manualSensor = true,
        sensitivityRange = ValueRange(50, 3200),
        exposureTimeRangeNanos = ValueRange(100_000L, 2_000_000_000L),
        maxFrameDurationNanos = 1_000_000_000L,
        exposureCompensationRange = ValueRange(-4, 4),
        exposureCompensationStep = RationalValue(1, 2),
        focusModes = setOf(FocusMode.CONTINUOUS_PICTURE, FocusMode.MANUAL),
        minimumFocusDistanceDiopters = 8f,
        whiteBalanceModes = setOf(WhiteBalanceMode.AUTO, WhiteBalanceMode.DAYLIGHT),
        maxDigitalZoom = 4f,
        rawAvailable = true,
    )

    @Test
    fun clampsManualIsoShutterAndZoomToReportedRanges() {
        val update = CameraControlValidator.validate(
            CameraControlState(
                exposureMode = ExposureMode.MANUAL,
                iso = 6400,
                exposureTimeNs = 3_000_000_000L,
                focusMode = FocusMode.CONTINUOUS_PICTURE,
                whiteBalanceMode = WhiteBalanceMode.AUTO,
                zoomRatio = 8f,
            ),
            capabilities,
        )

        assertTrue(update.accepted)
        assertEquals(3200, update.state.iso)
        assertEquals(1_000_000_000L, update.state.exposureTimeNs)
        assertEquals(4f, update.state.zoomRatio)
        assertTrue(update.messages.isNotEmpty())
    }

    @Test
    fun rejectsManualExposureWhenSensorCapabilityIsMissing() {
        val update = CameraControlValidator.validate(
            CameraControlState(
                exposureMode = ExposureMode.MANUAL,
                iso = 100,
                exposureTimeNs = 10_000_000,
                focusMode = FocusMode.CONTINUOUS_PICTURE,
                whiteBalanceMode = WhiteBalanceMode.AUTO,
            ),
            capabilities.copy(manualSensor = false),
        )
        assertFalse(update.accepted)
    }

    @Test
    fun clampsManualFocusDistanceAndRejectsUnsupportedFocusMode() {
        val clamped = CameraControlValidator.validate(
            CameraControlState(
                focusMode = FocusMode.MANUAL,
                focusDistanceDiopters = 20f,
                whiteBalanceMode = WhiteBalanceMode.AUTO,
            ),
            capabilities,
        )
        assertTrue(clamped.accepted)
        assertEquals(8f, clamped.state.focusDistanceDiopters)

        val rejected = CameraControlValidator.validate(
            clamped.state.copy(focusMode = FocusMode.MACRO),
            capabilities,
        )
        assertFalse(rejected.accepted)
    }

    @Test
    fun retainsBasicAutomaticJpegStateWhenOptionalModesAreNotReported() {
        val update = CameraControlValidator.validate(
            CameraControlState(),
            CameraControlCapabilities(),
        )
        assertTrue(update.accepted)
        assertEquals(CaptureMode.JPEG_ONLY, update.state.captureMode)
    }
}
