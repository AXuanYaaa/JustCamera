package top.r2dblog.justcamera.filter.lut

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.builtin.AdjustmentParameters
import top.r2dblog.justcamera.filter.model.FilterCategory
import top.r2dblog.justcamera.filter.model.FilterDescriptor
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterImplementationType
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.color.ColorTransfer
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

/** Applies and strength-blends a .cube LUT in encoded sRGB, then returns linear sRGB. */
class Lut3DFilter(
    id: String,
    displayName: String,
    private val lut: Lut3D,
) : ImageFilter {
    override val descriptor = FilterDescriptor(
        id = id,
        displayName = displayName,
        category = FilterCategory.LUT,
        implementationType = FilterImplementationType.KOTLIN_CPU_REFERENCE,
        supportedModes = FilterExecutionMode.entries.toSet(),
        parameterSpecs = listOf(AdjustmentParameters.strength),
    )

    override suspend fun process(
        input: RgbFloatFrame,
        parameters: FilterParameters,
        context: FilterExecutionContext,
    ): RgbFloatFrame {
        val strength = parameters.validateAndClamp(descriptor).parameters.float("strength", 1f)
        if (strength == 0f) return input.withOwnedPixels(input.copyPixels())
        val output = input.copyPixels()
        val lutOutput = FloatArray(3)
        val channels = input.channelCount
        var offset = 0
        var pixel = 0
        while (pixel < input.pixelCount) {
            if (pixel % input.width == 0) currentCoroutineContext().ensureActive()
            val red = ColorTransfer.linearToSrgb(output[offset])
            val green = ColorTransfer.linearToSrgb(output[offset + 1])
            val blue = ColorTransfer.linearToSrgb(output[offset + 2])
            lut.sampleInto(red, green, blue, lutOutput, 0)
            output[offset] = ColorTransfer.srgbToLinear(red + (lutOutput[0] - red) * strength)
            output[offset + 1] = ColorTransfer.srgbToLinear(
                green + (lutOutput[1] - green) * strength,
            )
            output[offset + 2] = ColorTransfer.srgbToLinear(blue + (lutOutput[2] - blue) * strength)
            offset += channels
            pixel++
        }
        return input.withOwnedPixels(output)
    }
}
