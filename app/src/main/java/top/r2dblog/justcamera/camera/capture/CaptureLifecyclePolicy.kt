package top.r2dblog.justcamera.camera.capture

object CaptureGenerationPolicy {
    fun isCurrent(outputGeneration: Long, activeGeneration: Long): Boolean =
        outputGeneration == activeGeneration
}

object CapturePreviewRecoveryPolicy {
    fun shouldReturnToPreview(
        terminalGeneration: Long,
        currentGeneration: Long,
        sessionHealthy: Boolean,
        cameraStateCapturing: Boolean,
    ): Boolean = terminalGeneration == currentGeneration && sessionHealthy && cameraStateCapturing
}
