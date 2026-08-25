package top.r2dblog.justcamera.ui.camera

import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.session.PreviewGeometry
import top.r2dblog.justcamera.camera.session.PreviewRotation
import top.r2dblog.justcamera.camera.session.PreviewTransformCalculator
import top.r2dblog.justcamera.camera.session.PreviewTransformReport
import top.r2dblog.justcamera.camera.session.PreviewViewportSize
import top.r2dblog.justcamera.camera.session.toPreviewBufferSize

@Composable
fun CameraPreview(
    cameraController: CameraController,
    previewSize: ImageSize?,
    cameraFacing: CameraFacing?,
    sensorOrientation: Int?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.pointerInput(cameraController) {
            detectTapGestures { offset ->
                if (size.width > 0 && size.height > 0) {
                    cameraController.focusAt(
                        offset.x / size.width,
                        offset.y / size.height,
                    )
                }
            }
        },
        factory = { context ->
            PreviewTextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        val rotation = displayRotationDegrees(
                            display?.rotation ?: Surface.ROTATION_0,
                        )
                        cameraController.attachPreview(texture, width, height, rotation)
                        onPreviewGeometryChanged?.invoke()
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        onPreviewGeometryChanged?.invoke()
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        cameraController.detachPreview(texture)
                        lastTransformReport = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.onPreviewGeometryChanged = {
                if (view.isAvailable) {
                    cameraController.updatePreviewGeometry(
                        view.width,
                        view.height,
                        displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0),
                    )
                    configureTransform(
                        view = view,
                        controller = cameraController,
                        buffer = previewSize,
                        facing = cameraFacing,
                        sensorOrientation = sensorOrientation,
                    )
                }
            }
            if (view.isAvailable) {
                view.onPreviewGeometryChanged?.invoke()
            }
        },
    )
}

private class PreviewTextureView(context: Context) : TextureView(context) {
    var lastTransformReport: PreviewTransformReport? = null
    var onPreviewGeometryChanged: (() -> Unit)? = null
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDisplayRotation: Int? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != display?.displayId) return
            val rotation = display?.rotation ?: return
            if (rotation != lastDisplayRotation) {
                lastDisplayRotation = rotation
                onPreviewGeometryChanged?.invoke()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastDisplayRotation = display?.rotation
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onDetachedFromWindow() {
        displayManager.unregisterDisplayListener(displayListener)
        onPreviewGeometryChanged = null
        super.onDetachedFromWindow()
    }
}

private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun configureTransform(
    view: PreviewTextureView,
    controller: CameraController,
    buffer: ImageSize?,
    facing: CameraFacing?,
    sensorOrientation: Int?,
) {
    if (view.width == 0 || view.height == 0 || buffer == null || facing == null ||
        sensorOrientation == null
    ) {
        view.lastTransformReport = null
        return
    }
    val displayRotation = PreviewRotation.fromDegrees(
        displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0),
    )
    val viewport = PreviewViewportSize(view.width, view.height)
    val transform = PreviewTransformCalculator.calculate(
        geometry = PreviewGeometry(
            bufferSize = buffer.toPreviewBufferSize(),
            sensorOrientation = PreviewRotation.fromDegrees(sensorOrientation),
            displayRotation = displayRotation,
            cameraFacing = facing,
        ),
        viewportSize = viewport,
    )

    // This one matrix is final * inverse(TextureView intrinsic). It cancels the intermediate
    // producer stretch and leaves rotation + one uniform CENTER_CROP scale around the view center.
    view.setTransform(
        Matrix().apply { setValues(transform.textureViewMatrix.values.toFloatArray()) },
    )

    val rootWidth = view.rootView.width.takeIf { it > 0 } ?: view.width
    val rootHeight = view.rootView.height.takeIf { it > 0 } ?: view.height
    val report = PreviewTransformReport(
        bufferSize = buffer.toPreviewBufferSize(),
        viewportSize = viewport,
        windowSize = PreviewViewportSize(rootWidth, rootHeight),
        screenOrientation = when (view.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "undefined"
        },
        displayRotation = displayRotation,
        relativeRotation = transform.geometry.relativeRotation,
        surfaceTextureIdentity = System.identityHashCode(view.surfaceTexture),
        intrinsicMatrixValues = transform.textureViewIntrinsicMatrix.values,
        textureViewMatrixValues = transform.textureViewMatrix.values,
        finalMatrixValues = transform.bufferToViewportMatrix.values,
    )
    if (view.lastTransformReport != report) {
        view.lastTransformReport = report
        controller.reportPreviewTransform(report)
    }
}
