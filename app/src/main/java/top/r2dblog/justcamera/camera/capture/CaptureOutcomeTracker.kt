package top.r2dblog.justcamera.camera.capture

import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CaptureOutputFailure
import top.r2dblog.justcamera.camera.model.CaptureOutputType
import top.r2dblog.justcamera.camera.model.CaptureStatus
import top.r2dblog.justcamera.camera.model.CapturedOutput

class CaptureOutcomeTracker(
    private val mode: CaptureMode,
    expectedOutputs: Set<CaptureOutputType>,
) {
    private val pending = expectedOutputs.toMutableSet()
    private val inProgress = mutableSetOf<CaptureOutputType>()
    private val saved = mutableListOf<CapturedOutput>()
    private val failures = mutableListOf<CaptureOutputFailure>()

    init {
        require(expectedOutputs.isNotEmpty())
    }

    @Synchronized
    fun saving(): CaptureStatus = CaptureStatus.Saving(mode, pending.toSet())

    @Synchronized
    fun begin(type: CaptureOutputType): Boolean =
        type in pending && inProgress.add(type)

    @Synchronized
    fun succeed(type: CaptureOutputType, displayName: String): CaptureStatus {
        if (!pending.remove(type)) return current()
        inProgress.remove(type)
        saved += CapturedOutput(type, displayName)
        return current()
    }

    @Synchronized
    fun fail(type: CaptureOutputType, error: CameraError): CaptureStatus {
        if (!pending.remove(type)) return current()
        inProgress.remove(type)
        failures += CaptureOutputFailure(type, error)
        return current()
    }

    @Synchronized
    fun failPending(errorFor: (CaptureOutputType) -> CameraError): CaptureStatus {
        pending.toList().forEach { type ->
            failures += CaptureOutputFailure(type, errorFor(type))
            pending.remove(type)
            inProgress.remove(type)
        }
        return current()
    }

    @Synchronized
    fun isComplete(): Boolean = pending.isEmpty()

    @Synchronized
    fun hasUnstartedOutputs(): Boolean = pending.any { it !in inProgress }

    @Synchronized
    private fun current(): CaptureStatus = when {
        pending.isNotEmpty() -> CaptureStatus.Saving(mode, pending.toSet())
        failures.isEmpty() -> CaptureStatus.Saved(saved.toList())
        saved.isNotEmpty() -> CaptureStatus.PartialSuccess(saved.toList(), failures.toList())
        else -> CaptureStatus.Failed(failures.first().error)
    }
}
