package top.r2dblog.justcamera.plugin.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.r2dblog.justcamera.plugin.api.PluginApiVersion
import top.r2dblog.justcamera.plugin.model.PluginDescriptor

sealed interface PluginRegistrationResult {
    data class Registered(val descriptor: PluginDescriptor) : PluginRegistrationResult
    data class Rejected(val reason: String) : PluginRegistrationResult
}

class PluginRegistry(private val hostApi: PluginApiVersion = PluginApiVersion.HOST) {
    private val _plugins = MutableStateFlow<Map<String, PluginDescriptor>>(emptyMap())
    val plugins: StateFlow<Map<String, PluginDescriptor>> = _plugins.asStateFlow()

    @Synchronized
    fun register(descriptor: PluginDescriptor): PluginRegistrationResult {
        if (!hostApi.supports(descriptor.requiredApi)) {
            return PluginRegistrationResult.Rejected(
                "Plugin requires API ${descriptor.requiredApi}; host provides $hostApi",
            )
        }
        if (descriptor.id in _plugins.value) {
            return PluginRegistrationResult.Rejected("Plugin id already registered: ${descriptor.id}")
        }
        _plugins.value = _plugins.value + (descriptor.id to descriptor)
        return PluginRegistrationResult.Registered(descriptor)
    }

    @Synchronized
    fun unregister(id: String): Boolean {
        if (id !in _plugins.value) return false
        _plugins.value = _plugins.value - id
        return true
    }
}
