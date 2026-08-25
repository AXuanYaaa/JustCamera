package top.r2dblog.justcamera.camera.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.r2dblog.justcamera.camera.capture.RawImageLeaseRegistry.RawImageLease
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CameraErrorCode
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.CaptureOutputType
import top.r2dblog.justcamera.camera.model.CaptureStatus
import top.r2dblog.justcamera.camera.raw.DngPair
import top.r2dblog.justcamera.camera.raw.DngPairingQueue
import top.r2dblog.justcamera.camera.raw.DngPairingUpdate
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class StillCapturePlan(
    val token: Long,
    val generation: Long,
    val expectedOutputs: Set<CaptureOutputType>,
)

/**
 * Coordinates one still request and its asynchronously delivered/saved outputs.
 *
 * CameraDevice, CameraCaptureSession, and request submission stay in CameraEngine. This component
 * owns capture generations, timestamp pairing, output completion, timeout, save jobs, and the RAW
 * Image lease that keeps a retired ImageReader alive until DngCreator finishes.
 */
internal class StillCaptureCoordinator(
    context: Context,
    private val cameraLooper: Looper,
    private val cameraHandler: Handler,
    private val onCaptureTerminal: (generation: Long) -> Unit,
) {
    private val jpegSaver = MediaStoreJpegSaver(context)
    private val dngSaver = MediaStoreDngSaver(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawReaders = RawImageLeaseRegistry()
    private val rawPairing = DngPairingQueue<RawImageLease, TotalCaptureResult>()
    private val jpegPairing = DngPairingQueue<ByteArray, Unit>(maxPendingTimestamps = 2)
    private val captureLock = Any()
    private val saveJobsLock = Any()
    private val activeSaveJobs = mutableSetOf<Job>()

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private var activeCapture: ActiveCapture? = null
    private var nextCaptureToken = 0L
    private var captureTimeout: ScheduledTimeout? = null
    private var releaseRequested = false

    fun registerRawReader(reader: ImageReader, generation: Long) {
        checkCameraThread()
        rawReaders.register(reader, generation)
    }

    fun retireRawReader(reader: ImageReader) {
        checkCameraThread()
        rawReaders.retire(reader)
    }

    fun beginCapture(
        generation: Long,
        mode: CaptureMode,
        expectedOutputs: Set<CaptureOutputType>,
        characteristics: CameraCharacteristics,
    ): StillCapturePlan {
        checkCameraThread()
        val discarded = synchronized(captureLock) {
            check(activeCapture == null) { "A still capture is already active" }
            clearPairingLocked()
        }
        discarded.forEach(RawImageLease::close)

        val batch = ActiveCapture(
            token = ++nextCaptureToken,
            generation = generation,
            mode = mode,
            expectedOutputs = expectedOutputs,
            baseName = "JC_${FILE_NAME_FORMAT.format(Date())}",
            characteristics = characteristics,
            tracker = CaptureOutcomeTracker(mode, expectedOutputs),
        )
        synchronized(captureLock) { activeCapture = batch }
        _status.value = CaptureStatus.Capturing(mode)
        scheduleCaptureTimeout(batch.token)
        return batch.plan
    }

    fun isActive(plan: StillCapturePlan): Boolean = synchronized(captureLock) {
        activeCapture?.matches(plan) == true
    }

    fun isBusy(): Boolean = _status.value is CaptureStatus.Capturing ||
        _status.value is CaptureStatus.Saving

    fun rejectCapture(error: CameraError) {
        checkCameraThread()
        if (!isBusy()) _status.value = CaptureStatus.Failed(error)
    }

    fun onCaptureResult(plan: StillCapturePlan, result: TotalCaptureResult) {
        checkCameraThread()
        if (!isActive(plan)) return
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (CaptureOutputType.JPEG in plan.expectedOutputs) {
            if (timestamp == null) {
                completeOutputFailure(
                    plan.token,
                    CaptureOutputType.JPEG,
                    CameraError(
                        CameraErrorCode.CAPTURE_FAILED,
                        "JPEG capture result did not contain a sensor timestamp",
                    ),
                )
            } else {
                val update = synchronized(captureLock) {
                    activeCapture?.takeIf { it.matches(plan) }
                        ?.let { jpegPairing.offerResult(timestamp, Unit) }
                }
                update?.let { handleJpegPairingUpdate(plan.token, it) }
            }
        }
        if (CaptureOutputType.DNG in plan.expectedOutputs) {
            if (timestamp == null) {
                completeOutputFailure(
                    plan.token,
                    CaptureOutputType.DNG,
                    CameraError(
                        CameraErrorCode.RAW_PAIRING_FAILED,
                        "RAW capture result did not contain a sensor timestamp",
                    ),
                )
            } else {
                val update = synchronized(captureLock) {
                    activeCapture?.takeIf { it.matches(plan) }
                        ?.let { rawPairing.offerResult(timestamp, result) }
                }
                update?.let { handleRawPairingUpdate(plan.token, it) }
            }
        }
    }

    fun onCaptureRequestFailed(plan: StillCapturePlan, message: String, cause: Throwable?) {
        checkCameraThread()
        val error = CameraError(CameraErrorCode.CAPTURE_FAILED, message, cause)
        JcLog.error(LogCategory.CAPTURE, message, cause)
        var discarded = emptyList<RawImageLease>()
        val status = synchronized(captureLock) {
            val batch = activeCapture?.takeIf { it.matches(plan) } ?: return
            discarded = clearPairingLocked()
            activeCapture = null
            batch.tracker.failPending { type ->
                if (type == CaptureOutputType.DNG) {
                    error.copy(code = CameraErrorCode.RAW_CAPTURE_FAILED)
                } else {
                    error
                }
            }
        }
        discarded.forEach(RawImageLease::close)
        _status.value = status
        cancelCaptureTimeout(plan.token)
        notifyCaptureTerminal(plan.generation)
    }

    fun onJpegImageAvailable(reader: ImageReader, callbackGeneration: Long) {
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            failCurrentOutput(
                callbackGeneration,
                CaptureOutputType.JPEG,
                CameraError(
                    CameraErrorCode.CAPTURE_FAILED,
                    "JPEG ImageReader queue is unavailable",
                    error,
                ),
            )
            return
        } ?: return

        val token = currentCaptureTokenFor(callbackGeneration, CaptureOutputType.JPEG)
        if (token == null) {
            image.close()
            return
        }
        val frame = try {
            val buffer = image.planes.first().buffer
            image.timestamp to ByteArray(buffer.remaining()).also(buffer::get)
        } catch (error: RuntimeException) {
            completeOutputFailure(
                token,
                CaptureOutputType.JPEG,
                CameraError(CameraErrorCode.CAPTURE_FAILED, "Unable to read JPEG image", error),
            )
            return
        } finally {
            image.close()
        }

        val update = synchronized(captureLock) {
            activeCapture?.takeIf {
                it.token == token && CaptureGenerationPolicy.isCurrent(
                    callbackGeneration,
                    it.generation,
                )
            }?.let { jpegPairing.offerImage(frame.first, frame.second) }
        }
        update?.let { handleJpegPairingUpdate(token, it) }
    }

    fun onRawImageAvailable(reader: ImageReader, callbackGeneration: Long) {
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            failCurrentOutput(
                callbackGeneration,
                CaptureOutputType.DNG,
                CameraError(
                    CameraErrorCode.RAW_CAPTURE_FAILED,
                    "RAW ImageReader queue is unavailable",
                    error,
                ),
            )
            return
        } ?: return

        val lease = try {
            rawReaders.transfer(reader, image, callbackGeneration)
        } catch (error: RuntimeException) {
            failCurrentOutput(
                callbackGeneration,
                CaptureOutputType.DNG,
                CameraError(
                    CameraErrorCode.RAW_CAPTURE_FAILED,
                    "Unable to read RAW image metadata",
                    error,
                ),
            )
            return
        } ?: return

        var token: Long? = null
        val update = synchronized(captureLock) {
            activeCapture?.takeIf {
                CaptureOutputType.DNG in it.expectedOutputs &&
                    CaptureGenerationPolicy.isCurrent(callbackGeneration, it.generation)
            }?.let {
                token = it.token
                rawPairing.offerImage(lease.timestampNanos, lease)
            }
        }
        if (token == null || update == null) {
            lease.close()
            return
        }
        handleRawPairingUpdate(token!!, update)
    }

    fun cancelActiveCapture(resetStatus: Boolean) {
        checkCameraThread()
        val discarded = synchronized(captureLock) {
            activeCapture = null
            clearPairingLocked()
        }
        discarded.forEach(RawImageLease::close)
        cancelCaptureTimeout()
        if (resetStatus && (_status.value is CaptureStatus.Capturing ||
                _status.value is CaptureStatus.Saving)
        ) {
            _status.value = CaptureStatus.Idle
        }
    }

    /** Already-started saves finish; release prevents any new save from taking ownership. */
    fun release() {
        checkCameraThread()
        cancelActiveCapture(resetStatus = true)
        synchronized(saveJobsLock) {
            releaseRequested = true
            if (activeSaveJobs.isEmpty()) ioScope.cancel()
        }
    }

    private fun handleJpegPairingUpdate(
        captureToken: Long,
        update: DngPairingUpdate<ByteArray, Unit>,
    ) {
        if (update.discardedImages.isNotEmpty()) {
            JcLog.debug(LogCategory.CAPTURE) {
                "Discarded ${update.discardedImages.size} stale JPEG pairing entries"
            }
        }
        update.ready.forEach { pair ->
            val batch = beginOutputForToken(captureToken, CaptureOutputType.JPEG)
                ?: return@forEach
            val displayName = "${batch.baseName}.jpg"
            if (!launchSave {
                    val result = try {
                        jpegSaver.save(pair.image, displayName)
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    result.fold(
                        onSuccess = {
                            completeOutputSuccess(batch.token, CaptureOutputType.JPEG, it.displayName)
                        },
                        onFailure = {
                            completeOutputFailure(
                                batch.token,
                                CaptureOutputType.JPEG,
                                CameraError(
                                    CameraErrorCode.STORAGE_FAILED,
                                    "Failed to save $displayName",
                                    it,
                                ),
                            )
                        },
                    )
                }
            ) {
                completeOutputFailure(
                    batch.token,
                    CaptureOutputType.JPEG,
                    CameraError(CameraErrorCode.STORAGE_FAILED, "Save coordinator was released"),
                )
            }
        }
    }

    private fun handleRawPairingUpdate(
        captureToken: Long,
        update: DngPairingUpdate<RawImageLease, TotalCaptureResult>,
    ) {
        update.discardedImages.forEach(RawImageLease::close)
        update.ready.forEach { pair -> saveDngPair(captureToken, pair) }
    }

    private fun saveDngPair(
        captureToken: Long,
        pair: DngPair<RawImageLease, TotalCaptureResult>,
    ) {
        val batch = beginOutputForToken(captureToken, CaptureOutputType.DNG)
        if (batch == null) {
            pair.image.close()
            return
        }
        val displayName = "${batch.baseName}.dng"
        if (!launchSave {
                val result = try {
                    dngSaver.save(
                        batch.characteristics,
                        pair.result,
                        pair.image.image,
                        displayName,
                    )
                } catch (error: Exception) {
                    Result.failure(error)
                } finally {
                    pair.image.close()
                }
                result.fold(
                    onSuccess = {
                        completeOutputSuccess(batch.token, CaptureOutputType.DNG, it.displayName)
                    },
                    onFailure = { error ->
                        val code = if (error is IOException || error is SecurityException) {
                            CameraErrorCode.STORAGE_FAILED
                        } else {
                            CameraErrorCode.DNG_ENCODING_FAILED
                        }
                        completeOutputFailure(
                            batch.token,
                            CaptureOutputType.DNG,
                            CameraError(code, "Failed to save $displayName", error),
                        )
                    },
                )
            }
        ) {
            pair.image.close()
            completeOutputFailure(
                batch.token,
                CaptureOutputType.DNG,
                CameraError(CameraErrorCode.STORAGE_FAILED, "Save coordinator was released"),
            )
        }
    }

    private fun beginOutputForToken(token: Long, type: CaptureOutputType): ActiveCapture? {
        var timeoutToCancel: Runnable? = null
        val batch = synchronized(captureLock) {
            activeCapture?.takeIf {
                it.token == token && type in it.expectedOutputs && it.tracker.begin(type)
            }?.also {
                _status.value = it.tracker.saving()
                if (!it.tracker.hasUnstartedOutputs()) {
                    timeoutToCancel = detachCaptureTimeoutLocked(token)
                }
            }
        }
        timeoutToCancel?.let(cameraHandler::removeCallbacks)
        return batch
    }

    private fun currentCaptureTokenFor(generation: Long, type: CaptureOutputType): Long? =
        synchronized(captureLock) {
            activeCapture?.takeIf {
                type in it.expectedOutputs &&
                    CaptureGenerationPolicy.isCurrent(generation, it.generation)
            }?.token
        }

    private fun failCurrentOutput(generation: Long, type: CaptureOutputType, error: CameraError) {
        currentCaptureTokenFor(generation, type)?.let { completeOutputFailure(it, type, error) }
    }

    private fun completeOutputSuccess(token: Long, type: CaptureOutputType, displayName: String) {
        publishCaptureProgress(token) { it.succeed(type, displayName) }
    }

    private fun completeOutputFailure(token: Long, type: CaptureOutputType, error: CameraError) {
        JcLog.error(LogCategory.CAPTURE, error.message, error.cause)
        publishCaptureProgress(token) { it.fail(type, error) }
    }

    private fun publishCaptureProgress(
        token: Long,
        update: (CaptureOutcomeTracker) -> CaptureStatus,
    ) {
        var completedGeneration: Long? = null
        var timeoutToCancel: Runnable? = null
        val status = synchronized(captureLock) {
            val batch = activeCapture?.takeIf { it.token == token } ?: return
            val next = update(batch.tracker)
            if (batch.tracker.isComplete()) {
                completedGeneration = batch.generation
                activeCapture = null
            }
            if (completedGeneration != null || !batch.tracker.hasUnstartedOutputs()) {
                timeoutToCancel = detachCaptureTimeoutLocked(token)
            }
            next
        }
        _status.value = status
        timeoutToCancel?.let(cameraHandler::removeCallbacks)
        completedGeneration?.let(::notifyCaptureTerminal)
    }

    private fun scheduleCaptureTimeout(token: Long) {
        cancelCaptureTimeout()
        val runnable = Runnable {
            var generation: Long? = null
            var discarded = emptyList<RawImageLease>()
            val status = synchronized(captureLock) {
                if (captureTimeout?.token != token) return@Runnable
                val batch = activeCapture?.takeIf { it.token == token } ?: return@Runnable
                captureTimeout = null
                discarded = clearPairingLocked()
                activeCapture = null
                generation = batch.generation
                batch.tracker.failPending { type ->
                    CameraError(
                        if (type == CaptureOutputType.DNG) {
                            CameraErrorCode.RAW_PAIRING_FAILED
                        } else {
                            CameraErrorCode.CAPTURE_FAILED
                        },
                        "Timed out waiting for ${type.name} capture output",
                    )
                }
            }
            discarded.forEach(RawImageLease::close)
            _status.value = status
            generation?.let(::notifyCaptureTerminal)
        }
        synchronized(captureLock) { captureTimeout = ScheduledTimeout(token, runnable) }
        cameraHandler.postDelayed(runnable, CAPTURE_TIMEOUT_MS)
    }

    private fun cancelCaptureTimeout(token: Long? = null) {
        val runnable = synchronized(captureLock) { detachCaptureTimeoutLocked(token) }
        runnable?.let(cameraHandler::removeCallbacks)
    }

    private fun detachCaptureTimeoutLocked(token: Long?): Runnable? =
        captureTimeout?.takeIf { token == null || it.token == token }?.also {
            captureTimeout = null
        }?.runnable

    private fun clearPairingLocked(): List<RawImageLease> {
        jpegPairing.clear()
        return rawPairing.clear()
    }

    private fun notifyCaptureTerminal(generation: Long) {
        cameraHandler.post { onCaptureTerminal(generation) }
    }

    private fun launchSave(block: suspend () -> Unit): Boolean {
        lateinit var job: Job
        synchronized(saveJobsLock) {
            if (releaseRequested) return false
            job = ioScope.launch(start = CoroutineStart.LAZY) { block() }
            activeSaveJobs += job
        }
        job.invokeOnCompletion {
            synchronized(saveJobsLock) {
                activeSaveJobs -= job
                if (releaseRequested && activeSaveJobs.isEmpty()) ioScope.cancel()
            }
        }
        job.start()
        return true
    }

    private fun checkCameraThread() {
        check(Looper.myLooper() === cameraLooper) {
            "Still capture camera coordination must run on the camera thread"
        }
    }

    private data class ScheduledTimeout(val token: Long, val runnable: Runnable)

    private data class ActiveCapture(
        val token: Long,
        val generation: Long,
        val mode: CaptureMode,
        val expectedOutputs: Set<CaptureOutputType>,
        val baseName: String,
        val characteristics: CameraCharacteristics,
        val tracker: CaptureOutcomeTracker,
    ) {
        val plan = StillCapturePlan(token, generation, expectedOutputs)

        fun matches(candidate: StillCapturePlan): Boolean =
            token == candidate.token && generation == candidate.generation
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 12_000L
        val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
