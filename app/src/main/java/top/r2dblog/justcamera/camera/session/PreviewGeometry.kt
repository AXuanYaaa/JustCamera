package top.r2dblog.justcamera.camera.session

import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.model.SensorRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PreviewSizeSelector {
    private const val MAX_PREVIEW_AREA = 1920L * 1080L

    fun select(choices: List<ImageSize>, viewWidth: Int, viewHeight: Int): ImageSize? {
        if (choices.isEmpty()) return null
        val targetRatio = maxOf(viewWidth, viewHeight).toDouble() /
            minOf(viewWidth, viewHeight).coerceAtLeast(1)
        val bounded = choices.filter { it.area <= MAX_PREVIEW_AREA }.ifEmpty { choices }
        return bounded.minWithOrNull(
            compareBy<ImageSize> {
                abs(maxOf(it.width, it.height).toDouble() /
                    minOf(it.width, it.height) - targetRatio)
            }.thenByDescending { it.area },
        )
    }
}

object OrientationCalculator {
    fun jpegOrientation(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        facing: CameraFacing,
    ): Int = if (facing == CameraFacing.FRONT) {
        normalizeDegrees(sensorOrientation + displayRotationDegrees)
    } else {
        normalizeDegrees(sensorOrientation - displayRotationDegrees)
    }

    fun relativePreviewRotation(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        facing: CameraFacing,
    ): Int = jpegOrientation(sensorOrientation, displayRotationDegrees, facing)

    private fun normalizeDegrees(degrees: Int): Int = (degrees % 360 + 360) % 360
}

/** Camera buffer facts needed by the UI without exposing CameraCharacteristics. */
data class PreviewGeometry(
    val bufferSize: ImageSize,
    val sensorOrientation: Int,
    val displayRotationDegrees: Int,
    val cameraFacing: CameraFacing,
) {
    init {
        require(sensorOrientation in setOf(0, 90, 180, 270))
        require(displayRotationDegrees in setOf(0, 90, 180, 270))
    }

    val relativeRotationDegrees: Int = OrientationCalculator.relativePreviewRotation(
        sensorOrientation = sensorOrientation,
        displayRotationDegrees = displayRotationDegrees,
        facing = cameraFacing,
    )
    val mirrorHorizontally: Boolean = cameraFacing == CameraFacing.FRONT
}

data class PreviewViewport(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

data class PreviewPoint(val x: Float, val y: Float)

data class PreviewBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

enum class PreviewScalePolicy { CENTER_CROP, FIT_CENTER }

/**
 * An affine mapping from camera-buffer pixels to viewport pixels.
 *
 * [scaleX] and [scaleY] deliberately expose the complete source-to-viewport scale. They are
 * always identical: the buffer is rotated, optionally mirrored for the front camera, uniformly
 * scaled, and centered. TextureView's implicit buffer-to-view stretch is compensated separately
 * by the Android adapter in CameraPreview.
 */
class PreviewTransform internal constructor(
    val geometry: PreviewGeometry,
    val viewport: PreviewViewport,
    val policy: PreviewScalePolicy,
    val scale: Float,
) {
    val scaleX: Float get() = scale
    val scaleY: Float get() = scale

    val rotatedBufferWidth: Float
        get() = if (geometry.relativeRotationDegrees % 180 == 0) {
            geometry.bufferSize.width.toFloat()
        } else {
            geometry.bufferSize.height.toFloat()
        }
    val rotatedBufferHeight: Float
        get() = if (geometry.relativeRotationDegrees % 180 == 0) {
            geometry.bufferSize.height.toFloat()
        } else {
            geometry.bufferSize.width.toFloat()
        }
    val transformedBounds: PreviewBounds
        get() {
            val width = rotatedBufferWidth * scale
            val height = rotatedBufferHeight * scale
            return PreviewBounds(
                left = (viewport.width - width) / 2f,
                top = (viewport.height - height) / 2f,
                right = (viewport.width + width) / 2f,
                bottom = (viewport.height + height) / 2f,
            )
        }

    fun mapBufferToViewport(point: PreviewPoint): PreviewPoint {
        val centeredX = point.x - geometry.bufferSize.width / 2f
        val centeredY = point.y - geometry.bufferSize.height / 2f
        var rotated = rotateClockwise(centeredX, centeredY, geometry.relativeRotationDegrees)
        if (geometry.mirrorHorizontally) rotated = PreviewPoint(-rotated.x, rotated.y)
        return PreviewPoint(
            x = viewport.width / 2f + rotated.x * scale,
            y = viewport.height / 2f + rotated.y * scale,
        )
    }

    fun mapViewportToBuffer(point: PreviewPoint): PreviewPoint {
        var displayX = (point.x - viewport.width / 2f) / scale
        val displayY = (point.y - viewport.height / 2f) / scale
        if (geometry.mirrorHorizontally) displayX = -displayX
        val source = rotateClockwise(displayX, displayY, -geometry.relativeRotationDegrees)
        return PreviewPoint(
            x = (source.x + geometry.bufferSize.width / 2f)
                .coerceIn(0f, geometry.bufferSize.width.toFloat()),
            y = (source.y + geometry.bufferSize.height / 2f)
                .coerceIn(0f, geometry.bufferSize.height.toFloat()),
        )
    }

    fun mapNormalizedViewportToBuffer(normalizedX: Float, normalizedY: Float): PreviewPoint {
        val point = mapViewportToBuffer(
            PreviewPoint(
                normalizedX.coerceIn(0f, 1f) * viewport.width,
                normalizedY.coerceIn(0f, 1f) * viewport.height,
            ),
        )
        return PreviewPoint(
            x = point.x / geometry.bufferSize.width,
            y = point.y / geometry.bufferSize.height,
        )
    }

    private fun rotateClockwise(x: Float, y: Float, degrees: Int): PreviewPoint =
        when ((degrees % 360 + 360) % 360) {
            90 -> PreviewPoint(-y, x)
            180 -> PreviewPoint(-x, -y)
            270 -> PreviewPoint(y, -x)
            else -> PreviewPoint(x, y)
        }
}

object PreviewTransformCalculator {
    fun calculate(
        geometry: PreviewGeometry,
        viewport: PreviewViewport,
        policy: PreviewScalePolicy = PreviewScalePolicy.CENTER_CROP,
    ): PreviewTransform {
        val rotated = geometry.relativeRotationDegrees % 180 != 0
        val rotatedWidth = if (rotated) geometry.bufferSize.height else geometry.bufferSize.width
        val rotatedHeight = if (rotated) geometry.bufferSize.width else geometry.bufferSize.height
        val widthScale = viewport.width.toFloat() / rotatedWidth
        val heightScale = viewport.height.toFloat() / rotatedHeight
        val scale = when (policy) {
            PreviewScalePolicy.CENTER_CROP -> max(widthScale, heightScale)
            PreviewScalePolicy.FIT_CENTER -> min(widthScale, heightScale)
        }
        return PreviewTransform(geometry, viewport, policy, scale)
    }
}

/** Center crop applied by Camera2 when the sensor crop and preview stream have different ratios. */
object PreviewMeteringCropCalculator {
    fun visibleSensorCrop(sensorCrop: SensorRect, previewBuffer: ImageSize): SensorRect {
        val sensorRatio = sensorCrop.width.toDouble() / sensorCrop.height
        val previewRatio = previewBuffer.width.toDouble() / previewBuffer.height
        return when {
            sensorRatio < previewRatio -> {
                val height = (sensorCrop.width / previewRatio).toInt().coerceAtLeast(1)
                val top = sensorCrop.top + (sensorCrop.height - height) / 2
                SensorRect(sensorCrop.left, top, sensorCrop.right, top + height)
            }
            sensorRatio > previewRatio -> {
                val width = (sensorCrop.height * previewRatio).toInt().coerceAtLeast(1)
                val left = sensorCrop.left + (sensorCrop.width - width) / 2
                SensorRect(left, sensorCrop.top, left + width, sensorCrop.bottom)
            }
            else -> sensorCrop
        }
    }
}
