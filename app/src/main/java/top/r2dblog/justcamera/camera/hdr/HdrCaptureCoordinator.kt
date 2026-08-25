package top.r2dblog.justcamera.camera.hdr

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ln
import top.r2dblog.justcamera.hdr.capture.HdrBracketConstraints
import top.r2dblog.justcamera.hdr.capture.HdrBracketPlanner
import top.r2dblog.justcamera.hdr.capture.HdrBracketPlanningResult
import top.r2dblog.justcamera.hdr.capture.HdrCapturePlan
import top.r2dblog.justcamera.hdr.capture.HdrCaptureStartResult
import top.r2dblog.justcamera.hdr.capture.HdrExposureBaseline
import top.r2dblog.justcamera.hdr.capture.HdrFramePairingQueue
import top.r2dblog.justcamera.hdr.capture.HdrPairingUpdate
import top.r2dblog.justcamera.hdr.capture.HdrStatus
import top.r2dblog.justcamera.hdr.model.HdrExposureMetadata
import top.r2dblog.justcamera.hdr.model.HdrFrameSet
import top.r2dblog.justcamera.hdr.model.HdrInputFrame
import top.r2dblog.justcamera.hdr.model.HdrProcessingStage
import top.r2dblog.justcamera.hdr.processing.HdrProcessingPipeline
import top.r2dblog.justcamera.hdr.yuv.OwnedYuvFrame
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory

/** Owns one bounded HDR burst from YUV/result pairing through tone-mapped output. */
internal class HdrCaptureCoordinator(
    private val cameraLooper: Looper,
    private val cameraHandler: Handler,
    private val onCaptureTerminal: (generation: Long) -> Unit,
    private val processor: HdrProcessingPipeline = HdrProcessingPipeline(),
) {
    private val lock = Any()
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val readers = HdrYuvImageLeaseRegistry()
    private val pairing = HdrFramePairingQueue<OwnedYuvFrame, ObservedCaptureMetadata>()
    private val _status = MutableStateFlow<HdrStatus>(HdrStatus.Idle)
    val status: StateFlow<HdrStatus> = _status.asStateFlow()
    private val _lastOutput = MutableStateFlow<RgbFloatFrame?>(null)
    val lastOutput: StateFlow<RgbFloatFrame?> = _lastOutput.asStateFlow()

    private var activeCapture: ActiveHdrCapture? = null
    private var processingToken: Long? = null
    private var processingJob: Job? = null
    private var nextToken = 0L
    private var timeout: ScheduledTimeout? = null

    fun registerReader(reader: ImageReader, generation: Long) {
        checkCameraThread()
        readers.register(reader, generation)
    }

    fun retireReader(reader: ImageReader) {
        checkCameraThread()
        readers.retire(reader)
    }

    fun beginCapture(
        generation: Long,
        baseline: HdrExposureBaseline,
        constraints: HdrBracketConstraints,
        outputRotationDegrees: Int,
    ): HdrCaptureStartResult {
        checkCameraThread()
        if (isBusy()) return HdrCaptureStartResult.Rejected("An HDR capture is already active")
        _status.value = HdrStatus.Planning
        val planning = HdrBracketPlanner.plan(baseline, constraints)
        if (planning is HdrBracketPlanningResult.Unsupported) {
            _status.value = HdrStatus.Failed(planning.reason, fallbackToStandard = true)
            return HdrCaptureStartResult.Rejected(planning.reason)
        }
        val bracket = (planning as HdrBracketPlanningResult.Planned).plan
        pairing.begin(generation)
        val batch = ActiveHdrCapture(
            token = ++nextToken,
            generation = generation,
            bracket = bracket,
            outputRotationDegrees = outputRotationDegrees,
        )
        synchronized(lock) {
            activeCapture = batch
            processingToken = null
            processingJob = null
        }
        _lastOutput.value = null
        _status.value = HdrStatus.Capturing(0, bracket.entries.size)
        scheduleTimeout(batch.token)
        return HdrCaptureStartResult.Started(batch.plan)
    }

    fun isActive(plan: HdrCapturePlan): Boolean = synchronized(lock) {
        activeCapture?.matches(plan) == true
    }

    fun isBusy(): Boolean = synchronized(lock) {
        activeCapture != null || processingToken != null
    }

    fun onCaptureResult(plan: HdrCapturePlan, frameIndex: Int, result: TotalCaptureResult) {
        checkCameraThread()
        if (!isActive(plan)) return
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val entry = plan.bracket.entries.firstOrNull { it.frameIndex == frameIndex }
        if (timestamp == null || exposureTime == null || sensitivity == null || entry == null) {
            failPlan(plan, "HDR result is missing timestamp, actual exposure, ISO, or frame identity")
            return
        }
        val observed = ObservedCaptureMetadata(
            frameIndex,
            timestamp,
            exposureTime,
            sensitivity,
            result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            entry.isReference,
        )
        handlePairingUpdate(
            plan,
            pairing.offerResult(plan.generation, timestamp, observed),
        )
    }

    fun onYuvImageAvailable(reader: ImageReader, callbackGeneration: Long) {
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            failGeneration(callbackGeneration, "HDR YUV reader queue is unavailable", error)
            return
        } ?: return
        val lease = readers.transfer(reader, image, callbackGeneration) ?: return
        val owned = try {
            lease.use { YuvImageCopier.copy(it.image) }
        } catch (error: RuntimeException) {
            failGeneration(callbackGeneration, "Unable to copy HDR YUV planes", error)
            return
        }
        val plan = synchronized(lock) {
            activeCapture?.takeIf { it.generation == callbackGeneration }?.plan
        } ?: return
        handlePairingUpdate(
            plan,
            pairing.offerImage(callbackGeneration, owned.timestampNanos, owned),
        )
    }

    fun onCaptureRequestFailed(plan: HdrCapturePlan, message: String, cause: Throwable?) {
        checkCameraThread()
        failPlan(plan, message, cause)
    }

    fun onSequencesSubmitted(plan: HdrCapturePlan, sequenceIds: List<Int>) {
        checkCameraThread()
        synchronized(lock) {
            activeCapture?.takeIf { it.matches(plan) }?.sequenceIds?.addAll(sequenceIds)
        }
    }

    fun rejectTopology(message: String) {
        checkCameraThread()
        _status.value = HdrStatus.Failed(message, fallbackToStandard = true)
    }

    fun rejectCapture(message: String) {
        checkCameraThread()
        if (!isBusy()) _status.value = HdrStatus.Failed(message)
    }

    fun dismissFailure() {
        checkCameraThread()
        if (!isBusy() && (_status.value is HdrStatus.Failed || _status.value is HdrStatus.Cancelled)) {
            _status.value = HdrStatus.Idle
        }
    }

    fun cancelActiveCapture(resetStatus: Boolean) {
        checkCameraThread()
        synchronized(lock) {
            activeCapture = null
            processingToken = null
            processingJob?.cancel()
            processingJob = null
        }
        pairing.cancel()
        cancelTimeout()
        _lastOutput.value = null
        _status.value = if (resetStatus) HdrStatus.Idle else HdrStatus.Cancelled
    }

    fun release() {
        checkCameraThread()
        cancelActiveCapture(resetStatus = true)
        processingScope.cancel()
    }

    private fun handlePairingUpdate(
        plan: HdrCapturePlan,
        update: HdrPairingUpdate<OwnedYuvFrame, ObservedCaptureMetadata>,
    ) {
        if (update.staleGenerationIgnored) return
        var completed: CompletedCapture? = null
        var capturedCount = 0
        synchronized(lock) {
            val batch = activeCapture?.takeIf { it.matches(plan) } ?: return
            update.ready.forEach { pair ->
                if (pair.result.frameIndex !in batch.pairedFrames) {
                    batch.pairedFrames[pair.result.frameIndex] = PairedFrame(pair.image, pair.result)
                }
            }
            capturedCount = batch.pairedFrames.size
            if (capturedCount == batch.bracket.entries.size) {
                activeCapture = null
                completed = CompletedCapture(
                    batch.token,
                    batch.generation,
                    batch.outputRotationDegrees,
                    batch.bracket.referenceFrameIndex,
                    batch.pairedFrames.values.sortedBy { it.metadata.frameIndex },
                )
            }
        }
        _status.value = HdrStatus.Capturing(capturedCount, plan.bracket.entries.size)
        completed?.let {
            pairing.cancel()
            cancelTimeout(plan.token)
            startProcessing(it)
        }
    }

    private fun startProcessing(capture: CompletedCapture) {
        val job = processingScope.launch(start = CoroutineStart.LAZY) {
            try {
                val reference = capture.frames.single { it.metadata.isReference }.metadata
                val inputs = capture.frames.map { paired ->
                    currentCoroutineContext().ensureActive()
                    val ratio = paired.metadata.exposureScalar / reference.exposureScalar
                    HdrInputFrame(
                        paired.yuv,
                        HdrExposureMetadata(
                            paired.metadata.exposureTimeNanos,
                            paired.metadata.sensitivityIso,
                            paired.metadata.timestampNanos,
                            paired.metadata.frameIndex,
                            log2(ratio),
                            paired.metadata.isReference,
                            paired.metadata.focusDistanceDiopters,
                        ),
                    )
                }
                val frameSet = HdrFrameSet(inputs, capture.referenceFrameIndex)
                val output = processor.process(
                    frameSet,
                    capture.outputRotationDegrees,
                ) { stage -> publishStage(capture.token, inputs.size, stage) }
                currentCoroutineContext().ensureActive()
                val publish = synchronized(lock) {
                    if (processingToken != capture.token) false else {
                        processingToken = null
                        processingJob = null
                        true
                    }
                }
                if (publish) {
                    _lastOutput.value = output.toneMapped
                    _status.value = HdrStatus.Completed(
                        output.toneMapped.width,
                        output.toneMapped.height,
                        output.diagnostics,
                    )
                    notifyTerminal(capture.generation)
                }
            } catch (_: CancellationException) {
                // Lifecycle/caller cancellation owns the terminal status and prevents publication.
            } catch (error: Exception) {
                val publish = synchronized(lock) {
                    if (processingToken != capture.token) false else {
                        processingToken = null
                        processingJob = null
                        true
                    }
                }
                if (publish) {
                    val message = "HDR processing failed: ${error.message ?: error::class.java.simpleName}"
                    JcLog.error(LogCategory.CAPTURE, message, error)
                    _status.value = HdrStatus.Failed(message)
                    notifyTerminal(capture.generation)
                }
            }
        }
        synchronized(lock) {
            processingToken = capture.token
            processingJob = job
        }
        job.start()
    }

    private fun publishStage(token: Long, frameCount: Int, stage: HdrProcessingStage) {
        if (synchronized(lock) { processingToken == token }) {
            _status.value = when (stage) {
                HdrProcessingStage.CONVERTING -> HdrStatus.Converting(frameCount)
                HdrProcessingStage.ALIGNING -> HdrStatus.Aligning
                HdrProcessingStage.MERGING -> HdrStatus.Merging
                HdrProcessingStage.TONE_MAPPING -> HdrStatus.ToneMapping
            }
        }
    }

    private fun failGeneration(generation: Long, message: String, cause: Throwable?) {
        cameraHandler.post {
            val plan = synchronized(lock) {
                activeCapture?.takeIf { it.generation == generation }?.plan
            } ?: return@post
            failPlan(plan, message, cause)
        }
    }

    private fun failPlan(plan: HdrCapturePlan, message: String, cause: Throwable? = null) {
        checkCameraThread()
        val failed = synchronized(lock) {
            val batch = activeCapture?.takeIf { it.matches(plan) } ?: return
            activeCapture = null
            batch
        }
        pairing.cancel()
        cancelTimeout(plan.token)
        JcLog.error(LogCategory.CAPTURE, message, cause)
        _status.value = HdrStatus.Failed(message)
        notifyTerminal(failed.generation)
    }

    private fun scheduleTimeout(token: Long) {
        cancelTimeout()
        val runnable = Runnable {
            val plan = synchronized(lock) {
                if (timeout?.token != token) return@Runnable
                activeCapture?.takeIf { it.token == token }?.plan
            } ?: return@Runnable
            timeout = null
            failPlan(plan, "Timed out waiting for the HDR bracket frames")
        }
        timeout = ScheduledTimeout(token, runnable)
        cameraHandler.postDelayed(runnable, CAPTURE_TIMEOUT_MS)
    }

    private fun cancelTimeout(token: Long? = null) {
        val runnable = synchronized(lock) {
            timeout?.takeIf { token == null || it.token == token }?.also { timeout = null }?.runnable
        }
        runnable?.let(cameraHandler::removeCallbacks)
    }

    private fun notifyTerminal(generation: Long) {
        cameraHandler.post { onCaptureTerminal(generation) }
    }

    private fun checkCameraThread() {
        check(Looper.myLooper() === cameraLooper) { "HDR capture mutation must run on camera thread" }
    }

    private data class ObservedCaptureMetadata(
        val frameIndex: Int,
        val timestampNanos: Long,
        val exposureTimeNanos: Long,
        val sensitivityIso: Int,
        val focusDistanceDiopters: Float?,
        val isReference: Boolean,
    ) {
        val exposureScalar: Double get() = exposureTimeNanos.toDouble() * sensitivityIso
    }

    private data class PairedFrame(
        val yuv: OwnedYuvFrame,
        val metadata: ObservedCaptureMetadata,
    )

    private data class CompletedCapture(
        val token: Long,
        val generation: Long,
        val outputRotationDegrees: Int,
        val referenceFrameIndex: Int,
        val frames: List<PairedFrame>,
    )

    private data class ScheduledTimeout(val token: Long, val runnable: Runnable)

    private class ActiveHdrCapture(
        val token: Long,
        val generation: Long,
        val bracket: top.r2dblog.justcamera.hdr.capture.HdrBracketPlan,
        val outputRotationDegrees: Int,
        val pairedFrames: MutableMap<Int, PairedFrame> = linkedMapOf(),
        val sequenceIds: MutableList<Int> = mutableListOf(),
    ) {
        val plan: HdrCapturePlan
            get() = HdrCapturePlan(token, generation, bracket, outputRotationDegrees)

        fun matches(plan: HdrCapturePlan): Boolean =
            token == plan.token && generation == plan.generation
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 10_000L
        fun log2(value: Double): Double = ln(value) / ln(2.0)
    }
}
