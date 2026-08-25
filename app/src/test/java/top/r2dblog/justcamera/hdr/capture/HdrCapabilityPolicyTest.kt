package top.r2dblog.justcamera.hdr.capture

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.capability.CameraCapabilityMapper
import top.r2dblog.justcamera.camera.capability.RawCameraCharacteristics
import top.r2dblog.justcamera.camera.capability.RawIntRange
import top.r2dblog.justcamera.camera.capability.RawLongRange
import top.r2dblog.justcamera.camera.capability.RawOutput
import top.r2dblog.justcamera.camera.capability.RawSize

class HdrCapabilityPolicyTest {
    @Test
    fun selectsLargestYuvFrameInsideBoundedProcessingBudget() {
        val selected = HdrProcessingSizeSelector.select(
            listOf(RawSize(1920, 1080), RawSize(1280, 720), RawSize(1024, 768)).map {
                top.r2dblog.justcamera.camera.model.ImageSize(it.width, it.height)
            },
        )

        assertEquals(1280, selected?.width)
        assertEquals(720, selected?.height)
        assertTrue(selected!!.area <= HdrProcessingSizeSelector.MAX_PROCESSING_PIXELS)
    }

    @Test
    fun enablesDeterministicManualBracketAndMapsTimingMetadata() {
        val assessment = HdrCapabilityPolicy.assess(camera(manual = true, burst = true))

        assertEquals(HdrSupportLevel.MANUAL_BRACKET_CAPABLE, assessment.level)
        assertTrue(assessment.captureEnabled)
        assertTrue(assessment.burstCapture)
        assertEquals(40_000_000L, assessment.minimumFrameDurationNanos)
        assertEquals(1, assessment.syncMaxLatency)
    }

    @Test
    fun reportsBurstWithoutManualControlButDoesNotEnableUnreliableAeFallback() {
        val assessment = HdrCapabilityPolicy.assess(camera(manual = false, burst = true))

        assertEquals(HdrSupportLevel.BURST_CAPABLE, assessment.level)
        assertFalse(assessment.captureEnabled)
    }

    private fun camera(manual: Boolean, burst: Boolean) = CameraCapabilityMapper.map(
        "0",
        RawCameraCharacteristics(
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientation = 90,
            hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            activeArray = null,
            pixelArraySize = RawSize(4000, 3000),
            sensitivityRange = if (manual) RawIntRange(50, 3200) else null,
            exposureTimeRange = if (manual) RawLongRange(100_000, 100_000_000) else null,
            maxFrameDuration = if (manual) 100_000_000 else null,
            aeCompensationRange = RawIntRange(-6, 6),
            aeCompensationStep = null,
            minimumFocusDistance = null,
            focalLengths = emptyList(),
            apertures = emptyList(),
            afModes = emptyList(),
            aeModes = emptyList(),
            awbModes = emptyList(),
            targetFpsRanges = emptyList(),
            maxDigitalZoom = null,
            zoomRatioRange = null,
            aeLockAvailable = false,
            awbLockAvailable = true,
            maxAfMeteringRegions = 0,
            maxAeMeteringRegions = 0,
            maxAwbMeteringRegions = 0,
            requestCapabilities = buildList {
                if (manual) add(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                if (burst) add(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
            },
            opticalStabilizationModes = emptyList(),
            videoStabilizationModes = emptyList(),
            physicalCameraIds = emptySet(),
            outputs = listOf(
                RawOutput(
                    ImageFormat.YUV_420_888,
                    listOf(RawSize(1920, 1080), RawSize(1280, 720)),
                    mapOf(RawSize(1280, 720) to 40_000_000L),
                ),
            ),
            syncMaxLatency = 1,
        ),
    )
}
