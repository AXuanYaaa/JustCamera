package top.r2dblog.justcamera.camera.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLifecyclePolicyTest {
    @Test
    fun staleGenerationOutputIsIgnored() {
        assertFalse(CaptureGenerationPolicy.isCurrent(outputGeneration = 4, activeGeneration = 5))
        assertTrue(CaptureGenerationPolicy.isCurrent(outputGeneration = 5, activeGeneration = 5))
    }

    @Test
    fun captureTimeoutReturnsHealthyCurrentSessionToPreview() {
        assertTrue(
            CapturePreviewRecoveryPolicy.shouldReturnToPreview(
                terminalGeneration = 8,
                currentGeneration = 8,
                sessionHealthy = true,
                cameraStateCapturing = true,
            ),
        )
        assertFalse(
            CapturePreviewRecoveryPolicy.shouldReturnToPreview(
                terminalGeneration = 7,
                currentGeneration = 8,
                sessionHealthy = true,
                cameraStateCapturing = true,
            ),
        )
        assertFalse(
            CapturePreviewRecoveryPolicy.shouldReturnToPreview(
                terminalGeneration = 8,
                currentGeneration = 8,
                sessionHealthy = false,
                cameraStateCapturing = true,
            ),
        )
    }
}
