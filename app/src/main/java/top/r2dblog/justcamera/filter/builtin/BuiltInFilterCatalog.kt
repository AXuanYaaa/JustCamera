package top.r2dblog.justcamera.filter.builtin

import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterOperation
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.model.FilterPreset
import top.r2dblog.justcamera.filter.registry.FilterRegistry

object BuiltInFilterCatalog {
    fun filters(): List<ImageFilter> = listOf(
        ExposureFilter(),
        ContrastFilter(),
        SaturationFilter(),
        TemperatureTintFilter(),
        HighlightsShadowsFilter(),
        FadeFilter(),
        VignetteFilter(),
    )

    fun registry(): FilterRegistry = FilterRegistry(filters())
}

object BuiltInPresets {
    val all: List<FilterPreset> = listOf(
        FilterPreset("builtin.neutral", "Neutral", FilterChain()),
        FilterPreset(
            "builtin.soft_contrast",
            "Soft Contrast",
            FilterChain(listOf(operation("builtin.contrast", "contrast", 1.15f))),
        ),
        FilterPreset(
            "builtin.warm_film",
            "Warm Film",
            FilterChain(
                listOf(
                    operation("builtin.temperature_tint", "temperature", 0.35f),
                    operation("builtin.contrast", "contrast", 0.92f),
                    operation("builtin.fade", "fade", 0.18f),
                ),
            ),
        ),
        FilterPreset(
            "builtin.clean_bw",
            "Clean B&W",
            FilterChain(
                listOf(
                    operation("builtin.saturation", "saturation", 0f),
                    operation("builtin.contrast", "contrast", 1.08f),
                ),
            ),
        ),
        FilterPreset(
            "builtin.cinematic_soft",
            "Cinematic Soft",
            FilterChain(
                listOf(
                    operation("builtin.contrast", "contrast", 0.9f),
                    operation("builtin.saturation", "saturation", 0.82f),
                    operation("builtin.fade", "fade", 0.12f),
                    operation("builtin.vignette", "vignette", 0.2f),
                ),
            ),
        ),
    )

    private fun operation(filterId: String, key: String, value: Float) = FilterOperation(
        filterId = filterId,
        parameters = FilterParameters(mapOf(key to FilterParameterValue.FloatValue(value))),
    )
}
