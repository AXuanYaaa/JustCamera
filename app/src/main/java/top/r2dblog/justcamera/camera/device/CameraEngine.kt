package top.r2dblog.justcamera.camera.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.r2dblog.justcamera.camera.capability.CameraCapabilityScanner
import top.r2dblog.justcamera.camera.capture.CaptureOutcomeTracker
import top.r2dblog.justcamera.camera.capture.MediaStoreDngSaver
import top.r2dblog.justcamera.camera.capture.MediaStoreJpegSaver
import top.r2dblog.justcamera.camera.control.CameraControlState
import top.r2dblog.justcamera.camera.control.CameraRequestController
import top.r2dblog.justcamera.camera.control.CaptureModeResolver
import top.r2dblog.justcamera.camera.control.MeteringRegionMapper
import top.r2dblog.justcamera.camera.control.ZoomCropCalculator
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraCaptureMetadata
import top.r2dblog.justcamera.camera.model.CameraControlCapabilities
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CameraErrorCode
import top.r2dblog.justcamera.camera.model.CameraEvent
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.CameraOutputFormat
import top.r2dblog.justcamera.camera.model.CameraState
import top.r2dblog.justcamera.camera.model.CameraStateReducer
import top.r2dblog.justcamera.camera.model.CaptureOutputType
import top.r2dblog.justcamera.camera.model.CaptureStatus
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.raw.DngPair
import top.r2dblog.justcamera.camera.raw.DngPairingQueue
import top.r2dblog.justcamera.camera.raw.DngPairingUpdate
import top.r2dblog.justcamera.camera.raw.RawCapabilitySelector
import top.r2dblog.justcamera.camera.session.CameraRecoveryPolicy
import top.r2dblog.justcamera.camera.session.CameraSessionController
import top.r2dblog.justcamera.camera.session.OrientationCalculator
import top.r2dblog.justcamera.camera.session.PreviewSizeSelector
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

internal class CameraEngine(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val scanner = CameraCapabilityScanner(cameraManager)
    private val jpegSaver = MediaStoreJpegSaver(appContext)
    private val dngSaver = MediaStoreDngSaver(appContext)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraThread = HandlerThread("JustCamera-Camera").apply { start() }
    private val imageThread = HandlerThread("JustCamera-ImageAcquire").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val sessionController = CameraSessionController(cameraThread.looper)
    private val recoveryPolicy = CameraRecoveryPolicy()
    private val rawPairing = DngPairingQueue<Image, TotalCaptureResult>()
    private val jpegPairing = DngPairingQueue<ByteArray, Unit>(maxPendingTimestamps = 2)

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    val state: StateFlow<CameraState> = _state.asStateFlow()
    private val _captureStatus = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val captureStatus: StateFlow<CaptureStatus> = _captureStatus.asStateFlow()
    private val _cameras = MutableStateFlow<List<CameraCapabilities>>(emptyList())
    val cameras: StateFlow<List<CameraCapabilities>> = _cameras.asStateFlow()
    private val _selectedCamera = MutableStateFlow<CameraCapabilities?>(null)
    val selectedCamera: StateFlow<CameraCapabilities?> = _selectedCamera.asStateFlow()
    private val _previewSize = MutableStateFlow<ImageSize?>(null)
    val previewSize: StateFlow<ImageSize?> = _previewSize.asStateFlow()
    private val _controlState = MutableStateFlow(CameraControlState())
    val controlState: StateFlow<CameraControlState> = _controlState.asStateFlow()
    private val _controlCapabilities = MutableStateFlow(CameraControlCapabilities())
    val controlCapabilities: StateFlow<CameraControlCapabilities> =
        _controlCapabilities.asStateFlow()
    private val _controlError = MutableStateFlow<CameraError?>(null)
    val controlError: StateFlow<CameraError?> = _controlError.asStateFlow()
    private val _captureMetadata = MutableStateFlow(CameraCaptureMetadata())
    val captureMetadata: StateFlow<CameraCaptureMetadata> = _captureMetadata.asStateFlow()
    private val _rawCaptureAvailable = MutableStateFlow(false)
    val rawCaptureAvailable: StateFlow<Boolean> = _rawCaptureAvailable.asStateFlow()

    @Volatile private var running = false
    @Volatile private var cameraPermissionGranted = false
    @Volatile private var storagePermissionGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    @Volatile private var released = false
    private var discoveryJob: Job? = null
    private val saveJobsLock = Any()
    private val activeSaveJobs = mutableSetOf<Job>()
    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var displayRotationDegrees: Int = 0
    private var cameraCallbackGeneration = 0L
    private var completedRetryAttempts = 0
    private var retryRunnable: Runnable? = null
    private var requestController: CameraRequestController? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private val rawTopologyDisabledCameraIds = mutableSetOf<String>()
    private var lastMetadataPublishTimestamp = Long.MIN_VALUE

    private val captureLock = Any()
    @Volatile private var activeCapture: ActiveCapture? = null
    private var nextCaptureToken = 0L
    private var captureTimeoutRunnable: Runnable? = null
    private var tapFocusResetRunnable: Runnable? = null

    fun updatePermissions(cameraGranted: Boolean, storageGranted: Boolean) {
        cameraPermissionGranted = cameraGranted
        storagePermissionGranted = storageGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (!cameraGranted) {
            cameraHandler.post {
                closeResources(emitClosed = false)
                transition(CameraEvent.PermissionMissing)
            }
        } else if (running) {
            ensureDiscoveryAndOpen()
        }
    }

    fun start() {
        running = true
        if (!cameraPermissionGranted) {
            transition(CameraEvent.PermissionMissing)
            return
        }
        ensureDiscoveryAndOpen()
    }

    fun stop() {
        running = false
        discoveryJob?.cancel()
        discoveryJob = null
        cameraHandler.post { closeResources(emitClosed = true) }
    }

    fun release() {
        running = false
        released = true
        discoveryJob?.cancel()
        cameraHandler.post {
            closeResources(emitClosed = true)
            cameraThread.quitSafely()
            imageThread.quitSafely()
        }
        workerScope.cancel()
        synchronized(saveJobsLock) {
            if (activeSaveJobs.isEmpty()) ioScope.cancel()
        }
    }

    fun attachPreview(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        cameraHandler.post {
            if (surfaceTexture != null && surfaceTexture !== texture) {
                closeResources(emitClosed = true)
            }
            surfaceTexture = texture
            surfaceWidth = width
            surfaceHeight = height
            displayRotationDegrees = rotationDegrees
            if (running && cameraPermissionGranted) openSelectedCameraIfReady()
        }
    }

    fun updatePreviewGeometry(width: Int, height: Int, rotationDegrees: Int) {
        cameraHandler.post {
            surfaceWidth = width
            surfaceHeight = height
            displayRotationDegrees = rotationDegrees
        }
    }

    fun detachPreview(texture: SurfaceTexture) {
        cameraHandler.post {
            if (surfaceTexture === texture) {
                closeResources(emitClosed = true)
                surfaceTexture = null
                surfaceWidth = 0
                surfaceHeight = 0
            }
        }
    }

    fun switchCamera() {
        cameraHandler.post {
            val available = _cameras.value
            if (available.size < 2) return@post
            val currentIndex = available.indexOfFirst {
                it.cameraId == _selectedCamera.value?.cameraId
            }.coerceAtLeast(0)
            closeResources(emitClosed = true)
            selectCameraOnCameraThread(available[(currentIndex + 1) % available.size])
            if (running) openSelectedCameraIfReady()
        }
    }

    fun selectCamera(cameraId: String) {
        cameraHandler.post {
            val selected = _cameras.value.firstOrNull { it.cameraId == cameraId } ?: return@post
            if (selected.cameraId == _selectedCamera.value?.cameraId) return@post
            closeResources(emitClosed = true)
            selectCameraOnCameraThread(selected)
            if (running) openSelectedCameraIfReady()
        }
    }

    fun updateControls(candidate: CameraControlState) {
        cameraHandler.post { updateControlsOnCameraThread(candidate) }
    }

    fun focusAt(normalizedX: Float, normalizedY: Float) {
        cameraHandler.post {
            val camera = _selectedCamera.value ?: return@post
            val controller = requestController ?: return@post
            val capabilities = controller.capabilities
            if (capabilities.maxAfMeteringRegions <= 0 &&
                capabilities.maxAeMeteringRegions <= 0
            ) {
                publishControlError(
                    CameraErrorCode.UNSUPPORTED_CONTROL,
                    "This camera does not support focus or exposure metering regions",
                )
                return@post
            }
            val activeArray = camera.activeArray ?: return@post
            val zoom = controller.requestedState.zoomRatio
            val crop = if (zoom >= 1f) ZoomCropCalculator.crop(activeArray, zoom) else activeArray
            val region = MeteringRegionMapper.map(
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                cropRegion = crop,
                relativeRotationDegrees = OrientationCalculator.relativePreviewRotation(
                    camera.sensorOrientation,
                    displayRotationDegrees,
                    camera.facing,
                ),
                mirrorHorizontally = camera.facing == CameraFacing.FRONT,
            )
            val update = controller.update(controller.requestedState.copy(meteringRegion = region))
            if (update.accepted) {
                _controlState.value = update.state
                updateRepeatingPreview()
                if (capabilities.maxAfMeteringRegions > 0 &&
                    update.state.focusMode != FocusMode.MANUAL
                ) {
                    triggerAutoFocus(triggerStart = true)
                    scheduleTapFocusReset()
                }
            }
        }
    }

    fun capture() {
        cameraHandler.post { captureOnCameraThread() }
    }

    fun captureJpeg() = capture()

    private fun selectCameraOnCameraThread(camera: CameraCapabilities) {
        checkCameraThread()
        _selectedCamera.value = camera
        requestController = CameraRequestController(camera).also { controller ->
            if (camera.cameraId in rawTopologyDisabledCameraIds) {
                controller.setRawAvailable(false)
            }
            publishRequestedControls(controller)
        }
        _captureMetadata.value = CameraCaptureMetadata()
        _controlError.value = null
    }

    private fun ensureDiscoveryAndOpen() {
        if (_cameras.value.isNotEmpty()) {
            cameraHandler.post {
                _selectedCamera.value?.let(::ensureRequestController)
                openSelectedCameraIfReady()
            }
            return
        }
        if (discoveryJob?.isActive == true) return
        discoveryJob = workerScope.launch {
            val result = scanner.discover()
            withContext(Dispatchers.Main.immediate) {
                _cameras.value = result.cameras
                _selectedCamera.value = preferredCamera(result.cameras)
            }
            cameraHandler.post {
                if (result.cameras.isEmpty()) {
                    publishError(
                        result.errors.firstOrNull() ?: CameraError(
                            CameraErrorCode.CAMERA_UNAVAILABLE,
                            "No Camera2 devices were discovered",
                            recoverable = false,
                        ),
                    )
                } else if (running) {
                    _selectedCamera.value?.let(::ensureRequestController)
                    openSelectedCameraIfReady()
                }
            }
        }
    }

    private fun preferredCamera(cameras: List<CameraCapabilities>): CameraCapabilities? =
        cameras.firstOrNull { it.facing == CameraFacing.BACK } ?: cameras.firstOrNull()

    private fun ensureRequestController(camera: CameraCapabilities): CameraRequestController {
        val current = requestController
        if (current != null && _selectedCamera.value?.cameraId == camera.cameraId) return current
        return CameraRequestController(camera).also { controller ->
            if (camera.cameraId in rawTopologyDisabledCameraIds) {
                controller.setRawAvailable(false)
            }
            requestController = controller
            publishRequestedControls(controller)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openSelectedCameraIfReady() {
        checkCameraThread()
        val currentState = _state.value
        if (!running || !cameraPermissionGranted ||
            sessionController.cameraDevice != null || currentState is CameraState.Opening ||
            currentState is CameraState.Error && !currentState.error.recoverable
        ) return
        val texture = surfaceTexture ?: return
        val camera = _selectedCamera.value ?: return
        ensureRequestController(camera)

        try {
            val characteristics = cameraManager.getCameraCharacteristics(camera.cameraId)
            activeCharacteristics = characteristics
            val choices = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)
                .orEmpty()
                .map { ImageSize(it.width, it.height) }
            val selectedSize = PreviewSizeSelector.select(choices, surfaceWidth, surfaceHeight)
            if (selectedSize == null) {
                failAndClose(
                    CameraError(
                        CameraErrorCode.UNSUPPORTED_CAPABILITY,
                        "Camera ${camera.cameraId} has no SurfaceTexture preview sizes",
                        recoverable = false,
                    ),
                )
                return
            }
            texture.setDefaultBufferSize(selectedSize.width, selectedSize.height)
            sessionController.replacePreviewSurface(texture)
            _previewSize.value = selectedSize
            val callbackGeneration = ++cameraCallbackGeneration
            transition(CameraEvent.Open(camera.cameraId))
            cameraManager.openCamera(
                camera.cameraId,
                deviceCallback(camera.cameraId, callbackGeneration),
                cameraHandler,
            )
        } catch (error: SecurityException) {
            failAndClose(
                CameraError(
                    CameraErrorCode.PERMISSION_DENIED,
                    "Camera permission denied",
                    error,
                    recoverable = false,
                ),
            )
        } catch (error: CameraAccessException) {
            failAndClose(accessError("Unable to open camera ${camera.cameraId}", error))
        } catch (error: IllegalArgumentException) {
            failAndClose(
                CameraError(
                    CameraErrorCode.CAMERA_UNAVAILABLE,
                    "Camera ${camera.cameraId} is no longer available",
                    error,
                ),
            )
        }
    }

    private fun deviceCallback(
        cameraId: String,
        callbackGeneration: Long,
    ) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!isCurrentCallback(cameraId, callbackGeneration) ||
                !running || surfaceTexture == null
            ) {
                sessionController.closeUnownedDevice(camera)
                return
            }
            sessionController.adoptCameraDevice(camera)
            transition(CameraEvent.DeviceOpened(cameraId))
            configureSession(camera, cameraId, callbackGeneration)
        }

        override fun onDisconnected(camera: CameraDevice) {
            if (!isCurrentCallback(cameraId, callbackGeneration)) {
                sessionController.closeUnownedDevice(camera)
                return
            }
            sessionController.adoptCameraDevice(camera)
            failAndClose(CameraError(CameraErrorCode.DISCONNECTED, "Camera $cameraId disconnected"))
        }

        override fun onError(camera: CameraDevice, error: Int) {
            if (!isCurrentCallback(cameraId, callbackGeneration)) {
                sessionController.closeUnownedDevice(camera)
                return
            }
            sessionController.adoptCameraDevice(camera)
            failAndClose(deviceError(cameraId, error))
        }
    }

    private fun configureSession(
        device: CameraDevice,
        cameraId: String,
        callbackGeneration: Long,
    ) {
        val previewSurface = sessionController.previewSurface
        val capabilities = _selectedCamera.value
        if (previewSurface == null || !previewSurface.isValid || capabilities == null) {
            failAndClose(CameraError(CameraErrorCode.INVALID_SURFACE, "Preview surface is invalid"))
            return
        }
        val jpegSize = capabilities.sizesFor(CameraOutputFormat.JPEG).maxByOrNull { it.area }
        if (jpegSize == null) {
            failAndClose(
                CameraError(
                    CameraErrorCode.UNSUPPORTED_CAPABILITY,
                    "Camera $cameraId does not expose JPEG output",
                    recoverable = false,
                ),
            )
            return
        }

        val rawSize = if (cameraId !in rawTopologyDisabledCameraIds) {
            RawCapabilitySelector.selectLargest(
                capabilities.hasRawCapability,
                capabilities.sizesFor(CameraOutputFormat.RAW_SENSOR),
            )
        } else {
            null
        }
        val attemptingRawTopology = rawSize != null

        try {
            val jpegReader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                ImageFormat.JPEG,
                2,
            ).apply { setOnImageAvailableListener(::onJpegImageAvailable, imageHandler) }
            sessionController.replaceImageReader(jpegReader)

            val rawReader = rawSize?.let { size ->
                ImageReader.newInstance(
                    size.width,
                    size.height,
                    ImageFormat.RAW_SENSOR,
                    RAW_READER_MAX_IMAGES,
                ).apply { setOnImageAvailableListener(::onRawImageAvailable, imageHandler) }
            }
            sessionController.replaceRawImageReader(rawReader)

            transition(CameraEvent.Configure(cameraId))
            val outputSurfaces = buildList {
                add(previewSurface)
                add(jpegReader.surface)
                rawReader?.surface?.let(::add)
            }
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isCurrentCallback(cameraId, callbackGeneration) ||
                        device !== sessionController.cameraDevice || !running
                    ) {
                        sessionController.closeUnownedSession(session)
                        return
                    }
                    sessionController.adoptCaptureSession(session)
                    setEffectiveRawAvailability(rawReader != null)
                    startPreview(device, session, previewSurface, cameraId)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionController.closeUnownedSession(session)
                    if (!isCurrentCallback(cameraId, callbackGeneration)) return
                    if (attemptingRawTopology) disableRawTopology(cameraId)
                    failAndClose(
                        CameraError(
                            CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                            if (attemptingRawTopology) {
                                "Camera $cameraId rejected preview + JPEG + RAW; retrying JPEG only"
                            } else {
                                "Camera $cameraId preview session configuration failed"
                            },
                        ),
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputSurfaces.map(::OutputConfiguration),
                        Executor { runnable -> cameraHandler.post(runnable) },
                        sessionCallback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(outputSurfaces, sessionCallback, cameraHandler)
            }
        } catch (error: CameraAccessException) {
            if (attemptingRawTopology) disableRawTopology(cameraId)
            failAndClose(accessError("Unable to configure camera $cameraId", error))
        } catch (error: IllegalArgumentException) {
            if (attemptingRawTopology) disableRawTopology(cameraId)
            failAndClose(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Camera $cameraId rejected the capture output topology",
                    error,
                ),
            )
        } catch (error: IllegalStateException) {
            if (attemptingRawTopology) disableRawTopology(cameraId)
            failAndClose(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Camera $cameraId closed while configuring its session",
                    error,
                ),
            )
        }
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface,
        cameraId: String,
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                requestController?.apply(this)
            }.build()
            session.setRepeatingRequest(request, previewCaptureCallback, cameraHandler)
            cancelScheduledRetry(resetAttempts = true)
            transition(CameraEvent.PreviewStarted(cameraId))
            JcLog.info(LogCategory.CAMERA, "Preview started on camera $cameraId")
        } catch (error: CameraAccessException) {
            failAndClose(accessError("Unable to start camera $cameraId preview", error))
        } catch (error: IllegalStateException) {
            failAndClose(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Preview session closed while starting",
                    error,
                ),
            )
        } catch (error: IllegalArgumentException) {
            failAndClose(
                CameraError(CameraErrorCode.INVALID_SURFACE, "Preview request was rejected", error),
            )
        }
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (session === sessionController.captureSession) {
                publishMetadata(result, throttled = true)
            }
        }
    }

    private fun updateControlsOnCameraThread(candidate: CameraControlState) {
        checkCameraThread()
        val controller = requestController ?: _selectedCamera.value?.let(::ensureRequestController)
            ?: return
        val previous = controller.requestedState
        val update = controller.update(candidate)
        if (!update.accepted) {
            publishControlError(CameraErrorCode.INVALID_CONTROL_VALUE, update.messages.joinToString())
            return
        }
        publishRequestedControls(controller)
        _controlError.value = update.messages.takeIf { it.isNotEmpty() }?.let {
            CameraError(
                CameraErrorCode.INVALID_CONTROL_VALUE,
                it.joinToString(),
                recoverable = true,
            )
        }
        updateRepeatingPreview()
        if (previous.afLockRequested != update.state.afLockRequested) {
            triggerAutoFocus(triggerStart = update.state.afLockRequested)
        }
    }

    private fun updateRepeatingPreview() {
        val device = sessionController.cameraDevice ?: return
        val session = sessionController.captureSession ?: return
        val surface = sessionController.previewSurface ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                requestController?.apply(this)
            }.build()
            session.setRepeatingRequest(request, previewCaptureCallback, cameraHandler)
        } catch (error: IllegalArgumentException) {
            publishControlError(
                CameraErrorCode.INVALID_CONTROL_VALUE,
                "The camera rejected the requested control combination",
                error,
            )
        } catch (error: CameraAccessException) {
            failAndClose(accessError("Unable to update camera controls", error))
        } catch (error: IllegalStateException) {
            failAndClose(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Preview session closed while updating controls",
                    error,
                ),
            )
        }
    }

    private fun triggerAutoFocus(triggerStart: Boolean) {
        val device = sessionController.cameraDevice ?: return
        val session = sessionController.captureSession ?: return
        val surface = sessionController.previewSurface ?: return
        val controller = requestController ?: return
        if (FocusMode.AUTO !in controller.capabilities.focusModes && triggerStart &&
            controller.capabilities.maxAfMeteringRegions <= 0
        ) {
            publishControlError(CameraErrorCode.UNSUPPORTED_CONTROL, "AF lock requires AUTO focus")
            return
        }
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                controller.apply(this)
                set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    if (triggerStart) {
                        CaptureRequest.CONTROL_AF_TRIGGER_START
                    } else {
                        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
                    },
                )
            }.build()
            session.capture(request, previewCaptureCallback, cameraHandler)
        } catch (error: IllegalArgumentException) {
            publishControlError(
                CameraErrorCode.INVALID_CONTROL_VALUE,
                "The camera rejected the autofocus trigger",
                error,
            )
        } catch (error: CameraAccessException) {
            failAndClose(accessError("Unable to trigger autofocus", error))
        } catch (error: IllegalStateException) {
            failAndClose(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Preview session closed while triggering autofocus",
                    error,
                ),
            )
        }
    }

    private fun scheduleTapFocusReset() {
        tapFocusResetRunnable?.let(cameraHandler::removeCallbacks)
        val runnable = Runnable {
            tapFocusResetRunnable = null
            if (requestController?.requestedState?.afLockRequested == false) {
                triggerAutoFocus(triggerStart = false)
            }
        }
        tapFocusResetRunnable = runnable
        cameraHandler.postDelayed(runnable, TAP_FOCUS_HOLD_MS)
    }

    private fun captureOnCameraThread() {
        checkCameraThread()
        val device = sessionController.cameraDevice
        val session = sessionController.captureSession
        val jpegReader = sessionController.imageReader
        val rawReader = sessionController.rawImageReader
        val camera = _selectedCamera.value
        val controller = requestController
        if (device == null || session == null || jpegReader == null ||
            camera == null || controller == null
        ) {
            failAndClose(
                CameraError(CameraErrorCode.CAMERA_UNAVAILABLE, "Camera is not ready to capture"),
            )
            return
        }
        if (!storagePermissionGranted) {
            _captureStatus.value = CaptureStatus.Failed(
                CameraError(
                    CameraErrorCode.PERMISSION_DENIED,
                    "Photo storage permission is required on this Android version",
                ),
            )
            return
        }
        if (_captureStatus.value is CaptureStatus.Capturing ||
            _captureStatus.value is CaptureStatus.Saving
        ) return

        val rawAvailable = rawReader != null && _rawCaptureAvailable.value
        val mode = CaptureModeResolver.resolve(controller.requestedState.captureMode, rawAvailable)
        if (mode != controller.requestedState.captureMode) {
            controller.update(controller.requestedState.copy(captureMode = mode))
            publishRequestedControls(controller)
            publishControlError(
                CameraErrorCode.RAW_UNSUPPORTED,
                "RAW is unavailable for this session; capturing JPEG",
            )
        }
        val expected = when (mode) {
            CaptureMode.JPEG_ONLY -> setOf(CaptureOutputType.JPEG)
            CaptureMode.RAW_ONLY -> setOf(CaptureOutputType.DNG)
            CaptureMode.JPEG_AND_RAW -> setOf(CaptureOutputType.JPEG, CaptureOutputType.DNG)
        }
        val baseName = "JC_${FILE_NAME_FORMAT.format(Date())}"
        val batch = beginCapture(mode, expected, baseName)

        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                if (CaptureOutputType.JPEG in expected) {
                    addTarget(jpegReader.surface)
                    set(
                        CaptureRequest.JPEG_ORIENTATION,
                        OrientationCalculator.jpegOrientation(
                            camera.sensorOrientation,
                            displayRotationDegrees,
                            camera.facing,
                        ),
                    )
                }
                if (CaptureOutputType.DNG in expected) addTarget(rawReader!!.surface)
                controller.apply(this)
            }.build()
            transition(CameraEvent.CaptureStarted(camera.cameraId))
            session.capture(request, stillCaptureCallback(batch), cameraHandler)
        } catch (error: CameraAccessException) {
            failCaptureRequest(batch.token, "Camera rejected the still capture request", error)
        } catch (error: IllegalStateException) {
            failCaptureRequest(batch.token, "Capture session closed before the request", error)
        } catch (error: IllegalArgumentException) {
            failCaptureRequest(batch.token, "Capture request controls or targets were rejected", error)
        }
    }

    private fun stillCaptureCallback(batch: ActiveCapture) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (!isActiveCapture(batch)) return
                publishMetadata(result, throttled = false)
                _selectedCamera.value?.cameraId?.let {
                    transition(CameraEvent.PreviewStarted(it))
                }
                val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                if (CaptureOutputType.JPEG in batch.expectedOutputs) {
                    if (timestamp == null) {
                        completeOutputFailure(
                            batch.token,
                            CaptureOutputType.JPEG,
                            CameraError(
                                CameraErrorCode.CAPTURE_FAILED,
                                "JPEG capture result did not contain a sensor timestamp",
                            ),
                        )
                    } else {
                        handleJpegPairingUpdate(
                            batch.token,
                            jpegPairing.offerResult(timestamp, Unit),
                        )
                    }
                }
                if (CaptureOutputType.DNG in batch.expectedOutputs) {
                    if (timestamp == null) {
                        completeOutputFailure(
                            batch.token,
                            CaptureOutputType.DNG,
                            CameraError(
                                CameraErrorCode.RAW_PAIRING_FAILED,
                                "RAW capture result did not contain a sensor timestamp",
                            ),
                        )
                    } else {
                        handlePairingUpdate(batch.token, rawPairing.offerResult(timestamp, result))
                    }
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                failCaptureRequest(
                    batch.token,
                    "Still capture failed (reason=${failure.reason}, frame=${failure.frameNumber})",
                    null,
                )
            }

            override fun onCaptureSequenceAborted(
                session: CameraCaptureSession,
                sequenceId: Int,
            ) {
                failCaptureRequest(batch.token, "Still capture sequence was aborted", null)
            }
        }

    private fun onJpegImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            currentCaptureTokenFor(CaptureOutputType.JPEG)?.let { token ->
                completeOutputFailure(
                    token,
                    CaptureOutputType.JPEG,
                    CameraError(
                        CameraErrorCode.CAPTURE_FAILED,
                        "JPEG ImageReader queue is unavailable",
                        error,
                    ),
                )
            }
            return
        } ?: return

        val captureToken = currentCaptureTokenFor(CaptureOutputType.JPEG)
        if (captureToken == null) {
            image.close()
            return
        }
        val imageTimestamp = image.timestamp
        val bytes = try {
            val buffer = image.planes.first().buffer
            ByteArray(buffer.remaining()).also(buffer::get)
        } catch (error: RuntimeException) {
            completeOutputFailure(
                captureToken,
                CaptureOutputType.JPEG,
                CameraError(CameraErrorCode.CAPTURE_FAILED, "Unable to read JPEG image", error),
            )
            return
        } finally {
            image.close()
        }
        handleJpegPairingUpdate(
            captureToken,
            jpegPairing.offerImage(imageTimestamp, bytes),
        )
    }

    private fun onRawImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            currentCaptureTokenFor(CaptureOutputType.DNG)?.let { token ->
                completeOutputFailure(
                    token,
                    CaptureOutputType.DNG,
                    CameraError(
                        CameraErrorCode.RAW_CAPTURE_FAILED,
                        "RAW ImageReader queue is unavailable",
                        error,
                    ),
                )
            }
            return
        } ?: return

        val captureToken = currentCaptureTokenFor(CaptureOutputType.DNG)
        if (captureToken == null) {
            image.close()
            return
        }
        handlePairingUpdate(captureToken, rawPairing.offerImage(image.timestamp, image))
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
            launchSave {
                val displayName = "${batch.baseName}.jpg"
                val result = jpegSaver.save(pair.image, displayName)
                result.fold(
                    onSuccess = {
                        completeOutputSuccess(
                            batch.token,
                            CaptureOutputType.JPEG,
                            it.displayName,
                        )
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
        }
    }

    private fun handlePairingUpdate(
        captureToken: Long,
        update: DngPairingUpdate<Image, TotalCaptureResult>,
    ) {
        update.discardedImages.forEach(Image::close)
        update.ready.forEach { pair -> saveDngPair(captureToken, pair) }
    }

    private fun saveDngPair(
        captureToken: Long,
        pair: DngPair<Image, TotalCaptureResult>,
    ) {
        val batch = beginOutputForToken(captureToken, CaptureOutputType.DNG)
        val characteristics = activeCharacteristics
        if (batch == null || characteristics == null) {
            pair.image.close()
            completeOutputFailure(
                captureToken,
                CaptureOutputType.DNG,
                CameraError(
                    CameraErrorCode.RAW_PAIRING_FAILED,
                    "Camera characteristics were unavailable for DNG encoding",
                ),
            )
            return
        }
        launchSave {
            val displayName = "${batch.baseName}.dng"
            val result = dngSaver.save(characteristics, pair.result, pair.image, displayName)
            result.fold(
                onSuccess = {
                    completeOutputSuccess(captureToken, CaptureOutputType.DNG, it.displayName)
                },
                onFailure = { error ->
                    val code = if (error is IOException || error is SecurityException) {
                        CameraErrorCode.STORAGE_FAILED
                    } else {
                        CameraErrorCode.DNG_ENCODING_FAILED
                    }
                    completeOutputFailure(
                        captureToken,
                        CaptureOutputType.DNG,
                        CameraError(code, "Failed to save $displayName", error),
                    )
                },
            )
        }
    }

    private fun beginCapture(
        mode: CaptureMode,
        expectedOutputs: Set<CaptureOutputType>,
        baseName: String,
    ): ActiveCapture {
        clearPendingImagePairs()
        val batch = ActiveCapture(
            token = ++nextCaptureToken,
            generation = cameraCallbackGeneration,
            mode = mode,
            expectedOutputs = expectedOutputs,
            baseName = baseName,
            tracker = CaptureOutcomeTracker(mode, expectedOutputs),
        )
        synchronized(captureLock) { activeCapture = batch }
        _captureStatus.value = CaptureStatus.Capturing(mode)
        scheduleCaptureTimeout(batch.token)
        return batch
    }

    private fun beginOutputForToken(
        token: Long,
        type: CaptureOutputType,
    ): ActiveCapture? {
        var allOutputsStarted = false
        val batch = synchronized(captureLock) {
            activeCapture?.takeIf {
                it.token == token && it.generation == cameraCallbackGeneration &&
                    type in it.expectedOutputs && it.tracker.begin(type)
            }?.also {
                _captureStatus.value = it.tracker.saving()
                allOutputsStarted = !it.tracker.hasUnstartedOutputs()
            }
        }
        if (allOutputsStarted) cancelCaptureTimeout()
        return batch
    }

    private fun currentCaptureTokenFor(type: CaptureOutputType): Long? =
        synchronized(captureLock) {
            activeCapture?.takeIf { type in it.expectedOutputs }?.token
        }

    private fun isActiveCapture(batch: ActiveCapture): Boolean = synchronized(captureLock) {
        activeCapture?.let { it.token == batch.token && it.generation == batch.generation } == true &&
            batch.generation == cameraCallbackGeneration
    }

    private fun completeOutputSuccess(
        token: Long,
        type: CaptureOutputType,
        displayName: String,
    ) {
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
        var completed = false
        var allOutputsStarted = false
        val status = synchronized(captureLock) {
            val batch = activeCapture?.takeIf { it.token == token } ?: return
            val next = update(batch.tracker)
            completed = batch.tracker.isComplete()
            allOutputsStarted = !batch.tracker.hasUnstartedOutputs()
            if (completed) activeCapture = null
            next
        }
        _captureStatus.value = status
        if (completed || allOutputsStarted) cancelCaptureTimeout()
    }

    private fun failCaptureRequest(token: Long, message: String, cause: Throwable?) {
        clearPendingImagePairs()
        val error = CameraError(CameraErrorCode.CAPTURE_FAILED, message, cause)
        JcLog.error(LogCategory.CAPTURE, message, cause)
        var completed = false
        val status = synchronized(captureLock) {
            val batch = activeCapture?.takeIf { it.token == token } ?: return
            val next = batch.tracker.failPending { type ->
                if (type == CaptureOutputType.DNG) {
                    error.copy(code = CameraErrorCode.RAW_CAPTURE_FAILED)
                } else {
                    error
                }
            }
            completed = true
            activeCapture = null
            next
        }
        _captureStatus.value = status
        if (completed) cancelCaptureTimeout()
        _selectedCamera.value?.cameraId?.let { transition(CameraEvent.PreviewStarted(it)) }
    }

    private fun scheduleCaptureTimeout(token: Long) {
        cancelCaptureTimeout()
        val runnable = Runnable {
            captureTimeoutRunnable = null
            clearPendingImagePairs()
            var status: CaptureStatus? = null
            synchronized(captureLock) {
                val batch = activeCapture?.takeIf { it.token == token } ?: return@Runnable
                status = batch.tracker.failPending { type ->
                    CameraError(
                        if (type == CaptureOutputType.DNG) {
                            CameraErrorCode.RAW_PAIRING_FAILED
                        } else {
                            CameraErrorCode.CAPTURE_FAILED
                        },
                        "Timed out waiting for ${type.name} capture output",
                    )
                }
                activeCapture = null
            }
            status?.let { _captureStatus.value = it }
        }
        captureTimeoutRunnable = runnable
        cameraHandler.postDelayed(runnable, CAPTURE_TIMEOUT_MS)
    }

    private fun cancelActiveCapture(resetStatus: Boolean) {
        cancelCaptureTimeout()
        synchronized(captureLock) { activeCapture = null }
        clearPendingImagePairs()
        if (resetStatus && (_captureStatus.value is CaptureStatus.Capturing ||
                _captureStatus.value is CaptureStatus.Saving)
        ) {
            _captureStatus.value = CaptureStatus.Idle
        }
    }

    private fun clearPendingImagePairs() {
        rawPairing.clear().forEach(Image::close)
        jpegPairing.clear()
    }

    private fun cancelCaptureTimeout() {
        captureTimeoutRunnable?.let(cameraHandler::removeCallbacks)
        captureTimeoutRunnable = null
    }

    private fun publishMetadata(result: TotalCaptureResult, throttled: Boolean) {
        val controller = requestController ?: return
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        if (throttled && lastMetadataPublishTimestamp != Long.MIN_VALUE &&
            timestamp - lastMetadataPublishTimestamp < METADATA_INTERVAL_NS
        ) return
        lastMetadataPublishTimestamp = timestamp
        _captureMetadata.value = controller.metadata(result)
    }

    private fun setEffectiveRawAvailability(available: Boolean) {
        _rawCaptureAvailable.value = available
        requestController?.let { controller ->
            controller.setRawAvailable(available)
            publishRequestedControls(controller)
        }
    }

    private fun disableRawTopology(cameraId: String) {
        rawTopologyDisabledCameraIds += cameraId
        setEffectiveRawAvailability(false)
        JcLog.warn(LogCategory.CAMERA, "RAW session topology disabled for camera $cameraId")
    }

    private fun publishRequestedControls(controller: CameraRequestController) {
        _controlState.value = controller.requestedState
        _controlCapabilities.value = controller.capabilities
    }

    private fun publishControlError(
        code: CameraErrorCode,
        message: String,
        cause: Throwable? = null,
    ) {
        _controlError.value = CameraError(code, message, cause, recoverable = true)
        JcLog.warn(LogCategory.CAMERA, message, cause)
    }

    private fun launchSave(block: suspend () -> Unit) {
        lateinit var job: Job
        job = ioScope.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(saveJobsLock) { activeSaveJobs += job }
        job.invokeOnCompletion {
            synchronized(saveJobsLock) {
                activeSaveJobs -= job
                if (released && activeSaveJobs.isEmpty()) ioScope.cancel()
            }
        }
        job.start()
    }

    private fun closeResources(emitClosed: Boolean) {
        checkCameraThread()
        cancelScheduledRetry(resetAttempts = true)
        tapFocusResetRunnable?.let(cameraHandler::removeCallbacks)
        tapFocusResetRunnable = null
        cameraCallbackGeneration++
        cancelActiveCapture(resetStatus = true)
        sessionController.closeAll()
        activeCharacteristics = null
        _previewSize.value = null
        _rawCaptureAvailable.value = false
        lastMetadataPublishTimestamp = Long.MIN_VALUE
        if (emitClosed && _state.value != CameraState.Closed) transition(CameraEvent.Close)
    }

    private fun transition(event: CameraEvent) {
        val current = _state.value
        val next = CameraStateReducer.reduce(current, event)
        if (next != current) _state.value = next
    }

    private fun failAndClose(error: CameraError) {
        checkCameraThread()
        cancelScheduledRetry(resetAttempts = false)
        tapFocusResetRunnable?.let(cameraHandler::removeCallbacks)
        tapFocusResetRunnable = null
        cameraCallbackGeneration++
        cancelActiveCapture(resetStatus = true)
        sessionController.closeAll()
        activeCharacteristics = null
        _previewSize.value = null
        _rawCaptureAvailable.value = false
        publishError(error)
        scheduleRetry(error)
    }

    private fun publishError(error: CameraError) {
        transition(CameraEvent.Failed(error))
        JcLog.error(LogCategory.CAMERA, error.message, error.cause)
    }

    private fun scheduleRetry(error: CameraError) {
        val decision = recoveryPolicy.decide(
            error = error,
            completedAttempts = completedRetryAttempts,
            reopenPrerequisitesReady = canReopen(),
        )
        if (!decision.shouldRetry) return

        completedRetryAttempts++
        val expectedGeneration = cameraCallbackGeneration
        val runnable = Runnable {
            retryRunnable = null
            if (expectedGeneration == cameraCallbackGeneration && canReopen()) {
                openSelectedCameraIfReady()
            }
        }
        retryRunnable = runnable
        if (!cameraHandler.postDelayed(runnable, decision.delayMillis)) {
            retryRunnable = null
            JcLog.warn(LogCategory.CAMERA, "Camera retry could not be scheduled")
        }
    }

    private fun cancelScheduledRetry(resetAttempts: Boolean) {
        retryRunnable?.let(cameraHandler::removeCallbacks)
        retryRunnable = null
        if (resetAttempts) completedRetryAttempts = 0
    }

    private fun canReopen(): Boolean = running && cameraPermissionGranted &&
        surfaceTexture != null && _selectedCamera.value != null

    private fun isCurrentCallback(cameraId: String, callbackGeneration: Long): Boolean =
        callbackGeneration == cameraCallbackGeneration &&
            _selectedCamera.value?.cameraId == cameraId

    private fun checkCameraThread() {
        check(Looper.myLooper() === cameraThread.looper) {
            "CameraEngine resource orchestration must run on the camera thread"
        }
    }

    private fun accessError(message: String, cause: CameraAccessException) = CameraError(
        CameraErrorCode.ACCESS_FAILURE,
        "$message (reason=${cause.reason})",
        cause,
        recoverable = cause.reason != CameraAccessException.CAMERA_DISABLED,
    )

    private fun deviceError(cameraId: String, error: Int) = CameraError(
        CameraErrorCode.CAMERA_UNAVAILABLE,
        "Camera $cameraId error: ${deviceErrorName(error)}",
        recoverable = error != CameraDevice.StateCallback.ERROR_CAMERA_DISABLED,
    )

    private fun deviceErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera in use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "maximum cameras in use"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "disabled by policy"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "device failure"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service failure"
        else -> "unknown ($error)"
    }

    private data class ActiveCapture(
        val token: Long,
        val generation: Long,
        val mode: CaptureMode,
        val expectedOutputs: Set<CaptureOutputType>,
        val baseName: String,
        val tracker: CaptureOutcomeTracker,
    )

    private companion object {
        const val RAW_READER_MAX_IMAGES = 3
        const val CAPTURE_TIMEOUT_MS = 12_000L
        const val TAP_FOCUS_HOLD_MS = 2_000L
        const val METADATA_INTERVAL_NS = 100_000_000L
        val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
