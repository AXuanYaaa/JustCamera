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
import top.r2dblog.justcamera.camera.capability.CameraCapabilityScanner
import top.r2dblog.justcamera.camera.capture.MediaStoreJpegSaver
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CameraErrorCode
import top.r2dblog.justcamera.camera.model.CameraEvent
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.CameraState
import top.r2dblog.justcamera.camera.model.CameraStateReducer
import top.r2dblog.justcamera.camera.model.CaptureStatus
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.session.CameraRecoveryPolicy
import top.r2dblog.justcamera.camera.session.CameraSessionController
import top.r2dblog.justcamera.camera.session.OrientationCalculator
import top.r2dblog.justcamera.camera.session.PreviewSizeSelector
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CameraEngine(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val scanner = CameraCapabilityScanner(cameraManager)
    private val mediaSaver = MediaStoreJpegSaver(appContext)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraThread = HandlerThread("JustCamera-Camera").apply { start() }
    private val imageThread = HandlerThread("JustCamera-ImageAcquire").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val sessionController = CameraSessionController(cameraThread.looper)
    private val recoveryPolicy = CameraRecoveryPolicy()

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

    @Volatile private var running = false
    @Volatile private var cameraPermissionGranted = false
    @Volatile private var storagePermissionGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private var discoveryJob: Job? = null
    @Volatile private var activeSaveJob: Job? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var displayRotationDegrees: Int = 0
    private var cameraCallbackGeneration = 0L
    private var completedRetryAttempts = 0
    private var retryRunnable: Runnable? = null

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
            cameraThread.quitSafely()
            imageThread.quitSafely()
        }
        workerScope.cancel()
        val saveJob = activeSaveJob
        if (saveJob == null || !saveJob.isActive) {
            ioScope.cancel()
        } else {
            saveJob.invokeOnCompletion { ioScope.cancel() }
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
            _selectedCamera.value = available[(currentIndex + 1) % available.size]
            if (running) openSelectedCameraIfReady()
        }
    }

    fun selectCamera(cameraId: String) {
        cameraHandler.post {
            val selected = _cameras.value.firstOrNull { it.cameraId == cameraId } ?: return@post
            if (selected.cameraId == _selectedCamera.value?.cameraId) return@post
            closeResources(emitClosed = true)
            _selectedCamera.value = selected
            if (running) openSelectedCameraIfReady()
        }
    }

    fun captureJpeg() {
        cameraHandler.post {
            val device = sessionController.cameraDevice
            val session = sessionController.captureSession
            val reader = sessionController.imageReader
            val camera = _selectedCamera.value
            if (device == null || session == null || reader == null || camera == null) {
                failAndClose(
                    CameraError(
                        CameraErrorCode.CAMERA_UNAVAILABLE,
                        "Camera is not ready to capture",
                    ),
                )
                return@post
            }
            if (!storagePermissionGranted) {
                val error = CameraError(
                    CameraErrorCode.PERMISSION_DENIED,
                    "Photo storage permission is required on this Android version",
                )
                _captureStatus.value = CaptureStatus.Failed(error)
                return@post
            }
            if (_captureStatus.value is CaptureStatus.Capturing ||
                _captureStatus.value is CaptureStatus.Saving
            ) return@post

            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    if ("Continuous picture" in camera.afModes) {
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                    }
                    set(
                        CaptureRequest.JPEG_ORIENTATION,
                        OrientationCalculator.jpegOrientation(
                            camera.sensorOrientation,
                            displayRotationDegrees,
                            camera.facing,
                        ),
                    )
                }.build()
                _captureStatus.value = CaptureStatus.Capturing
                transition(CameraEvent.CaptureStarted(camera.cameraId))
                session.capture(request, captureCallback(camera.cameraId), cameraHandler)
            } catch (error: CameraAccessException) {
                captureFailed("Camera rejected the JPEG capture request", error)
            } catch (error: IllegalStateException) {
                captureFailed("Capture session closed before the request", error)
            }
        }
    }

    private fun ensureDiscoveryAndOpen() {
        if (_cameras.value.isNotEmpty()) {
            cameraHandler.post { openSelectedCameraIfReady() }
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
                    openSelectedCameraIfReady()
                }
            }
        }
    }

    private fun preferredCamera(cameras: List<CameraCapabilities>): CameraCapabilities? =
        cameras.firstOrNull { it.facing == CameraFacing.BACK } ?: cameras.firstOrNull()

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

        try {
            val characteristics = cameraManager.getCameraCharacteristics(camera.cameraId)
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
            failAndClose(
                CameraError(
                    CameraErrorCode.DISCONNECTED,
                    "Camera $cameraId disconnected",
                ),
            )
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
        val surface = sessionController.previewSurface
        val capabilities = _selectedCamera.value
        if (surface == null || !surface.isValid || capabilities == null) {
            failAndClose(
                CameraError(CameraErrorCode.INVALID_SURFACE, "Preview surface is invalid"),
            )
            return
        }
        val jpegSize = capabilities.sizesFor(
            top.r2dblog.justcamera.camera.model.CameraOutputFormat.JPEG,
        ).maxByOrNull { it.area }
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

        try {
            val reader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                ImageFormat.JPEG,
                2,
            ).apply { setOnImageAvailableListener(::onImageAvailable, imageHandler) }
            sessionController.replaceImageReader(reader)

            transition(CameraEvent.Configure(cameraId))
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isCurrentCallback(cameraId, callbackGeneration) ||
                        device !== sessionController.cameraDevice || !running
                    ) {
                        sessionController.closeUnownedSession(session)
                        return
                    }
                    sessionController.adoptCaptureSession(session)
                    startPreview(device, session, surface, cameraId)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionController.closeUnownedSession(session)
                    if (!isCurrentCallback(cameraId, callbackGeneration)) return
                    failAndClose(
                        CameraError(
                            CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                            "Camera $cameraId preview session configuration failed",
                        ),
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(
                            OutputConfiguration(surface),
                            OutputConfiguration(reader.surface),
                        ),
                        Executor { runnable -> cameraHandler.post(runnable) },
                        sessionCallback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    listOf(surface, reader.surface),
                    sessionCallback,
                    cameraHandler,
                )
            }
        } catch (error: CameraAccessException) {
            failAndClose(accessError("Unable to configure camera $cameraId", error))
        } catch (error: IllegalArgumentException) {
            failAndClose(
                CameraError(
                    CameraErrorCode.INVALID_SURFACE,
                    "Preview or JPEG surface was rejected",
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
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if ("Continuous picture" in _selectedCamera.value?.afModes.orEmpty()) {
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                }
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
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
                CameraError(
                    CameraErrorCode.INVALID_SURFACE,
                    "Preview request was rejected",
                    error,
                ),
            )
        }
    }

    private fun captureCallback(cameraId: String) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            transition(CameraEvent.PreviewStarted(cameraId))
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            captureFailed(
                "JPEG capture failed (reason=${failure.reason}, frame=${failure.frameNumber})",
                null,
            )
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (error: IllegalStateException) {
            captureFailed("JPEG ImageReader queue is unavailable", error)
            return
        } ?: return

        val bytes = try {
            val buffer = image.planes.first().buffer
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            image.close()
        }
        _captureStatus.value = CaptureStatus.Saving
        val displayName = "JC_${FILE_NAME_FORMAT.format(Date())}.jpg"
        activeSaveJob = ioScope.launch {
            val result = mediaSaver.save(bytes, displayName)
            _captureStatus.value = result.fold(
                onSuccess = { CaptureStatus.Saved(it.displayName) },
                onFailure = {
                    CaptureStatus.Failed(
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

    private fun closeResources(emitClosed: Boolean) {
        checkCameraThread()
        cancelScheduledRetry(resetAttempts = true)
        cameraCallbackGeneration++
        sessionController.closeAll()
        _previewSize.value = null
        if (emitClosed && _state.value != CameraState.Closed) transition(CameraEvent.Close)
    }

    private fun transition(event: CameraEvent) {
        val current = _state.value
        val next = CameraStateReducer.reduce(current, event)
        if (next != current) _state.value = next
    }

    private fun captureFailed(message: String, cause: Throwable?) {
        val error = CameraError(CameraErrorCode.CAPTURE_FAILED, message, cause)
        _captureStatus.value = CaptureStatus.Failed(error)
        val id = _selectedCamera.value?.cameraId
        if (id != null) transition(CameraEvent.PreviewStarted(id))
        JcLog.error(LogCategory.CAPTURE, message, cause)
    }

    private fun failAndClose(error: CameraError) {
        checkCameraThread()
        cancelScheduledRetry(resetAttempts = false)
        cameraCallbackGeneration++
        sessionController.closeAll()
        _previewSize.value = null
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
        val FILE_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
