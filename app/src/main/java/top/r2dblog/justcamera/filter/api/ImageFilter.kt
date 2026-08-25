package top.r2dblog.justcamera.filter.api

import top.r2dblog.justcamera.filter.model.FilterDescriptor
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.imaging.frame.ImageFrame

interface ImageFilter {
    val descriptor: FilterDescriptor

    suspend fun process(input: ImageFrame, parameters: FilterParameters): ImageFrame
}
