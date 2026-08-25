package top.r2dblog.justcamera.ui.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
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
import top.r2dblog.justcamera.camera.session.PreviewTransformCalculator
import top.r2dblog.justcamera.camera.session.PreviewViewport

@Composable
fun CameraPreview(
    cameraController: CameraController,
    previewSize: ImageSize?,
    cameraFacing: CameraFacing?,
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
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        cameraController.attachPreview(
                            texture,
                            width,
                            height,
                            displayRotationDegrees(display?.rotation ?: Surface.ROTATION_0),
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        cameraController.updatePreviewGeometry(
                            width,
                            height,
                            displayRotationDegrees(display?.rotation ?: Surface.ROTATION_0),
                        )
                        configureTransform(
                            view = this@apply,
                            buffer = previewSize,
                            facing = cameraFacing,
                        )
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        cameraController.detachPreview(texture)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            if (view.isAvailable) {
                cameraController.updatePreviewGeometry(
                    view.width,
                    view.height,
                    displayRotationDegrees(view.display?.rotation ?: Surface.ROTATION_0),
                )
                configureTransform(view, previewSize, cameraFacing)
            }
        },
    )
}

private fun displayRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun configureTransform(
    view: TextureView,
    buffer: ImageSize?,
    facing: CameraFacing?,
) {
    if (view.width == 0 || view.height == 0 || buffer == null || facing == null) return
    val transform = PreviewTransformCalculator.calculate(
        geometry = PreviewGeometry(
            bufferSize = buffer,
            cameraFacing = facing,
        ),
        viewport = PreviewViewport(view.width, view.height),
    )

    // Camera2/SurfaceTexture owns sensor/display orientation. TextureView first stretches that
    // oriented producer content to view bounds, so this axis-aligned adapter only compensates the
    // implicit stretch to match the verified uniform CENTER_CROP mapping. It never rotates.
    val bounds = transform.transformedBounds
    val implicitStretchCompensationX = bounds.width / view.width
    val implicitStretchCompensationY = bounds.height / view.height
    val adapterScaleX = if (transform.geometry.mirrorHorizontally) {
        -implicitStretchCompensationX
    } else {
        implicitStretchCompensationX
    }
    val adapterTranslationX = if (transform.geometry.mirrorHorizontally) {
        bounds.right
    } else {
        transform.translationX
    }
    view.setTransform(
        Matrix().apply {
            setValues(
                floatArrayOf(
                    adapterScaleX, 0f, adapterTranslationX,
                    0f, implicitStretchCompensationY, transform.translationY,
                    0f, 0f, 1f,
                ),
            )
        },
    )
}
