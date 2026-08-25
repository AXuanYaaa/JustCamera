package top.r2dblog.justcamera.camera.raw

import top.r2dblog.justcamera.camera.model.ImageSize

object RawCapabilitySelector {
    fun selectLargest(hasRawCapability: Boolean, rawSizes: List<ImageSize>): ImageSize? =
        if (hasRawCapability) rawSizes.maxByOrNull { it.area } else null
}
