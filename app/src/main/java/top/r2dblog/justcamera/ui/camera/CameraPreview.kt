package top.r2dblog.justcamera.ui.camera

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.camera.model.ImageSize
import kotlin.math.max

@Composable
fun CameraPreview(
    cameraController: CameraController,
    previewSize: ImageSize?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
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
                        previewSize?.let { configureTransform(this@apply, it) }
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
                previewSize?.let { configureTransform(view, it) }
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

private fun configureTransform(view: TextureView, buffer: ImageSize) {
    if (view.width == 0 || view.height == 0) return
    val rotation = view.display?.rotation ?: Surface.ROTATION_0
    val viewRect = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
    val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val bufferWidth = if (rotated) buffer.height.toFloat() else buffer.width.toFloat()
    val bufferHeight = if (rotated) buffer.width.toFloat() else buffer.height.toFloat()
    val bufferRect = RectF(0f, 0f, bufferWidth, bufferHeight)
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

    val matrix = Matrix().apply {
        setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            view.height.toFloat() / bufferHeight,
            view.width.toFloat() / bufferWidth,
        )
        postScale(scale, scale, centerX, centerY)
        when (rotation) {
            Surface.ROTATION_90 -> postRotate(-90f, centerX, centerY)
            Surface.ROTATION_180 -> postRotate(180f, centerX, centerY)
            Surface.ROTATION_270 -> postRotate(90f, centerX, centerY)
        }
    }
    view.setTransform(matrix)
}
