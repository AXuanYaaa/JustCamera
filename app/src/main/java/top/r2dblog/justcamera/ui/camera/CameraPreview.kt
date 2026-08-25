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
import top.r2dblog.justcamera.camera.session.PreviewPoint
import top.r2dblog.justcamera.camera.session.PreviewTransformCalculator
import top.r2dblog.justcamera.camera.session.PreviewViewport

@Composable
fun CameraPreview(
    cameraController: CameraController,
    previewSize: ImageSize?,
    sensorOrientation: Int?,
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
                            sensorOrientation = sensorOrientation,
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
                configureTransform(view, previewSize, sensorOrientation, cameraFacing)
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
    sensorOrientation: Int?,
    facing: CameraFacing?,
) {
    if (view.width == 0 || view.height == 0 || buffer == null ||
        sensorOrientation == null || facing == null
    ) return
    val transform = PreviewTransformCalculator.calculate(
        geometry = PreviewGeometry(
            bufferSize = buffer,
            sensorOrientation = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees(
                view.display?.rotation ?: Surface.ROTATION_0,
            ),
            cameraFacing = facing,
        ),
        viewport = PreviewViewport(view.width, view.height),
    )

    // TextureView first stretches its producer buffer to view bounds. Map those implicit view
    // coordinates back onto the verified, uniform buffer-to-viewport transform using 3 points.
    val source = floatArrayOf(
        0f, 0f,
        view.width.toFloat(), 0f,
        0f, view.height.toFloat(),
    )
    val topLeft = transform.mapBufferToViewport(PreviewPoint(0f, 0f))
    val topRight = transform.mapBufferToViewport(PreviewPoint(buffer.width.toFloat(), 0f))
    val bottomLeft = transform.mapBufferToViewport(PreviewPoint(0f, buffer.height.toFloat()))
    val destination = floatArrayOf(
        topLeft.x, topLeft.y,
        topRight.x, topRight.y,
        bottomLeft.x, bottomLeft.y,
    )
    view.setTransform(Matrix().apply { setPolyToPoly(source, 0, destination, 0, 3) })
}
