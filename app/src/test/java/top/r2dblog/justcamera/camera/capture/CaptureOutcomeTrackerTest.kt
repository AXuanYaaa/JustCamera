package top.r2dblog.justcamera.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CameraErrorCode
import top.r2dblog.justcamera.camera.model.CaptureOutputType
import top.r2dblog.justcamera.camera.model.CaptureStatus

class CaptureOutcomeTrackerTest {
    @Test
    fun reportsPartialSuccessWhenOnlyOneCombinedOutputSaves() {
        val tracker = CaptureOutcomeTracker(
            CaptureMode.JPEG_AND_RAW,
            setOf(CaptureOutputType.JPEG, CaptureOutputType.DNG),
        )
        assertTrue(tracker.begin(CaptureOutputType.JPEG))
        tracker.succeed(CaptureOutputType.JPEG, "capture.jpg")
        assertTrue(tracker.begin(CaptureOutputType.DNG))
        val status = tracker.fail(
            CaptureOutputType.DNG,
            CameraError(CameraErrorCode.DNG_ENCODING_FAILED, "DNG failed"),
        )

        assertTrue(status is CaptureStatus.PartialSuccess)
        status as CaptureStatus.PartialSuccess
        assertEquals("capture.jpg", status.outputs.single().displayName)
        assertEquals(CaptureOutputType.DNG, status.failures.single().type)
    }

    @Test
    fun timeoutKeepsAlreadySavedJpegAsPartialSuccess() {
        val tracker = CaptureOutcomeTracker(
            CaptureMode.JPEG_AND_RAW,
            setOf(CaptureOutputType.JPEG, CaptureOutputType.DNG),
        )
        tracker.begin(CaptureOutputType.JPEG)
        tracker.succeed(CaptureOutputType.JPEG, "capture.jpg")

        val status = tracker.failPending { type ->
            CameraError(CameraErrorCode.RAW_PAIRING_FAILED, "Timed out waiting for ${type.name}")
        }

        assertTrue(status is CaptureStatus.PartialSuccess)
        status as CaptureStatus.PartialSuccess
        assertEquals(listOf(CaptureOutputType.JPEG), status.outputs.map { it.type })
        assertEquals(listOf(CaptureOutputType.DNG), status.failures.map { it.type })
    }
}
