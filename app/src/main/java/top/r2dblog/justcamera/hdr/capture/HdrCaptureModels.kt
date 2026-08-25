package top.r2dblog.justcamera.hdr.capture

import top.r2dblog.justcamera.hdr.model.HdrProcessingDiagnostics

enum class HdrMode { OFF, ON }

sealed interface HdrStatus {
    data object Idle : HdrStatus
    data object Planning : HdrStatus
    data class Capturing(val capturedFrames: Int, val totalFrames: Int) : HdrStatus
    data class Converting(val frameCount: Int) : HdrStatus
    data object Aligning : HdrStatus
    data object Merging : HdrStatus
    data object ToneMapping : HdrStatus
    data class Completed(
        val width: Int,
        val height: Int,
        val diagnostics: HdrProcessingDiagnostics,
    ) : HdrStatus
    data class Failed(val reason: String, val fallbackToStandard: Boolean = false) : HdrStatus
    data object Cancelled : HdrStatus
}

internal data class HdrCapturePlan(
    val token: Long,
    val generation: Long,
    val bracket: HdrBracketPlan,
    val outputRotationDegrees: Int,
)

internal data class HdrRequestTag(val token: Long, val frameIndex: Int)

internal sealed interface HdrCaptureStartResult {
    data class Started(val plan: HdrCapturePlan) : HdrCaptureStartResult
    data class Rejected(val reason: String) : HdrCaptureStartResult
}
