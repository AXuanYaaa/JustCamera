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
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
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
import top.r2dblog.justcamera.camera.capture.CapturePreviewRecoveryPolicy
import top.r2dblog.justcamera.camera.capture.StillCaptureCoordinator
import top.r2dblog.justcamera.camera.capture.StillCapturePlan
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
import top.r2dblog.justcamera.camera.raw.RawCapabilitySelector
import top.r2dblog.justcamera.camera.raw.RawTopologyFailure
import top.r2dblog.justcamera.camera.raw.RawTopologyFallbackPolicy
import top.r2dblog.justcamera.camera.session.CameraRecoveryPolicy
import top.r2dblog.justcamera.camera.session.CameraSessionController
import top.r2dblog.justcamera.camera.session.OrientationCalculator
import top.r2dblog.justcamera.camera.session.PreviewSizeSelector
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import java.util.concurrent.Executor

internal class CameraEngine(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val scanner = CameraCapabilityScanner(cameraManager)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cameraThread = HandlerThread("JustCamera-Camera").apply { start() }
    private val imageThread = HandlerThread("JustCamera-ImageAcquire").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val sessionController = CameraSessionController(cameraThread.looper)
    private val recoveryPolicy = CameraRecoveryPolicy()

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    val state: StateFlow<CameraState> = _state.asStateFlow()
    private val stillCaptureCoordinator = StillCaptureCoordinator(
        context = appContext,
        cameraLooper = cameraThread.looper,
        cameraHandler = cameraHandler,
        onCaptureTerminal = ::returnToPreviewAfterCapture,
    )
    val captureStatus: StateFlow<CaptureStatus> = stillCaptureCoordinator.status
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
    private var discoveryJob: Job? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var displayRotationDegrees: Int = 0
    private var cameraCallbackGeneration = 0L
    private var completedRetryAttempts = 0
    private var retryRunnable: Runnable? = null
    private var requestController: CameraRequestController? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private val rawTopologyFallback = RawTopologyFallbackPolicy()
    private var lastMetadataPublishTimestamp = Long.MIN_VALUE

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
        discoveryJob?.cancel()
        cameraHandler.post {
            closeResources(emitClosed = true)
            stillCaptureCoordinator.release()
            cameraThread.quitSafely()
            imageThread.quitSafely()
        }
        workerScope.cancel()
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
        rawTopologyFallback.select(camera.cameraId)
        _selectedCamera.value = camera
        requestController = CameraRequestController(camera).also { controller ->
            if (rawTopologyFallback.isDisabled(camera.cameraId)) {
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
        rawTopologyFallback.select(camera.cameraId)
        val current = requestController
        if (current != null && _selectedCamera.value?.cameraId == camera.cameraId) return current
        return CameraRequestController(camera).also { controller ->
            if (rawTopologyFallback.isDisabled(camera.cameraId)) {
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

        val rawSize = if (!rawTopologyFallback.isDisabled(cameraId)) {
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
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        stillCaptureCoordinator.onJpegImageAvailable(reader, callbackGeneration)
                    },
                    imageHandler,
                )
            }
            sessionController.replaceImageReader(jpegReader)

            val rawReader = rawSize?.let { size ->
                ImageReader.newInstance(
                    size.width,
                    size.height,
                    ImageFormat.RAW_SENSOR,
                    RAW_READER_MAX_IMAGES,
                ).apply {
                    setOnImageAvailableListener(
                        { reader ->
                            stillCaptureCoordinator.onRawImageAvailable(reader, callbackGeneration)
                        },
                        imageHandler,
                    )
                }
            }
            sessionController.replaceRawImageReader(
                rawReader,
                stillCaptureCoordinator::retireRawReader,
            )
            rawReader?.let { stillCaptureCoordinator.registerRawReader(it, callbackGeneration) }

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
                    if (attemptingRawTopology) {
                        disableRawTopology(cameraId, RawTopologyFailure.CONFIGURATION_REJECTED)
                    }
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
            failAndClose(accessError("Unable to configure camera $cameraId", error))
        } catch (error: IllegalArgumentException) {
            if (attemptingRawTopology) {
                disableRawTopology(cameraId, RawTopologyFailure.OUTPUT_COMBINATION_REJECTED)
            }
            failAndClose(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Camera $cameraId rejected the capture output topology",
                    error,
                ),
            )
        } catch (error: IllegalStateException) {
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
        val characteristics = activeCharacteristics
        if (device == null || session == null || jpegReader == null ||
            camera == null || controller == null || characteristics == null
        ) {
            failAndClose(
                CameraError(CameraErrorCode.CAMERA_UNAVAILABLE, "Camera is not ready to capture"),
            )
            return
        }
        if (!storagePermissionGranted) {
            stillCaptureCoordinator.rejectCapture(
                CameraError(
                    CameraErrorCode.PERMISSION_DENIED,
                    "Photo storage permission is required on this Android version",
                ),
            )
            return
        }
        if (stillCaptureCoordinator.isBusy()) return

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
        val plan = stillCaptureCoordinator.beginCapture(
            generation = cameraCallbackGeneration,
            mode = mode,
            expectedOutputs = expected,
            characteristics = characteristics,
        )

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
            session.capture(request, stillCaptureCallback(plan), cameraHandler)
        } catch (error: CameraAccessException) {
            stillCaptureCoordinator.onCaptureRequestFailed(
                plan,
                "Camera rejected the still capture request",
                error,
            )
        } catch (error: IllegalStateException) {
            stillCaptureCoordinator.onCaptureRequestFailed(
                plan,
                "Capture session closed before the request",
                error,
            )
        } catch (error: IllegalArgumentException) {
            stillCaptureCoordinator.onCaptureRequestFailed(
                plan,
                "Capture request controls or targets were rejected",
                error,
            )
        }
    }

    private fun stillCaptureCallback(plan: StillCapturePlan) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (!stillCaptureCoordinator.isActive(plan)) return
                publishMetadata(result, throttled = false)
                returnToPreviewAfterCapture(plan.generation)
                stillCaptureCoordinator.onCaptureResult(plan, result)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                stillCaptureCoordinator.onCaptureRequestFailed(
                    plan,
                    "Still capture failed (reason=${failure.reason}, frame=${failure.frameNumber})",
                    null,
                )
            }

            override fun onCaptureSequenceAborted(
                session: CameraCaptureSession,
                sequenceId: Int,
            ) {
                stillCaptureCoordinator.onCaptureRequestFailed(
                    plan,
                    "Still capture sequence was aborted",
                    null,
                )
            }
        }

    private fun returnToPreviewAfterCapture(generation: Long) {
        checkCameraThread()
        val sessionHealthy = sessionController.cameraDevice != null &&
            sessionController.captureSession != null
        if (CapturePreviewRecoveryPolicy.shouldReturnToPreview(
                terminalGeneration = generation,
                currentGeneration = cameraCallbackGeneration,
                sessionHealthy = sessionHealthy,
                cameraStateCapturing = _state.value is CameraState.Capturing,
            )
        ) {
            _selectedCamera.value?.cameraId?.let {
                transition(CameraEvent.PreviewStarted(it))
            }
        }
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

    private fun disableRawTopology(cameraId: String, evidence: RawTopologyFailure) {
        if (rawTopologyFallback.record(cameraId, evidence)) {
            setEffectiveRawAvailability(false)
            JcLog.warn(
                LogCategory.CAMERA,
                "RAW session topology disabled for the current selection of camera $cameraId",
            )
        }
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

    private fun closeResources(emitClosed: Boolean) {
        checkCameraThread()
        cancelScheduledRetry(resetAttempts = true)
        tapFocusResetRunnable?.let(cameraHandler::removeCallbacks)
        tapFocusResetRunnable = null
        cameraCallbackGeneration++
        stillCaptureCoordinator.cancelActiveCapture(resetStatus = true)
        sessionController.closeAll(stillCaptureCoordinator::retireRawReader)
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
        stillCaptureCoordinator.cancelActiveCapture(resetStatus = true)
        sessionController.closeAll(stillCaptureCoordinator::retireRawReader)
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

    private companion object {
        const val RAW_READER_MAX_IMAGES = 3
        const val TAP_FOCUS_HOLD_MS = 2_000L
        const val METADATA_INTERVAL_NS = 100_000_000L
    }
}
