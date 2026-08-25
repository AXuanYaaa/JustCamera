package top.r2dblog.justcamera.filter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterParametersTest {
    private val descriptor = FilterDescriptor(
        id = "test.parameters",
        displayName = "Parameters",
        category = FilterCategory.UTILITY,
        implementationType = FilterImplementationType.KOTLIN_CPU_REFERENCE,
        supportedModes = FilterExecutionMode.entries.toSet(),
        parameterSpecs = listOf(
            FilterParameterSpec.FloatParameter("strength", "Strength", 0.75f, 0f, 1f, 0.05f),
            FilterParameterSpec.IntParameter("passes", "Passes", 2, 1, 4),
            FilterParameterSpec.BooleanParameter("enabled", "Enabled", true),
            FilterParameterSpec.EnumParameter("style", "Style", "soft", listOf("soft", "hard")),
        ),
    )

    @Test
    fun validatesAllTypesAndSuppliesDefaults() {
        val validation = FilterParameters(
            mapOf(
                "strength" to FilterParameterValue.FloatValue(0.6f),
                "passes" to FilterParameterValue.IntValue(3),
                "style" to FilterParameterValue.EnumValue("hard"),
            ),
        ).validateAndClamp(descriptor)

        assertTrue(validation.errors.isEmpty())
        assertEquals(0.6f, validation.parameters.float("strength"))
        assertEquals(3, validation.parameters.int("passes"))
        assertTrue(validation.parameters.boolean("enabled"))
        assertEquals("hard", validation.parameters.enum("style"))
    }

    @Test
    fun malformedValuesClampOrFallBackWithoutCrashing() {
        val validation = FilterParameters(
            mapOf(
                "strength" to FilterParameterValue.FloatValue(2f),
                "passes" to FilterParameterValue.IntValue(-5),
                "style" to FilterParameterValue.EnumValue("corrupt"),
                "unknown" to FilterParameterValue.BooleanValue(true),
            ),
        ).validateAndClamp(descriptor)

        assertEquals(1f, validation.parameters.float("strength"))
        assertEquals(1, validation.parameters.int("passes"))
        assertEquals("soft", validation.parameters.enum("style"))
        assertFalse(validation.issues.isEmpty())
        assertTrue(validation.errors.any { it.key == "style" })
        assertTrue(validation.errors.any { it.key == "unknown" })
    }
}
