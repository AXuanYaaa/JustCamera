package top.r2dblog.justcamera.filter.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.filter.builtin.ExposureFilter
import top.r2dblog.justcamera.filter.model.FilterCategory
import top.r2dblog.justcamera.filter.model.FilterExecutionMode

class FilterRegistryTest {
    @Test
    fun rejectsDuplicateStableIdsAndEnumeratesCapabilities() {
        val registry = FilterRegistry()
        assertTrue(registry.register(ExposureFilter()) is FilterRegistrationResult.Registered)
        assertTrue(registry.register(ExposureFilter()) is FilterRegistrationResult.Rejected)

        assertNotNull(registry.resolve("builtin.exposure"))
        assertEquals(setOf(FilterCategory.ADJUSTMENT), registry.categories())
        assertEquals(
            listOf("builtin.exposure"),
            registry.descriptors(mode = FilterExecutionMode.FINAL_CAPTURE).map { it.id },
        )
    }
}
