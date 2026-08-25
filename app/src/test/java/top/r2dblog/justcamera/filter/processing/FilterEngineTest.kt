package top.r2dblog.justcamera.filter.processing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.builtin.ContrastFilter
import top.r2dblog.justcamera.filter.builtin.ExposureFilter
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterOperation
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.registry.FilterRegistry
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

class FilterEngineTest {
    private val registry = FilterRegistry(listOf(ExposureFilter(), ContrastFilter()))
    private val engine = FilterEngine(registry, Dispatchers.Unconfined)
    private val context = FilterExecutionContext(FilterExecutionMode.FINAL_CAPTURE)

    @Test
    fun emptyAndDisabledChainsAreIdentityWithoutMutatingSource() = runTest {
        val input = frame(0.2f)
        val before = input.copyPixels()
        val empty = engine.process(input, FilterChain(), context)
        val disabled = engine.process(
            input,
            FilterChain(listOf(operation("builtin.exposure", "exposure", 1f, false))),
            context,
        )

        assertArrayEquals(before, empty.output.copyPixels(), EPSILON)
        assertArrayEquals(before, disabled.output.copyPixels(), EPSILON)
        assertArrayEquals(before, input.copyPixels(), EPSILON)
        assertEquals(emptyList<String>(), disabled.appliedFilterIds)
    }

    @Test
    fun chainExecutesInDeclarationOrder() = runTest {
        val input = frame(0.2f)
        val exposureThenFlatContrast = FilterChain(
            listOf(
                operation("builtin.exposure", "exposure", 1f),
                operation("builtin.contrast", "contrast", 0f),
            ),
        )
        val flatContrastThenExposure = FilterChain(exposureThenFlatContrast.operations.reversed())

        val first = engine.process(input, exposureThenFlatContrast, context).output
        val second = engine.process(input, flatContrastThenExposure, context).output

        assertEquals(0.18f, first.sample(0, 0), EPSILON)
        assertEquals(0.36f, second.sample(0, 0), EPSILON)
        assertFalse(first.copyPixels().contentEquals(second.copyPixels()))
    }

    @Test
    fun sameInputAndChainProduceDeterministicOutput() = runTest {
        val input = frame(0.31f)
        val chain = FilterChain(listOf(operation("builtin.exposure", "exposure", 0.7f)))

        val first = engine.process(input, chain, context).output.copyPixels()
        val second = engine.process(input, chain, context).output.copyPixels()

        assertArrayEquals(first, second, 0f)
    }

    private fun frame(value: Float) = RgbFloatFrame.create(
        1,
        1,
        RgbChannelLayout.RGB,
        floatArrayOf(value, value, value),
    )

    private fun operation(id: String, key: String, value: Float, enabled: Boolean = true) =
        FilterOperation(
            id,
            FilterParameters(mapOf(key to FilterParameterValue.FloatValue(value))),
            enabled,
        )

    private companion object { const val EPSILON = 0.0001f }
}
