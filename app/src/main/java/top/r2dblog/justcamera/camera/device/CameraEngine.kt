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
    private var previewSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

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
            val device = cameraDevice
            val session = captureSession
            val reader = imageReader
            val camera = _selectedCamera.value
            if (device == null || session == null || reader == null || camera == null) {
                fail(
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
                    fail(
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
        if (!running || !cameraPermissionGranted || cameraDevice != null ||
            _state.value is CameraState.Opening
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
                fail(
                    CameraError(
                        CameraErrorCode.UNSUPPORTED_CAPABILITY,
                        "Camera ${camera.cameraId} has no SurfaceTexture preview sizes",
                        recoverable = false,
                    ),
                )
                return
            }
            texture.setDefaultBufferSize(selectedSize.width, selectedSize.height)
            previewSurface?.release()
            previewSurface = Surface(texture)
            _previewSize.value = selectedSize
            transition(CameraEvent.Open(camera.cameraId))
            cameraManager.openCamera(camera.cameraId, deviceCallback(camera.cameraId), cameraHandler)
        } catch (error: SecurityException) {
            fail(CameraError(CameraErrorCode.PERMISSION_DENIED, "Camera permission denied", error))
        } catch (error: CameraAccessException) {
            fail(accessError("Unable to open camera ${camera.cameraId}", error))
        } catch (error: IllegalArgumentException) {
            fail(
                CameraError(
                    CameraErrorCode.CAMERA_UNAVAILABLE,
                    "Camera ${camera.cameraId} is no longer available",
                    error,
                ),
            )
        }
    }

    private fun deviceCallback(cameraId: String) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!running || surfaceTexture == null ||
                _selectedCamera.value?.cameraId != cameraId
            ) {
                camera.close()
                return
            }
            cameraDevice = camera
            transition(CameraEvent.DeviceOpened(cameraId))
            configureSession(camera, cameraId)
        }

        override fun onDisconnected(camera: CameraDevice) {
            closeResources(emitClosed = false)
            fail(
                CameraError(
                    CameraErrorCode.DISCONNECTED,
                    "Camera $cameraId disconnected",
                ),
            )
        }

        override fun onError(camera: CameraDevice, error: Int) {
            closeResources(emitClosed = false)
            fail(
                CameraError(
                    CameraErrorCode.CAMERA_UNAVAILABLE,
                    "Camera $cameraId error: ${deviceErrorName(error)}",
                ),
            )
        }
    }

    private fun configureSession(device: CameraDevice, cameraId: String) {
        val surface = previewSurface
        val capabilities = _selectedCamera.value
        if (surface == null || !surface.isValid || capabilities == null) {
            fail(CameraError(CameraErrorCode.INVALID_SURFACE, "Preview surface is invalid"))
            return
        }
        val jpegSize = capabilities.sizesFor(
            top.r2dblog.justcamera.camera.model.CameraOutputFormat.JPEG,
        ).maxByOrNull { it.area }
        if (jpegSize == null) {
            fail(
                CameraError(
                    CameraErrorCode.UNSUPPORTED_CAPABILITY,
                    "Camera $cameraId does not expose JPEG output",
                    recoverable = false,
                ),
            )
            return
        }

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            jpegSize.width,
            jpegSize.height,
            ImageFormat.JPEG,
            2,
        ).apply { setOnImageAvailableListener(::onImageAvailable, imageHandler) }

        try {
            transition(CameraEvent.Configure(cameraId))
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (device != cameraDevice || !running) {
                        session.close()
                        return
                    }
                    captureSession = session
                    startPreview(device, session, surface, cameraId)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail(
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
                            OutputConfiguration(imageReader!!.surface),
                        ),
                        Executor { runnable -> cameraHandler.post(runnable) },
                        sessionCallback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    listOf(surface, imageReader!!.surface),
                    sessionCallback,
                    cameraHandler,
                )
            }
        } catch (error: CameraAccessException) {
            fail(accessError("Unable to configure camera $cameraId", error))
        } catch (error: IllegalArgumentException) {
            fail(
                CameraError(
                    CameraErrorCode.INVALID_SURFACE,
                    "Preview or JPEG surface was rejected",
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
            transition(CameraEvent.PreviewStarted(cameraId))
            JcLog.info(LogCategory.CAMERA, "Preview started on camera $cameraId")
        } catch (error: CameraAccessException) {
            fail(accessError("Unable to start camera $cameraId preview", error))
        } catch (error: IllegalStateException) {
            fail(
                CameraError(
                    CameraErrorCode.SESSION_CONFIGURATION_FAILED,
                    "Preview session closed while starting",
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
        try {
            captureSession?.stopRepeating()
        } catch (error: CameraAccessException) {
            JcLog.warn(LogCategory.CAMERA, "Unable to stop repeating preview", error)
        } catch (error: IllegalStateException) {
            JcLog.debug(LogCategory.CAMERA) { "Session already closed: ${error.message}" }
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        _previewSize.value = null
        if (emitClosed) transition(CameraEvent.Close)
    }

    private fun transition(event: CameraEvent) {
        _state.value = CameraStateReducer.reduce(_state.value, event)
    }

    private fun captureFailed(message: String, cause: Throwable?) {
        val error = CameraError(CameraErrorCode.CAPTURE_FAILED, message, cause)
        _captureStatus.value = CaptureStatus.Failed(error)
        val id = _selectedCamera.value?.cameraId
        if (id != null) transition(CameraEvent.PreviewStarted(id))
        JcLog.error(LogCategory.CAPTURE, message, cause)
    }

    private fun fail(error: CameraError) {
        transition(CameraEvent.Failed(error))
        JcLog.error(LogCategory.CAMERA, error.message, error.cause)
    }

    private fun accessError(message: String, cause: CameraAccessException) = CameraError(
        CameraErrorCode.ACCESS_FAILURE,
        "$message (reason=${cause.reason})",
        cause,
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
