package top.r2dblog.justcamera.filter.api

import top.r2dblog.justcamera.filter.model.FilterDescriptor
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

data class FilterExecutionContext(
    val mode: FilterExecutionMode,
    val attributes: Map<String, FrameMetadataValue> = emptyMap(),
)

interface ImageFilter {
    val descriptor: FilterDescriptor

    suspend fun process(
        input: RgbFloatFrame,
        parameters: FilterParameters,
        context: FilterExecutionContext,
    ): RgbFloatFrame
}
