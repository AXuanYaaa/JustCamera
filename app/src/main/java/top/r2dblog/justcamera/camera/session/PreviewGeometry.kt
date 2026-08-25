package top.r2dblog.justcamera.camera.session

import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.model.SensorRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class PreviewBufferSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Preview buffer dimensions must be positive" }
    }

    val area: Long get() = width.toLong() * height
    fun toImageSize(): ImageSize = ImageSize(width, height)
    override fun toString(): String = "${width}x$height"
}

data class PreviewViewportSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Preview viewport dimensions must be positive" }
    }

    override fun toString(): String = "${width}x$height"
}

data class PreviewRotation(val degrees: Int) {
    init {
        require(degrees in 0..359 && degrees % 90 == 0) {
            "Preview rotation must be a normalized multiple of 90 degrees"
        }
    }

    val swapsDimensions: Boolean get() = degrees % 180 != 0

    companion object {
        fun fromDegrees(degrees: Int): PreviewRotation =
            PreviewRotation((degrees % 360 + 360) % 360)
    }
}

fun ImageSize.toPreviewBufferSize(): PreviewBufferSize = PreviewBufferSize(width, height)

object PreviewSizeSelector {
    private const val MAX_PREVIEW_LONG_EDGE = 1920
    private const val MAX_PREVIEW_SHORT_EDGE = 1080
    private const val DESIRED_ASPECT_RATIO = 16.0 / 9.0
    private const val ASPECT_RATIO_TOLERANCE = 0.02

    /**
     * Selects a stable camera stream independently from the window aspect ratio. A 20:9 screen is
     * a crop destination, not a reason to ask the HAL for an unusual 20:9 producer stream.
     */
    fun select(choices: List<ImageSize>): ImageSize? {
        if (choices.isEmpty()) return null
        val bounded = choices.filter {
            max(it.width, it.height) <= MAX_PREVIEW_LONG_EDGE &&
                min(it.width, it.height) <= MAX_PREVIEW_SHORT_EDGE
        }
        if (bounded.isEmpty()) return choices.minByOrNull { it.area }

        val desiredAspect = bounded.filter {
            abs(landscapeAspect(it) - DESIRED_ASPECT_RATIO) <= ASPECT_RATIO_TOLERANCE
        }
        return desiredAspect.ifEmpty { bounded }.maxByOrNull { it.area }
    }

    private fun landscapeAspect(size: ImageSize): Double =
        max(size.width, size.height).toDouble() / min(size.width, size.height)
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

    /**
     * Android Camera2 relative-rotation formula. Display rotation is the counter-clockwise value
     * reported by Display#getRotation from the user's point of view. The result is the sensor
     * output rotation relative to that display; front output has the opposite handedness.
     */
    fun relativePreviewRotationDegrees(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        facing: CameraFacing,
    ): Int {
        val sign = if (facing == CameraFacing.FRONT) 1 else -1
        return normalizeDegrees(sensorOrientation - displayRotationDegrees * sign)
    }

    fun relativePreviewRotation(
        sensorOrientation: PreviewRotation,
        displayRotation: PreviewRotation,
        facing: CameraFacing,
    ): PreviewRotation = PreviewRotation.fromDegrees(
        relativePreviewRotationDegrees(
            sensorOrientation.degrees,
            displayRotation.degrees,
            facing,
        ),
    )

    private fun normalizeDegrees(degrees: Int): Int = (degrees % 360 + 360) % 360
}

/** The producer owns front mirroring; the application transform must not mirror it again. */
enum class PreviewMirrorOwner { OUTPUT_CONFIGURATION_AUTO, APPLICATION, NONE }

data class PreviewGeometry(
    val bufferSize: PreviewBufferSize,
    val sensorOrientation: PreviewRotation,
    val displayRotation: PreviewRotation,
    val cameraFacing: CameraFacing,
    val mirrorOwner: PreviewMirrorOwner = PreviewMirrorOwner.OUTPUT_CONFIGURATION_AUTO,
) {
    val relativeRotation: PreviewRotation = OrientationCalculator.relativePreviewRotation(
        sensorOrientation,
        displayRotation,
        cameraFacing,
    )

    val isFrontMirrored: Boolean = cameraFacing == CameraFacing.FRONT &&
        mirrorOwner != PreviewMirrorOwner.NONE
    val producerMirrorsFront: Boolean = cameraFacing == CameraFacing.FRONT &&
        mirrorOwner == PreviewMirrorOwner.OUTPUT_CONFIGURATION_AUTO
    val applicationMirrorsFront: Boolean = cameraFacing == CameraFacing.FRONT &&
        mirrorOwner == PreviewMirrorOwner.APPLICATION
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
 * A project-owned affine matrix with Android Matrix value ordering. Matrices use column vectors;
 * `left * right` maps through [right] first and then [left].
 */
data class PreviewMatrix(
    val scaleX: Float,
    val skewX: Float,
    val translateX: Float,
    val skewY: Float,
    val scaleY: Float,
    val translateY: Float,
) {
    val values: List<Float>
        get() = listOf(
            scaleX, skewX, translateX,
            skewY, scaleY, translateY,
            0f, 0f, 1f,
        )

    fun map(point: PreviewPoint): PreviewPoint = PreviewPoint(
        x = scaleX * point.x + skewX * point.y + translateX,
        y = skewY * point.x + scaleY * point.y + translateY,
    )

    operator fun times(other: PreviewMatrix): PreviewMatrix = PreviewMatrix(
        scaleX = scaleX * other.scaleX + skewX * other.skewY,
        skewX = scaleX * other.skewX + skewX * other.scaleY,
        translateX = scaleX * other.translateX + skewX * other.translateY + translateX,
        skewY = skewY * other.scaleX + scaleY * other.skewY,
        scaleY = skewY * other.skewX + scaleY * other.scaleY,
        translateY = skewY * other.translateX + scaleY * other.translateY + translateY,
    )

    fun inverse(): PreviewMatrix {
        val determinant = scaleX * scaleY - skewX * skewY
        require(abs(determinant) > 1e-8f) { "Preview matrix is not invertible" }
        return PreviewMatrix(
            scaleX = scaleY / determinant,
            skewX = -skewX / determinant,
            translateX = (skewX * translateY - scaleY * translateX) / determinant,
            skewY = -skewY / determinant,
            scaleY = scaleX / determinant,
            translateY = (skewY * translateX - scaleX * translateY) / determinant,
        )
    }

    companion object {
        fun translation(x: Float, y: Float) = PreviewMatrix(1f, 0f, x, 0f, 1f, y)
        fun scale(x: Float, y: Float) = PreviewMatrix(x, 0f, 0f, 0f, y, 0f)
        fun rotationClockwise(degrees: Float): PreviewMatrix {
            val radians = Math.toRadians(degrees.toDouble())
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            return PreviewMatrix(cosine, -sine, 0f, sine, cosine, 0f)
        }
    }
}

/**
 * Complete producer-buffer to viewport geometry plus the single matrix supplied to TextureView.
 *
 * [bufferToViewportMatrix] is the geometry truth. [textureViewIntrinsicMatrix] models the
 * sensor-orientation/mirror transform and full-view stretch already present before setTransform.
 * [textureViewMatrix] is derived as final * inverse(intrinsic), so the combined displayed mapping
 * is exactly uniform even though TextureView's default intermediate mapping is not.
 */
data class PreviewTransform(
    val geometry: PreviewGeometry,
    val viewportSize: PreviewViewportSize,
    val policy: PreviewScalePolicy,
    val uniformScale: Float,
    val effectiveBufferSize: PreviewBufferSize,
    val bufferToViewportMatrix: PreviewMatrix,
    val textureViewIntrinsicMatrix: PreviewMatrix,
    val textureViewMatrix: PreviewMatrix,
) {
    val transformedBounds: PreviewBounds by lazy {
        val corners = listOf(
            PreviewPoint(0f, 0f),
            PreviewPoint(geometry.bufferSize.width.toFloat(), 0f),
            PreviewPoint(0f, geometry.bufferSize.height.toFloat()),
            PreviewPoint(
                geometry.bufferSize.width.toFloat(),
                geometry.bufferSize.height.toFloat(),
            ),
        ).map(bufferToViewportMatrix::map)
        PreviewBounds(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }

    fun mapBufferToViewport(point: PreviewPoint): PreviewPoint =
        bufferToViewportMatrix.map(point)

    fun mapThroughTextureView(point: PreviewPoint): PreviewPoint =
        textureViewMatrix.map(textureViewIntrinsicMatrix.map(point))

    fun mapViewportToBuffer(point: PreviewPoint): PreviewPoint {
        val mapped = bufferToViewportMatrix.inverse().map(point)
        return PreviewPoint(
            mapped.x.coerceIn(0f, geometry.bufferSize.width.toFloat()),
            mapped.y.coerceIn(0f, geometry.bufferSize.height.toFloat()),
        )
    }

    fun mapNormalizedViewportToBuffer(normalizedX: Float, normalizedY: Float): PreviewPoint {
        val point = mapViewportToBuffer(
            PreviewPoint(
                normalizedX.coerceIn(0f, 1f) * viewportSize.width,
                normalizedY.coerceIn(0f, 1f) * viewportSize.height,
            ),
        )
        return PreviewPoint(
            point.x / geometry.bufferSize.width,
            point.y / geometry.bufferSize.height,
        )
    }
}

object PreviewTransformCalculator {
    fun calculate(
        geometry: PreviewGeometry,
        viewportSize: PreviewViewportSize,
        policy: PreviewScalePolicy = PreviewScalePolicy.CENTER_CROP,
    ): PreviewTransform {
        val effectiveBufferSize = if (geometry.relativeRotation.swapsDimensions) {
            PreviewBufferSize(geometry.bufferSize.height, geometry.bufferSize.width)
        } else {
            geometry.bufferSize
        }
        val widthScale = viewportSize.width.toFloat() / effectiveBufferSize.width
        val heightScale = viewportSize.height.toFloat() / effectiveBufferSize.height
        val uniformScale = when (policy) {
            PreviewScalePolicy.CENTER_CROP -> max(widthScale, heightScale)
            PreviewScalePolicy.FIT_CENTER -> min(widthScale, heightScale)
        }

        val sensorClockwise = when (geometry.cameraFacing) {
            CameraFacing.FRONT -> -geometry.sensorOrientation.degrees.toFloat()
            else -> geometry.sensorOrientation.degrees.toFloat()
        }
        val relativeClockwise = when (geometry.cameraFacing) {
            CameraFacing.FRONT -> -geometry.relativeRotation.degrees.toFloat()
            else -> geometry.relativeRotation.degrees.toFloat()
        }
        val finalMirror = if (geometry.isFrontMirrored) -1f else 1f
        val producerMirror = if (geometry.producerMirrorsFront) -1f else 1f
        val viewportCenterX = viewportSize.width / 2f
        val viewportCenterY = viewportSize.height / 2f
        val bufferCenterX = geometry.bufferSize.width / 2f
        val bufferCenterY = geometry.bufferSize.height / 2f

        val finalMatrix = PreviewMatrix.translation(viewportCenterX, viewportCenterY) *
            PreviewMatrix.scale(finalMirror * uniformScale, uniformScale) *
            PreviewMatrix.rotationClockwise(relativeClockwise) *
            PreviewMatrix.translation(-bufferCenterX, -bufferCenterY)

        val sensorOrientedSize = if (geometry.sensorOrientation.swapsDimensions) {
            PreviewBufferSize(geometry.bufferSize.height, geometry.bufferSize.width)
        } else {
            geometry.bufferSize
        }
        val intrinsicMatrix = PreviewMatrix.translation(viewportCenterX, viewportCenterY) *
            PreviewMatrix.scale(producerMirror, 1f) *
            PreviewMatrix.scale(
                viewportSize.width.toFloat() / sensorOrientedSize.width,
                viewportSize.height.toFloat() / sensorOrientedSize.height,
            ) *
            PreviewMatrix.rotationClockwise(sensorClockwise) *
            PreviewMatrix.translation(-bufferCenterX, -bufferCenterY)
        val textureViewMatrix = finalMatrix * intrinsicMatrix.inverse()

        return PreviewTransform(
            geometry = geometry,
            viewportSize = viewportSize,
            policy = policy,
            uniformScale = uniformScale,
            effectiveBufferSize = effectiveBufferSize,
            bufferToViewportMatrix = finalMatrix,
            textureViewIntrinsicMatrix = intrinsicMatrix,
            textureViewMatrix = textureViewMatrix,
        )
    }
}

data class PreviewTransformReport(
    val bufferSize: PreviewBufferSize,
    val viewportSize: PreviewViewportSize,
    val windowSize: PreviewViewportSize,
    val screenOrientation: String,
    val displayRotation: PreviewRotation,
    val relativeRotation: PreviewRotation,
    val surfaceTextureIdentity: Int,
    val intrinsicMatrixValues: List<Float>,
    val textureViewMatrixValues: List<Float>,
    val finalMatrixValues: List<Float>,
)

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
