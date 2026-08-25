package top.r2dblog.justcamera.plugin.model

import top.r2dblog.justcamera.plugin.api.PluginApiVersion

enum class PluginType { DENOISE, HDR, NIGHT, TONEMAP, SHARPEN, FILTER, SUPER_RESOLUTION }

data class PluginDescriptor(
    val id: String,
    val displayName: String,
    val vendor: String,
    val pluginVersion: UInt,
    val requiredApi: PluginApiVersion,
    val types: Set<PluginType>,
    val threadSafe: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(types.isNotEmpty())
    }
}
