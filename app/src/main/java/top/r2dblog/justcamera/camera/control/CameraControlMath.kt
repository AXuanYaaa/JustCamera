package top.r2dblog.justcamera.camera.control

import top.r2dblog.justcamera.camera.model.SensorRect
import top.r2dblog.justcamera.camera.model.ValueRange
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.RationalValue
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object ShutterSpeedFormatter {
    private const val NANOS_PER_SECOND = 1_000_000_000L

    fun fractionToNanos(numerator: Long, denominator: Long): Long {
        require(numerator > 0 && denominator > 0)
        return (numerator.toDouble() * NANOS_PER_SECOND / denominator).roundToLong()
    }

    fun secondsToNanos(seconds: Double): Long {
        require(seconds > 0.0 && seconds.isFinite())
        return (seconds * NANOS_PER_SECOND).roundToLong()
    }

    fun format(nanoseconds: Long): String {
        require(nanoseconds > 0)
        if (nanoseconds >= NANOS_PER_SECOND) {
            val seconds = nanoseconds.toDouble() / NANOS_PER_SECOND
            return if (seconds == seconds.toLong().toDouble()) {
                "${seconds.toLong()}s"
            } else {
                String.format(Locale.US, "%.2fs", seconds).trimTrailingZeros()
            }
        }
        val denominator = (NANOS_PER_SECOND.toDouble() / nanoseconds).roundToInt().coerceAtLeast(1)
        return "1/$denominator"
    }

    fun fromSlider(position: Float, range: ValueRange<Long>): Long {
        if (range.lower == range.upper) return range.lower
        val clamped = position.coerceIn(0f, 1f).toDouble()
        return exp(ln(range.lower.toDouble()) +
            (ln(range.upper.toDouble()) - ln(range.lower.toDouble())) * clamped).roundToLong()
    }

    fun toSlider(nanoseconds: Long, range: ValueRange<Long>): Float {
        if (range.lower == range.upper) return 0f
        val clamped = nanoseconds.coerceIn(range.lower, range.upper).toDouble()
        return ((ln(clamped) - ln(range.lower.toDouble())) /
            (ln(range.upper.toDouble()) - ln(range.lower.toDouble()))).toFloat()
    }

    private fun String.trimTrailingZeros(): String = replace(Regex("0+s$"), "s")
        .replace(".s", "s")
}

object ExposureCompensation {
    fun evForSteps(steps: Int, step: RationalValue): Double = steps * step.value

    fun label(steps: Int, step: RationalValue): String =
        String.format(Locale.US, "%+.2f EV", evForSteps(steps, step))
}

object ZoomCropCalculator {
    fun crop(activeArray: SensorRect, zoomRatio: Float): SensorRect {
        val zoom = zoomRatio.coerceAtLeast(1f)
        val width = (activeArray.width / zoom).roundToInt().coerceAtLeast(1)
        val height = (activeArray.height / zoom).roundToInt().coerceAtLeast(1)
        val left = activeArray.left + (activeArray.width - width) / 2
        val top = activeArray.top + (activeArray.height - height) / 2
        return SensorRect(left, top, left + width, top + height)
    }

    fun ratio(activeArray: SensorRect, crop: SensorRect): Float =
        activeArray.width.toFloat() / crop.width.coerceAtLeast(1)
}

object MeteringRegionMapper {
    fun map(
        normalizedX: Float,
        normalizedY: Float,
        cropRegion: SensorRect,
        relativeRotationDegrees: Int,
        mirrorHorizontally: Boolean,
        regionFraction: Float = 0.12f,
    ): SensorRect {
        var x = normalizedX.coerceIn(0f, 1f)
        val y = normalizedY.coerceIn(0f, 1f)
        if (mirrorHorizontally) x = 1f - x
        val (sensorX, sensorY) = when ((relativeRotationDegrees % 360 + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        val regionWidth = (cropRegion.width * regionFraction.coerceIn(0.02f, 1f))
            .roundToInt().coerceAtLeast(1)
        val regionHeight = (cropRegion.height * regionFraction.coerceIn(0.02f, 1f))
            .roundToInt().coerceAtLeast(1)
        val centerX = cropRegion.left + (cropRegion.width * sensorX).roundToInt()
        val centerY = cropRegion.top + (cropRegion.height * sensorY).roundToInt()
        val left = (centerX - regionWidth / 2)
            .coerceIn(cropRegion.left, cropRegion.right - regionWidth)
        val top = (centerY - regionHeight / 2)
            .coerceIn(cropRegion.top, cropRegion.bottom - regionHeight)
        return SensorRect(left, top, left + regionWidth, top + regionHeight)
    }
}

object CaptureModeResolver {
    fun resolve(requested: CaptureMode, rawAvailable: Boolean): CaptureMode =
        if (rawAvailable) requested else CaptureMode.JPEG_ONLY
}
