package top.r2dblog.justcamera.plugin.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.plugin.api.PluginApiVersion
import top.r2dblog.justcamera.plugin.model.PluginDescriptor
import top.r2dblog.justcamera.plugin.model.PluginType

class PluginCompatibilityTest {
    @Test
    fun versionRoundTripsAndRejectsFutureMinorOrDifferentMajor() {
        val host = PluginApiVersion(1, 2, 7)
        assertEquals(host, PluginApiVersion.decode(host.encode()))
        assertTrue(host.supports(PluginApiVersion(1, 1)))
        assertTrue(!host.supports(PluginApiVersion(1, 3)))
        assertTrue(!host.supports(PluginApiVersion(2, 0)))
    }

    @Test
    fun registryAppliesCompatibilityAndUniqueIdRules() {
        val registry = PluginRegistry(PluginApiVersion(1, 0))
        val descriptor = PluginDescriptor(
            id = "builtin.identity",
            displayName = "Identity",
            vendor = "JustCamera",
            pluginVersion = 1u,
            requiredApi = PluginApiVersion(1, 0),
            types = setOf(PluginType.FILTER),
            threadSafe = true,
        )
        assertTrue(registry.register(descriptor) is PluginRegistrationResult.Registered)
        assertTrue(registry.register(descriptor) is PluginRegistrationResult.Rejected)
    }
}
