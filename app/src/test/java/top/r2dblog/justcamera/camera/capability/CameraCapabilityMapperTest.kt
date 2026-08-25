package top.r2dblog.justcamera.camera.capability

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.CameraCapability
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.CameraOutputFormat
import top.r2dblog.justcamera.camera.model.HardwareLevel

class CameraCapabilityMapperTest {
    @Test
    fun mapsPlatformValuesAndGracefullyDefaultsOptionalFields() {
        val mapped = CameraCapabilityMapper.map(
            "rear-wide",
            RawCameraCharacteristics(
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                sensorOrientation = 90,
                hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                activeArray = RawRect(8, 6, 4008, 3006),
                pixelArraySize = RawSize(4032, 3024),
                sensitivityRange = RawIntRange(50, 6400),
                exposureTimeRange = RawLongRange(100_000, 30_000_000_000),
                maxFrameDuration = 33_333_333,
                minimumFocusDistance = 10f,
                focalLengths = listOf(5.6f),
                apertures = listOf(1.8f),
                afModes = listOf(4),
                aeModes = listOf(1),
                awbModes = listOf(1),
                targetFpsRanges = listOf(RawFpsRange(15, 30)),
                maxDigitalZoom = null,
                requestCapabilities = listOf(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                ),
                opticalStabilizationModes = listOf(0, 1),
                videoStabilizationModes = listOf(0),
                physicalCameraIds = setOf("2", "3"),
                outputs = listOf(RawOutput(ImageFormat.JPEG, listOf(RawSize(4000, 3000)))),
            ),
        )

        assertEquals("rear-wide", mapped.cameraId)
        assertEquals(CameraFacing.BACK, mapped.facing)
        assertEquals(HardwareLevel.FULL, mapped.hardwareLevel)
        assertEquals(1f, mapped.maxDigitalZoom)
        assertTrue(CameraCapability.RAW in mapped.capabilities)
        assertTrue(CameraCapability.MANUAL_SENSOR in mapped.capabilities)
        assertTrue(mapped.opticalStabilization)
        assertEquals(CameraOutputFormat.JPEG, mapped.outputs.single().format)
        assertEquals(12_000_000L, mapped.outputs.single().sizes.single().area)
    }
}
