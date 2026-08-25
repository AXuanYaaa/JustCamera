package top.r2dblog.justcamera.camera.session

import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize
import kotlin.math.abs

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
        (sensorOrientation + displayRotationDegrees) % 360
    } else {
        (sensorOrientation - displayRotationDegrees + 360) % 360
    }

    fun relativePreviewRotation(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        facing: CameraFacing,
    ): Int = jpegOrientation(sensorOrientation, displayRotationDegrees, facing)
}
