package top.r2dblog.justcamera.camera.session

import top.r2dblog.justcamera.camera.model.CameraError

data class CameraRetryDecision(val shouldRetry: Boolean, val delayMillis: Long = 0)

class CameraRecoveryPolicy(
    private val maxAttempts: Int = 3,
    private val baseDelayMillis: Long = 250,
) {
    init {
        require(maxAttempts >= 0)
        require(baseDelayMillis >= 0)
    }

    fun decide(
        error: CameraError,
        completedAttempts: Int,
        reopenPrerequisitesReady: Boolean,
    ): CameraRetryDecision {
        if (!error.recoverable || !reopenPrerequisitesReady || completedAttempts >= maxAttempts) {
            return CameraRetryDecision(shouldRetry = false)
        }
        val multiplier = 1L shl completedAttempts.coerceAtMost(3)
        return CameraRetryDecision(
            shouldRetry = true,
            delayMillis = baseDelayMillis * multiplier,
        )
    }
}
