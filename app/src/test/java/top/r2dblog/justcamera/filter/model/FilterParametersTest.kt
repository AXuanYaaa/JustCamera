package top.r2dblog.justcamera.filter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterParametersTest {
    private val descriptor = FilterDescriptor(
        id = "builtin.film",
        displayName = "Film",
        version = 1,
        executionTarget = FilterExecutionTarget.BOTH,
        parameterSpecs = listOf(
            FilterParameterSpec.FloatRange("strength", "Strength", 0f, 1f, 0.75f),
            FilterParameterSpec.Toggle("grain", "Grain", false),
        ),
    )

    @Test
    fun validatesTypedDataDrivenParameters() {
        val valid = FilterParameters(
            mapOf(
                "strength" to FilterParameterValue.FloatValue(0.6f),
                "grain" to FilterParameterValue.ToggleValue(true),
            ),
        )
        assertTrue(valid.validateAgainst(descriptor).isEmpty())
        assertEquals(0.6f, valid.float("strength"))

        val invalid = FilterParameters(
            mapOf("strength" to FilterParameterValue.FloatValue(2f)),
        )
        assertEquals(listOf("Invalid value for parameter: strength"), invalid.validateAgainst(descriptor))
    }
}
