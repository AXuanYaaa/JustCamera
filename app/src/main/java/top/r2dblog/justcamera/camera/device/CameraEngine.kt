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
import android.hardware.camera2.CameraMetadata
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
import android.util.Log
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
import top.r2dblog.justcamera.camera.hdr.HdrCaptureCoordinator
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
import top.r2dblog.justcamera.camera.session.PreviewBufferSize
import top.r2dblog.justcamera.camera.session.PreviewGeometry
import top.r2dblog.justcamera.camera.session.PreviewMeteringCropCalculator
import top.r2dblog.justcamera.camera.session.PreviewRotation
import top.r2dblog.justcamera.camera.session.PreviewSizeSelector
import top.r2dblog.justcamera.camera.session.PreviewTransformCalculator
import top.r2dblog.justcamera.camera.session.PreviewTransformReport
import top.r2dblog.justcamera.camera.session.PreviewViewportSize
import top.r2dblog.justcamera.camera.session.toPreviewBufferSize
import top.r2dblog.justcamera.hdr.capture.HdrBracketConstraints
import top.r2dblog.justcamera.hdr.capture.HdrCapabilityAssessment
import top.r2dblog.justcamera.hdr.capture.HdrCapabilityPolicy
import top.r2dblog.justcamera.hdr.capture.HdrCapturePlan
import top.r2dblog.justcamera.hdr.capture.HdrCaptureStartResult
import top.r2dblog.justcamera.hdr.capture.HdrExposureBaseline
import top.r2dblog.justcamera.hdr.capture.HdrMode
import top.r2dblog.justcamera.hdr.capture.HdrRequestTag
import top.r2dblog.justcamera.hdr.capture.HdrStatus
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import java.util.Locale
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
    private val hdrCaptureCoordinator = HdrCaptureCoordinator(
        cameraLooper = cameraThread.looper,
        cameraHandler = cameraHandler,
        onCaptureTerminal = ::returnToPreviewAfterCapture,
    )
    val hdrStatus: StateFlow<HdrStatus> = hdrCaptureCoordinator.status
    val hdrLastOutput = hdrCaptureCoordinator.lastOutput
    private val _hdrMode = MutableStateFlow(HdrMode.OFF)
    val hdrMode: StateFlow<HdrMode> = _hdrMode.asStateFlow()
    private val _hdrCapability = MutableStateFlow<HdrCapabilityAssessment?>(null)
    val hdrCapability: StateFlow<HdrCapabilityAssessment?> = _hdrCapability.asStateFlow()
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
    private var configuredPreviewBufferSize: PreviewBufferSize? = null
    private var lastPreviewTransformReport: PreviewTransformReport? = null
    private var lastPreviewGeometryLog: String? = null
    private var observedRotateAndCropMode: Int? = null
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
            hdrCaptureCoordinator.release()
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
            val changed = surfaceWidth != width || surfaceHeight != height ||
                displayRotationDegrees != rotationDegrees
            surfaceWidth = width
            surfaceHeight = height
            displayRotationDegrees = rotationDegrees
            if (changed) logPreviewGeometry("geometry_changed")
        }
    }

    fun reportPreviewTransform(report: PreviewTransformReport) {
        cameraHandler.post {
            lastPreviewTransformReport = report
            logPreviewGeometry("transform_applied", report)
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
            val bufferSize = _previewSize.value ?: return@post
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return@post
            val bufferPoint = PreviewTransformCalculator.calculate(
                geometry = PreviewGeometry(
                    bufferSize = bufferSize.toPreviewBufferSize(),
                    sensorOrientation = PreviewRotation.fromDegrees(camera.sensorOrientation),
                    displayRotation = PreviewRotation.fromDegrees(displayRotationDegrees),
                    cameraFacing = camera.facing,
                ),
                viewportSize = PreviewViewportSize(surfaceWidth, surfaceHeight),
            ).mapNormalizedViewportToBuffer(normalizedX, normalizedY)
            val zoom = controller.requestedState.zoomRatio
            val crop = if (zoom >= 1f) ZoomCropCalculator.crop(activeArray, zoom) else activeArray
            val visibleSensorCrop = PreviewMeteringCropCalculator.visibleSensorCrop(
                sensorCrop = crop,
                previewBuffer = bufferSize,
            )
            val region = MeteringRegionMapper.map(
                normalizedX = bufferPoint.x,
                normalizedY = bufferPoint.y,
                cropRegion = visibleSensorCrop,
                relativeRotationDegrees = 0,
                mirrorHorizontally = false,
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

    fun setHdrMode(mode: HdrMode) {
        cameraHandler.post {
            if (mode == _hdrMode.value) return@post
            val assessment = _selectedCamera.value?.let(HdrCapabilityPolicy::assess)
            if (mode == HdrMode.ON && assessment?.captureEnabled != true) {
                val reason = assessment?.reason ?: "No selected camera is available for HDR"
                hdrCaptureCoordinator.rejectTopology(reason)
                _hdrMode.value = HdrMode.OFF
                return@post
            }
            if (stillCaptureCoordinator.isBusy() || hdrCaptureCoordinator.isBusy()) return@post
            closeResources(emitClosed = true)
            _hdrMode.value = mode
            if (running) openSelectedCameraIfReady()
        }
    }

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
        updateHdrCapability(camera)
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
        updateHdrCapability(camera)
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

    private fun updateHdrCapability(camera: CameraCapabilities) {
        val assessment = HdrCapabilityPolicy.assess(camera)
        _hdrCapability.value = assessment
        if (_hdrMode.value == HdrMode.ON && !assessment.captureEnabled) {
            _hdrMode.value = HdrMode.OFF
            hdrCaptureCoordinator.rejectTopology(assessment.reason)
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
            val selectedSize = PreviewSizeSelector.select(choices)
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
            configuredPreviewBufferSize = selectedSize.toPreviewBufferSize()
            sessionController.replacePreviewSurface(texture)
            _previewSize.value = selectedSize
            check(configuredPreviewBufferSize == _previewSize.value?.toPreviewBufferSize()) {
                "Selected preview size and SurfaceTexture default buffer size diverged"
            }
            logPreviewGeometry("buffer_configured")
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
        if (_hdrMode.value == HdrMode.ON) {
            val assessment = _hdrCapability.value
            if (assessment?.captureEnabled == true && assessment.processingSize != null) {
                configureHdrSession(
                    device,
                    cameraId,
                    callbackGeneration,
                    previewSurface,
                    assessment,
                )
            } else {
                fallbackFromHdrTopology(
                    cameraId,
                    assessment?.reason ?: "HDR capability assessment is unavailable",
                    null,
                )
            }
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
                        createOutputConfigurations(outputSurfaces, previewSurface),
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

    private fun configureHdrSession(
        device: CameraDevice,
        cameraId: String,
        callbackGeneration: Long,
        previewSurface: Surface,
        assessment: HdrCapabilityAssessment,
    ) {
        val size = assessment.processingSize ?: run {
            fallbackFromHdrTopology(cameraId, "No bounded HDR YUV size is available", null)
            return
        }
        try {
            val hdrReader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                HDR_READER_MAX_IMAGES,
            ).apply {
                setOnImageAvailableListener(
                    { reader ->
                        hdrCaptureCoordinator.onYuvImageAvailable(reader, callbackGeneration)
                    },
                    imageHandler,
                )
            }
            sessionController.replaceHdrImageReader(
                hdrReader,
                hdrCaptureCoordinator::retireReader,
            )
            hdrCaptureCoordinator.registerReader(hdrReader, callbackGeneration)
            transition(CameraEvent.Configure(cameraId))
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isCurrentCallback(cameraId, callbackGeneration) ||
                        device !== sessionController.cameraDevice || !running ||
                        _hdrMode.value != HdrMode.ON
                    ) {
                        sessionController.closeUnownedSession(session)
                        return
                    }
                    sessionController.adoptCaptureSession(session)
                    setEffectiveRawAvailability(false)
                    startPreview(device, session, previewSurface, cameraId)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionController.closeUnownedSession(session)
                    if (!isCurrentCallback(cameraId, callbackGeneration)) return
                    fallbackFromHdrTopology(
                        cameraId,
                        "Camera $cameraId rejected preview + bounded YUV HDR; retrying standard capture",
                        null,
                    )
                }
            }
            val surfaces = listOf(previewSurface, hdrReader.surface)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        createOutputConfigurations(surfaces, previewSurface),
                        Executor { runnable -> cameraHandler.post(runnable) },
                        sessionCallback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, sessionCallback, cameraHandler)
            }
        } catch (error: CameraAccessException) {
            fallbackFromHdrTopology(
                cameraId,
                "Unable to configure HDR session for camera $cameraId",
                error,
            )
        } catch (error: IllegalArgumentException) {
            fallbackFromHdrTopology(
                cameraId,
                "Camera $cameraId rejected the HDR YUV output topology",
                error,
            )
        } catch (error: IllegalStateException) {
            fallbackFromHdrTopology(
                cameraId,
                "Camera $cameraId closed while configuring HDR",
                error,
            )
        }
    }

    private fun fallbackFromHdrTopology(cameraId: String, message: String, cause: Throwable?) {
        checkCameraThread()
        _hdrMode.value = HdrMode.OFF
        failAndClose(
            CameraError(
                CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                message,
                cause,
                recoverable = true,
            ),
        )
        hdrCaptureCoordinator.rejectTopology(message)
        JcLog.warn(LogCategory.CAMERA, "HDR topology disabled for camera $cameraId tenure", cause)
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
                applyPreviewRotateAndCropPolicy(this)
            }.build()
            session.setRepeatingRequest(request, previewCaptureCallback, cameraHandler)
            cancelScheduledRetry(resetAttempts = true)
            transition(CameraEvent.PreviewStarted(cameraId))
            logPreviewGeometry("preview_started")
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
                observeRotateAndCrop(result)
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
        if (previous.zoomRatio != update.state.zoomRatio) {
            logPreviewGeometry("zoom_changed")
        }
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
                applyPreviewRotateAndCropPolicy(this)
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
                applyPreviewRotateAndCropPolicy(this)
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
        if (_hdrMode.value == HdrMode.ON) {
            captureHdrOnCameraThread()
            return
        }
        hdrCaptureCoordinator.dismissFailure()
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

    private fun captureHdrOnCameraThread() {
        checkCameraThread()
        val device = sessionController.cameraDevice
        val session = sessionController.captureSession
        val hdrReader = sessionController.hdrImageReader
        val camera = _selectedCamera.value
        val controller = requestController
        val assessment = _hdrCapability.value
        if (device == null || session == null || hdrReader == null || camera == null ||
            controller == null || assessment?.captureEnabled != true
        ) {
            hdrCaptureCoordinator.rejectCapture("HDR session is not ready to capture")
            return
        }
        if (stillCaptureCoordinator.isBusy() || hdrCaptureCoordinator.isBusy()) return

        val metadata = _captureMetadata.value
        val exposureTime = metadata.exposureTimeNanos
        val sensitivity = metadata.sensitivityIso
        if (exposureTime == null || sensitivity == null) {
            hdrCaptureCoordinator.rejectCapture(
                "Waiting for a preview result with actual exposure time and ISO",
            )
            return
        }
        val start = hdrCaptureCoordinator.beginCapture(
            generation = cameraCallbackGeneration,
            baseline = HdrExposureBaseline(
                exposureTimeNanos = exposureTime,
                sensitivityIso = sensitivity,
                frameDurationNanos = metadata.frameDurationNanos,
            ),
            constraints = HdrBracketConstraints(
                manualSensor = camera.supportsManualSensor,
                sensitivityRange = camera.sensitivityRange,
                exposureTimeRangeNanos = camera.exposureTimeRangeNanos,
                maxFrameDurationNanos = camera.maxFrameDurationNanos,
                minimumFrameDurationNanos = assessment.minimumFrameDurationNanos,
            ),
            outputRotationDegrees = OrientationCalculator.jpegOrientation(
                camera.sensorOrientation,
                displayRotationDegrees,
                camera.facing,
            ),
        )
        val plan = when (start) {
            is HdrCaptureStartResult.Started -> start.plan
            is HdrCaptureStartResult.Rejected -> return
        }

        try {
            val requests = plan.bracket.entries.map { entry ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(hdrReader.surface)
                    controller.apply(this)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, entry.exposureTimeNanos)
                    set(CaptureRequest.SENSOR_SENSITIVITY, entry.sensitivityIso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, entry.frameDurationNanos)
                    if (camera.awbLockAvailable) {
                        set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    }
                    setTag(HdrRequestTag(plan.token, entry.frameIndex))
                }.build()
            }
            transition(CameraEvent.CaptureStarted(camera.cameraId))
            val callback = hdrCaptureCallback(plan)
            val sequenceIds = if (assessment.burstCapture) {
                listOf(session.captureBurst(requests, callback, cameraHandler))
            } else {
                requests.map { request -> session.capture(request, callback, cameraHandler) }
            }
            hdrCaptureCoordinator.onSequencesSubmitted(plan, sequenceIds)
        } catch (error: CameraAccessException) {
            hdrCaptureCoordinator.onCaptureRequestFailed(
                plan,
                "Camera rejected the HDR bracket",
                error,
            )
        } catch (error: IllegalStateException) {
            hdrCaptureCoordinator.onCaptureRequestFailed(
                plan,
                "HDR capture session closed before the bracket was submitted",
                error,
            )
        } catch (error: IllegalArgumentException) {
            hdrCaptureCoordinator.onCaptureRequestFailed(
                plan,
                "Camera rejected the HDR bracket controls or YUV target",
                error,
            )
        }
    }

    private fun hdrCaptureCallback(plan: HdrCapturePlan) =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val tag = request.tag as? HdrRequestTag ?: return
                if (tag.token != plan.token || !hdrCaptureCoordinator.isActive(plan)) return
                publishMetadata(result, throttled = false)
                hdrCaptureCoordinator.onCaptureResult(plan, tag.frameIndex, result)
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                hdrCaptureCoordinator.onCaptureRequestFailed(
                    plan,
                    "HDR bracket frame failed (reason=${failure.reason}, frame=${failure.frameNumber})",
                    null,
                )
            }

            override fun onCaptureSequenceAborted(
                session: CameraCaptureSession,
                sequenceId: Int,
            ) {
                hdrCaptureCoordinator.onCaptureRequestFailed(
                    plan,
                    "HDR bracket sequence was aborted",
                    null,
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

    private fun createOutputConfigurations(
        surfaces: List<Surface>,
        previewSurface: Surface,
    ): List<OutputConfiguration> = surfaces.map { surface ->
        OutputConfiguration(surface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                surface === previewSurface
            ) {
                // The producer owns front-preview mirroring. The app matrix never mirrors again.
                setMirrorMode(OutputConfiguration.MIRROR_MODE_AUTO)
            }
        }
    }

    private fun applyPreviewRotateAndCropPolicy(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val availableModes = availableRotateAndCropModes()
        if (CameraMetadata.SCALER_ROTATE_AND_CROP_NONE in availableModes) {
            // Own preview rotation in the application only after confirming NONE is supported.
            builder.set(
                CaptureRequest.SCALER_ROTATE_AND_CROP,
                CameraMetadata.SCALER_ROTATE_AND_CROP_NONE,
            )
        }
    }

    private fun observeRotateAndCrop(result: TotalCaptureResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val observed = result.get(CaptureResult.SCALER_ROTATE_AND_CROP) ?: return
        if (observedRotateAndCropMode != observed) {
            observedRotateAndCropMode = observed
            logPreviewGeometry("rotate_crop_observed")
        }
    }

    private fun availableRotateAndCropModes(): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return activeCharacteristics
            ?.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
            ?.toList()
            .orEmpty()
    }

    private fun logPreviewGeometry(
        stage: String,
        report: PreviewTransformReport? = lastPreviewTransformReport,
    ) {
        checkCameraThread()
        val camera = _selectedCamera.value
        val selected = _previewSize.value?.toPreviewBufferSize()
        val relativeRotation = camera?.let {
            OrientationCalculator.relativePreviewRotationDegrees(
                it.sensorOrientation,
                displayRotationDegrees,
                it.facing,
            )
        }
        val currentCrop = run {
            val activeArray = camera?.activeArray
            val zoomRatio = requestController?.requestedState?.zoomRatio
            if (activeArray != null && zoomRatio != null && zoomRatio >= 1f) {
                ZoomCropCalculator.crop(activeArray, zoomRatio)
            } else {
                activeArray
            }
        }
        val modes = availableRotateAndCropModes()
        val rotateCropRequest = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "unavailable"
            CameraMetadata.SCALER_ROTATE_AND_CROP_NONE in modes -> "NONE(app-owned)"
            else -> "DEFAULT_AUTO"
        }
        val reportMatchesSelected = report?.bufferSize == selected
        val surfaceIdentity = surfaceTexture?.let(System::identityHashCode)
        val sameSurfaceTexture = report?.surfaceTextureIdentity == surfaceIdentity
        val reportMatchesEngineGeometry = report != null &&
            report.displayRotation.degrees == displayRotationDegrees &&
            report.relativeRotation.degrees == relativeRotation
        val message = buildString {
            append("stage=").append(stage)
            append(" camera=").append(camera?.cameraId ?: "none")
            append(" facing=").append(camera?.facing ?: "unknown")
            append(" sensor=").append(camera?.sensorOrientation ?: "unknown")
            append(" display=").append(displayRotationDegrees)
            append(" relative=").append(relativeRotation ?: "unknown")
            append(" reportDisplay=").append(report?.displayRotation?.degrees ?: "unknown")
            append(" reportRelative=").append(report?.relativeRotation?.degrees ?: "unknown")
            append(" view=").append(report?.viewportSize ?: "${surfaceWidth}x$surfaceHeight")
            append(" window=").append(report?.windowSize ?: "unknown")
            append(" screen=").append(report?.screenOrientation ?: "unknown")
            append(" selected=").append(selected ?: "unknown")
            append(" defaultBufferApplied=").append(configuredPreviewBufferSize ?: "unknown")
            append(" reportMatchesSelected=").append(reportMatchesSelected)
            append(" reportMatchesEngineGeometry=").append(reportMatchesEngineGeometry)
            append(" sameSurfaceTexture=").append(sameSurfaceTexture)
            append(" mirrorOwner=OUTPUT_CONFIGURATION_AUTO")
            append(" rotateCropAvailable=")
                .append(modes.joinToString(prefix = "[", postfix = "]", transform = ::rotateCropName))
            append(" rotateCropRequest=").append(rotateCropRequest)
            append(" rotateCropObserved=").append(rotateCropName(observedRotateAndCropMode))
            append(" zoomCrop=").append(
                currentCrop?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "unknown",
            )
            append(" intrinsicMatrix=").append(formatMatrix(report?.intrinsicMatrixValues))
            append(" matrix=").append(formatMatrix(report?.textureViewMatrixValues))
            append(" finalMatrix=").append(formatMatrix(report?.finalMatrixValues))
        }
        if (message != lastPreviewGeometryLog) {
            lastPreviewGeometryLog = message
            Log.i(PREVIEW_GEOMETRY_TAG, message)
        }
    }

    private fun rotateCropName(mode: Int?): String = when (mode) {
        null -> "unknown"
        CameraMetadata.SCALER_ROTATE_AND_CROP_NONE -> "NONE"
        CameraMetadata.SCALER_ROTATE_AND_CROP_90 -> "90"
        CameraMetadata.SCALER_ROTATE_AND_CROP_180 -> "180"
        CameraMetadata.SCALER_ROTATE_AND_CROP_270 -> "270"
        CameraMetadata.SCALER_ROTATE_AND_CROP_AUTO -> "AUTO"
        else -> "unknown($mode)"
    }

    private fun formatMatrix(values: List<Float>?): String = values?.joinToString(
        prefix = "[",
        postfix = "]",
    ) { String.format(Locale.US, "%.5f", it) } ?: "unknown"

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
        hdrCaptureCoordinator.cancelActiveCapture(resetStatus = true)
        sessionController.closeAll(
            stillCaptureCoordinator::retireRawReader,
            hdrCaptureCoordinator::retireReader,
        )
        activeCharacteristics = null
        configuredPreviewBufferSize = null
        lastPreviewTransformReport = null
        lastPreviewGeometryLog = null
        observedRotateAndCropMode = null
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
        hdrCaptureCoordinator.cancelActiveCapture(resetStatus = true)
        sessionController.closeAll(
            stillCaptureCoordinator::retireRawReader,
            hdrCaptureCoordinator::retireReader,
        )
        activeCharacteristics = null
        configuredPreviewBufferSize = null
        lastPreviewTransformReport = null
        lastPreviewGeometryLog = null
        observedRotateAndCropMode = null
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
        const val HDR_READER_MAX_IMAGES = 4
        const val TAP_FOCUS_HOLD_MS = 2_000L
        const val METADATA_INTERVAL_NS = 100_000_000L
        const val PREVIEW_GEOMETRY_TAG = "JC_PREVIEW_GEOMETRY"
    }
}
