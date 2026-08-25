package top.r2dblog.justcamera.filter.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.filter.builtin.BuiltInFilterCatalog
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterOperation
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.model.FilterPreset
import top.r2dblog.justcamera.filter.processing.FilterChainValidator

class PresetCodecTest {
    @Test
    fun presetRoundTripsChainParametersAndLutReference() {
        val preset = FilterPreset(
            "user.look_1",
            "My Look 你好",
            FilterChain(
                listOf(
                    FilterOperation(
                        "builtin.exposure",
                        FilterParameters(
                            mapOf(
                                "exposure" to FilterParameterValue.FloatValue(0.75f),
                                "passes" to FilterParameterValue.IntValue(2),
                                "enabled" to FilterParameterValue.BooleanValue(true),
                                "style" to FilterParameterValue.EnumValue("soft"),
                            ),
                        ),
                        lutReference = "private/luts/look.cube",
                    ),
                ),
            ),
        )

        val decoded = PresetCodec.decode(PresetCodec.encode(preset))

        assertTrue(decoded is PresetDecodeResult.Success)
        assertEquals(preset, (decoded as PresetDecodeResult.Success).preset)
    }

    @Test
    fun malformedPresetAndUnknownFilterReturnValidationErrors() {
        assertTrue(PresetCodec.decode("broken") is PresetDecodeResult.Failure)

        val chain = FilterChain(listOf(FilterOperation("missing.filter")))
        val errors = FilterChainValidator(BuiltInFilterCatalog.registry())
            .validate(chain, FilterExecutionMode.PREVIEW)
        assertTrue(errors.single().isError)
        assertTrue("Unknown filter" in errors.single().message)
    }
}
