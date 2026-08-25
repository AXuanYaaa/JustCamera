package top.r2dblog.justcamera.filter.builtin

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.model.FilterCategory
import top.r2dblog.justcamera.filter.model.FilterDescriptor
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterImplementationType
import top.r2dblog.justcamera.filter.model.FilterParameterSpec
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import kotlin.math.pow
import kotlin.math.sqrt

private val BOTH_MODES = FilterExecutionMode.entries.toSet()

object AdjustmentParameters {
    val exposure = FilterParameterSpec.FloatParameter("exposure", "Exposure", 0f, -5f, 5f, 0.1f)
    val contrast = FilterParameterSpec.FloatParameter("contrast", "Contrast", 1f, 0f, 2f, 0.05f)
    val saturation = FilterParameterSpec.FloatParameter("saturation", "Saturation", 1f, 0f, 2f, 0.05f)
    val temperature = FilterParameterSpec.FloatParameter(
        "temperature",
        "Temperature",
        0f,
        -1f,
        1f,
        0.05f,
    )
    val tint = FilterParameterSpec.FloatParameter("tint", "Tint", 0f, -1f, 1f, 0.05f)
    val highlights = FilterParameterSpec.FloatParameter("highlights", "Highlights", 0f, -1f, 1f, 0.05f)
    val shadows = FilterParameterSpec.FloatParameter("shadows", "Shadows", 0f, -1f, 1f, 0.05f)
    val fade = FilterParameterSpec.FloatParameter("fade", "Fade", 0f, 0f, 1f, 0.05f)
    val vignette = FilterParameterSpec.FloatParameter("vignette", "Vignette", 0f, 0f, 1f, 0.05f)
    val strength = FilterParameterSpec.FloatParameter("strength", "Strength", 1f, 0f, 1f, 0.05f)
}

abstract class CpuRgbFilter(
    final override val descriptor: FilterDescriptor,
) : ImageFilter {
    final override suspend fun process(
        input: RgbFloatFrame,
        parameters: FilterParameters,
        context: FilterExecutionContext,
    ): RgbFloatFrame {
        val normalized = parameters.validateAndClamp(descriptor).parameters
        val strength = normalized.float("strength", 1f)
        val prepared = prepare(normalized)
        val output = input.copyPixels()
        val channels = input.channelCount
        var offset = 0
        var pixel = 0
        while (pixel < input.pixelCount) {
            if (pixel % input.width == 0) currentCoroutineContext().ensureActive()
            val originalRed = output[offset]
            val originalGreen = output[offset + 1]
            val originalBlue = output[offset + 2]
            transform(output, offset, prepared, pixel, input)
            if (strength < 1f) {
                output[offset] = originalRed + (output[offset] - originalRed) * strength
                output[offset + 1] = originalGreen + (output[offset + 1] - originalGreen) * strength
                output[offset + 2] = originalBlue + (output[offset + 2] - originalBlue) * strength
            }
            offset += channels
            pixel++
        }
        return input.withOwnedPixels(output)
    }

    protected abstract fun transform(
        pixels: FloatArray,
        offset: Int,
        prepared: FloatArray,
        pixelIndex: Int,
        frame: RgbFloatFrame,
    )

    protected abstract fun prepare(parameters: FilterParameters): FloatArray

    protected fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
}

class ExposureFilter : CpuRgbFilter(adjustmentDescriptor("builtin.exposure", "Exposure", AdjustmentParameters.exposure)) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(
        2.0.pow(parameters.float("exposure").toDouble()).toFloat(),
    )

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val multiplier = prepared[0]
        for (channel in 0..2) pixels[offset + channel] = clamp(pixels[offset + channel] * multiplier)
    }
}

class ContrastFilter : CpuRgbFilter(adjustmentDescriptor("builtin.contrast", "Contrast", AdjustmentParameters.contrast)) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(parameters.float("contrast", 1f))

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val contrast = prepared[0]
        for (channel in 0..2) {
            pixels[offset + channel] = clamp((pixels[offset + channel] - CONTRAST_PIVOT) * contrast + CONTRAST_PIVOT)
        }
    }

    private companion object { const val CONTRAST_PIVOT = 0.18f }
}

class SaturationFilter : CpuRgbFilter(adjustmentDescriptor("builtin.saturation", "Saturation", AdjustmentParameters.saturation)) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(parameters.float("saturation", 1f))

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val saturation = prepared[0]
        val luma = pixels[offset] * 0.2126f + pixels[offset + 1] * 0.7152f +
            pixels[offset + 2] * 0.0722f
        for (channel in 0..2) {
            pixels[offset + channel] = clamp(luma + (pixels[offset + channel] - luma) * saturation)
        }
    }
}

class TemperatureTintFilter : CpuRgbFilter(
    adjustmentDescriptor(
        "builtin.temperature_tint",
        "Temperature / Tint",
        AdjustmentParameters.temperature,
        AdjustmentParameters.tint,
    ),
) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(
        parameters.float("temperature"),
        parameters.float("tint"),
    )

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val temperature = prepared[0]
        val tint = prepared[1]
        val redScale = 1f + 0.10f * temperature + 0.05f * tint
        val greenScale = 1f - 0.10f * tint
        val blueScale = 1f - 0.10f * temperature + 0.05f * tint
        pixels[offset] = clamp(pixels[offset] * redScale)
        pixels[offset + 1] = clamp(pixels[offset + 1] * greenScale)
        pixels[offset + 2] = clamp(pixels[offset + 2] * blueScale)
    }
}

class HighlightsShadowsFilter : CpuRgbFilter(
    adjustmentDescriptor(
        "builtin.highlights_shadows",
        "Highlights / Shadows",
        AdjustmentParameters.highlights,
        AdjustmentParameters.shadows,
    ),
) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(
        parameters.float("highlights"),
        parameters.float("shadows"),
    )

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val highlights = prepared[0]
        val shadows = prepared[1]
        val luma = pixels[offset] * 0.2126f + pixels[offset + 1] * 0.7152f +
            pixels[offset + 2] * 0.0722f
        val highlightWeight = luma * luma
        val shadowWeight = (1f - luma) * (1f - luma)
        for (channel in 0..2) {
            var value = pixels[offset + channel]
            value += if (shadows >= 0f) {
                shadows * shadowWeight * (1f - value) * 0.25f
            } else {
                shadows * shadowWeight * value * 0.25f
            }
            value += if (highlights >= 0f) {
                highlights * highlightWeight * (1f - value) * 0.25f
            } else {
                highlights * highlightWeight * value * 0.25f
            }
            pixels[offset + channel] = clamp(value)
        }
    }
}

class FadeFilter : CpuRgbFilter(adjustmentDescriptor("builtin.fade", "Fade", AdjustmentParameters.fade)) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(parameters.float("fade"))

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val fade = prepared[0]
        for (channel in 0..2) {
            pixels[offset + channel] = clamp(pixels[offset + channel] * (1f - 0.25f * fade) + 0.08f * fade)
        }
    }
}

class VignetteFilter : CpuRgbFilter(adjustmentDescriptor("builtin.vignette", "Vignette", AdjustmentParameters.vignette)) {
    override fun prepare(parameters: FilterParameters) = floatArrayOf(parameters.float("vignette"))

    override fun transform(pixels: FloatArray, offset: Int, prepared: FloatArray, pixelIndex: Int, frame: RgbFloatFrame) {
        val amount = prepared[0]
        if (amount == 0f) return
        val x = (pixelIndex % frame.width + 0.5f) / frame.width
        val y = (pixelIndex / frame.width + 0.5f) / frame.height
        val radius = sqrt((x - 0.5f) * (x - 0.5f) + (y - 0.5f) * (y - 0.5f))
        val t = ((radius - 0.25f) / 0.46f).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        val multiplier = 1f - amount * smooth * 0.65f
        for (channel in 0..2) pixels[offset + channel] = clamp(pixels[offset + channel] * multiplier)
    }
}

private fun adjustmentDescriptor(
    id: String,
    name: String,
    vararg parameters: FilterParameterSpec,
) = FilterDescriptor(
    id = id,
    displayName = name,
    category = FilterCategory.ADJUSTMENT,
    implementationType = FilterImplementationType.KOTLIN_CPU_REFERENCE,
    supportedModes = BOTH_MODES,
    parameterSpecs = parameters.toList() + AdjustmentParameters.strength,
)
