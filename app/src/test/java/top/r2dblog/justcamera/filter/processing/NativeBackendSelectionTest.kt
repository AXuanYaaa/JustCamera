package top.r2dblog.justcamera.filter.processing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.builtin.ContrastFilter
import top.r2dblog.justcamera.filter.builtin.ExposureFilter
import top.r2dblog.justcamera.filter.builtin.SaturationFilter
import top.r2dblog.justcamera.filter.builtin.TemperatureTintFilter
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterOperation
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.processing.backend.NativeBackendResult
import top.r2dblog.justcamera.filter.processing.backend.NativeFilterOperation
import top.r2dblog.justcamera.filter.processing.backend.NativeProcessingBackend
import top.r2dblog.justcamera.filter.processing.backend.NativeProcessingBridge
import top.r2dblog.justcamera.filter.processing.backend.NativeStatus
import top.r2dblog.justcamera.filter.processing.backend.ProcessingBackendKind
import top.r2dblog.justcamera.filter.processing.backend.ProcessingBackendSelection
import top.r2dblog.justcamera.filter.registry.FilterRegistry
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.nativecore.NativeCapabilities

class NativeBackendSelectionTest {
    private val context = FilterExecutionContext(FilterExecutionMode.FINAL_CAPTURE)

    @Test
    fun autoFusesAdjacentCompatibleOperationsAndSplitsAtKotlinFilter() = runTest {
        val bridge = FakeBridge()
        val engine = engine(
            bridge,
            ProcessingBackendSelection.AUTO,
            ExposureFilter(),
            ContrastFilter(),
            TemperatureTintFilter(),
            SaturationFilter(),
        )
        val chain = FilterChain(
            listOf(
                operation("builtin.exposure", "exposure", 1f),
                operation("builtin.contrast", "contrast", 1.2f),
                operation("builtin.temperature_tint", "temperature", 0.2f),
                operation("builtin.saturation", "saturation", 0.7f),
            ),
        )

        val result = engine.process(frame(0.2f), chain, context)

        assertEquals(listOf(2, 1), bridge.operationCounts)
        assertEquals(
            listOf(
                ProcessingBackendKind.NATIVE_SCALAR,
                ProcessingBackendKind.KOTLIN_REFERENCE,
                ProcessingBackendKind.NATIVE_SCALAR,
            ),
            result.backendEvents.map { it.backend },
        )
        assertEquals(chain.operations.map { it.filterId }, result.appliedFilterIds)
    }

    @Test
    fun recoverableNativeFailureUsesOracleWithoutMutatingInput() = runTest {
        val bridge = FakeBridge(failure = NativeStatus.INVALID_BUFFER)
        val fallbackLogs = mutableListOf<String>()
        val backend = NativeProcessingBackend(bridge) { message, _ -> fallbackLogs += message }
        val engine = FilterEngine(
            FilterRegistry(listOf(ExposureFilter())),
            Dispatchers.Unconfined,
            ProcessingBackendSelection.AUTO,
            backend,
        )
        val input = frame(0.2f)
        val before = input.copyPixels()

        val result = engine.process(
            input,
            FilterChain(listOf(operation("builtin.exposure", "exposure", 1f))),
            context,
        )

        assertArrayEquals(floatArrayOf(0.4f, 0.4f, 0.4f), result.output.copyPixels(), EPSILON)
        assertArrayEquals(before, input.copyPixels(), 0f)
        assertEquals(1, result.issues.size)
        assertFalse(result.issues.single().isError)
        assertTrue(result.issues.single().message.contains("INVALID_BUFFER"))
        assertEquals(1, fallbackLogs.size)
        assertEquals(ProcessingBackendKind.KOTLIN_REFERENCE, result.backendEvents.single().backend)
    }

    @Test
    fun systematicNativeFailureIsVisibleEvenThoughOutputFallsBack() = runTest {
        val bridge = FakeBridge(failure = NativeStatus.INTERNAL_ERROR)
        val result = engine(
            bridge,
            ProcessingBackendSelection.AUTO,
            ExposureFilter(),
        ).process(
            frame(0.2f),
            FilterChain(listOf(operation("builtin.exposure", "exposure", 1f))),
            context,
        )

        assertTrue(result.issues.single().isError)
        assertEquals(0.4f, result.output.sample(0, 0), EPSILON)
    }

    @Test
    fun kotlinReferenceSelectionNeverCallsNative() = runTest {
        val bridge = FakeBridge()
        val result = engine(
            bridge,
            ProcessingBackendSelection.KOTLIN_REFERENCE,
            ExposureFilter(),
        ).process(
            frame(0.2f),
            FilterChain(listOf(operation("builtin.exposure", "exposure", 1f))),
            context,
        )

        assertEquals(emptyList<Int>(), bridge.operationCounts)
        assertEquals(0.4f, result.output.sample(0, 0), EPSILON)
        assertEquals(ProcessingBackendKind.KOTLIN_REFERENCE, result.backendEvents.single().backend)
    }

    @Test
    fun explicitNativeSelectionReportsUnavailableThenFallsBack() = runTest {
        val bridge = FakeBridge(available = false)
        val result = engine(
            bridge,
            ProcessingBackendSelection.NATIVE,
            ExposureFilter(),
        ).process(
            frame(0.2f),
            FilterChain(listOf(operation("builtin.exposure", "exposure", 1f))),
            context,
        )

        assertEquals(1, result.issues.size)
        assertTrue(result.issues.single().message.contains("unavailable"))
        assertEquals(0.4f, result.output.sample(0, 0), EPSILON)
    }

    private fun engine(
        bridge: FakeBridge,
        selection: ProcessingBackendSelection,
        vararg filters: top.r2dblog.justcamera.filter.api.ImageFilter,
    ) = FilterEngine(
        FilterRegistry(filters.toList()),
        Dispatchers.Unconfined,
        selection,
        NativeProcessingBackend(bridge) { _, _ -> },
    )

    private fun frame(value: Float) = RgbFloatFrame.create(
        1,
        1,
        RgbChannelLayout.RGB,
        floatArrayOf(value, value, value),
    )

    private fun operation(id: String, key: String, value: Float) = FilterOperation(
        id,
        FilterParameters(mapOf(key to FilterParameterValue.FloatValue(value))),
    )

    private class FakeBridge(
        private val available: Boolean = true,
        private val failure: NativeStatus? = null,
    ) : NativeProcessingBridge {
        val operationCounts = mutableListOf<Int>()
        private val capabilities = NativeCapabilities(
            "test-core",
            "test-processing",
            "test-abi",
            neonAvailable = false,
            simdKernelsActive = false,
        )

        override fun capabilities(): NativeCapabilities? = capabilities.takeIf { available }

        override fun process(
            input: RgbFloatFrame,
            operations: List<NativeFilterOperation>,
        ): NativeBackendResult {
            operationCounts += operations.size
            if (!available) return NativeBackendResult.Unavailable("test unavailable")
            return failure?.let {
                NativeBackendResult.Failure(it, "Native processing returned ${it.name}", capabilities)
            } ?: NativeBackendResult.Success(input, capabilities)
        }
    }

    private companion object { const val EPSILON = 0.0001f }
}
