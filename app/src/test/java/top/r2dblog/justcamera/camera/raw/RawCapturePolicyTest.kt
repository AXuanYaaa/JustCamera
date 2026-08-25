package top.r2dblog.justcamera.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.r2dblog.justcamera.camera.control.CaptureModeResolver
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.model.CaptureMode

class RawCapturePolicyTest {
    @Test
    fun selectsLargestRawSizeOnlyWithRawCapability() {
        val sizes = listOf(ImageSize(2000, 1500), ImageSize(4000, 3000))
        assertEquals(ImageSize(4000, 3000), RawCapabilitySelector.selectLargest(true, sizes))
        assertNull(RawCapabilitySelector.selectLargest(false, sizes))
    }

    @Test
    fun captureModeFallsBackToJpegWhenRawIsUnavailable() {
        assertEquals(
            CaptureMode.JPEG_ONLY,
            CaptureModeResolver.resolve(CaptureMode.JPEG_AND_RAW, rawAvailable = false),
        )
        assertEquals(
            CaptureMode.RAW_ONLY,
            CaptureModeResolver.resolve(CaptureMode.RAW_ONLY, rawAvailable = true),
        )
    }
}
