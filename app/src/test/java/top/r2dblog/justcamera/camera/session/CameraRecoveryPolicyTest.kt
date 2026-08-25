package top.r2dblog.justcamera.camera.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CameraErrorCode

class CameraRecoveryPolicyTest {
    private val policy = CameraRecoveryPolicy(maxAttempts = 3, baseDelayMillis = 100)
    private val recoverableError = CameraError(
        CameraErrorCode.SESSION_CONFIGURATION_FAILED,
        "Session failed",
        recoverable = true,
    )

    @Test
    fun retriesRecoverableFailureWithBoundedBackoff() {
        assertEquals(CameraRetryDecision(true, 100), policy.decide(recoverableError, 0, true))
        assertEquals(CameraRetryDecision(true, 200), policy.decide(recoverableError, 1, true))
        assertEquals(CameraRetryDecision(true, 400), policy.decide(recoverableError, 2, true))
        assertFalse(policy.decide(recoverableError, 3, true).shouldRetry)
    }

    @Test
    fun waitsForReopenPrerequisitesInsteadOfSchedulingADeadRetry() {
        assertFalse(policy.decide(recoverableError, 0, false).shouldRetry)
    }

    @Test
    fun neverRetriesNonRecoverableFailure() {
        val fatal = recoverableError.copy(recoverable = false)
        assertFalse(policy.decide(fatal, 0, true).shouldRetry)
        assertTrue(policy.decide(recoverableError, 0, true).shouldRetry)
    }
}
