package top.r2dblog.justcamera.filter.processing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.model.FilterValidationIssue
import top.r2dblog.justcamera.filter.processing.backend.NativeBackendResult
import top.r2dblog.justcamera.filter.processing.backend.NativeFilterOperation
import top.r2dblog.justcamera.filter.processing.backend.NativeOperationProvider
import top.r2dblog.justcamera.filter.processing.backend.NativeProcessingBackend
import top.r2dblog.justcamera.filter.processing.backend.ProcessingBackendEvent
import top.r2dblog.justcamera.filter.processing.backend.ProcessingBackendKind
import top.r2dblog.justcamera.filter.processing.backend.ProcessingBackendSelection
import top.r2dblog.justcamera.filter.registry.FilterRegistry
import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue
import top.r2dblog.justcamera.imaging.frame.ImageFrame
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.imaging.pipeline.ProcessingContext
import top.r2dblog.justcamera.imaging.pipeline.ProcessingIntent
import top.r2dblog.justcamera.imaging.pipeline.ProcessingNode

data class FilterChainIssue(
    val operationIndex: Int,
    val message: String,
    val isError: Boolean,
)

data class FilterProcessingResult(
    val output: RgbFloatFrame,
    val issues: List<FilterChainIssue>,
    val appliedFilterIds: List<String>,
    val backendEvents: List<ProcessingBackendEvent> = emptyList(),
)

internal data class ResolvedFilterOperation(
    val index: Int,
    val filter: ImageFilter,
    val parameters: FilterParameters,
    val enabled: Boolean,
)

internal data class ResolvedFilterChain(
    val operations: List<ResolvedFilterOperation>,
    val issues: List<FilterChainIssue>,
)

class FilterChainValidator(private val registry: FilterRegistry) {
    internal fun resolve(chain: FilterChain, mode: FilterExecutionMode): ResolvedFilterChain {
        val resolved = mutableListOf<ResolvedFilterOperation>()
        val issues = mutableListOf<FilterChainIssue>()
        chain.operations.forEachIndexed { index, operation ->
            val filter = registry.resolve(operation.filterId)
            if (filter == null) {
                issues += FilterChainIssue(index, "Unknown filter '${operation.filterId}'", true)
                return@forEachIndexed
            }
            if (mode !in filter.descriptor.supportedModes) {
                issues += FilterChainIssue(
                    index,
                    "Filter '${operation.filterId}' does not support $mode",
                    true,
                )
                return@forEachIndexed
            }
            val validation = operation.parameters.validateAndClamp(filter.descriptor)
            issues += validation.issues.map { it.toChainIssue(index) }
            resolved += ResolvedFilterOperation(
                index,
                filter,
                validation.parameters,
                operation.enabled,
            )
        }
        return ResolvedFilterChain(resolved, issues)
    }

    fun validate(chain: FilterChain, mode: FilterExecutionMode): List<FilterChainIssue> =
        resolve(chain, mode).issues

    private fun FilterValidationIssue.toChainIssue(index: Int) =
        FilterChainIssue(index, message, isError)
}

/**
 * Top-level PH3 filter orchestrator. Compatible operation runs may use one PH4 native call;
 * every failure retains the PH3 Kotlin filters as the correctness fallback.
 */
class FilterEngine(
    private val registry: FilterRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val backendSelection: ProcessingBackendSelection = ProcessingBackendSelection.AUTO,
    private val nativeBackend: NativeProcessingBackend = NativeProcessingBackend(),
) {
    private val validator = FilterChainValidator(registry)

    suspend fun process(
        input: RgbFloatFrame,
        chain: FilterChain,
        context: FilterExecutionContext,
    ): FilterProcessingResult = withContext(dispatcher) {
        val resolved = validator.resolve(chain, context.mode)
        val issues = resolved.issues.toMutableList()
        val applied = mutableListOf<String>()
        val backendEvents = mutableListOf<ProcessingBackendEvent>()
        val pendingNative = mutableListOf<Pair<ResolvedFilterOperation, NativeFilterOperation>>()
        var current = input

        suspend fun runKotlin(operations: List<ResolvedFilterOperation>, reason: String) {
            operations.forEach { operation ->
                currentCoroutineContext().ensureActive()
                current = operation.filter.process(current, operation.parameters, context)
                applied += operation.filter.descriptor.id
            }
            if (operations.isNotEmpty()) {
                backendEvents += ProcessingBackendEvent(
                    filterIds = operations.map { it.filter.descriptor.id },
                    backend = ProcessingBackendKind.KOTLIN_REFERENCE,
                    message = reason,
                )
            }
        }

        suspend fun flushNativeRun() {
            if (pendingNative.isEmpty()) return
            val run = pendingNative.toList()
            pendingNative.clear()
            val resolvedRun = run.map { it.first }
            val ids = resolvedRun.map { it.filter.descriptor.id }
            currentCoroutineContext().ensureActive()
            when (val result = nativeBackend.process(current, run.map { it.second })) {
                is NativeBackendResult.Success -> {
                    current = result.output
                    applied += ids
                    backendEvents += ProcessingBackendEvent(
                        ids,
                        ProcessingBackendKind.NATIVE_SCALAR,
                        "Fused native scalar operation run",
                    )
                }
                is NativeBackendResult.Unavailable -> {
                    val explicitNative = backendSelection == ProcessingBackendSelection.NATIVE
                    if (explicitNative) {
                        issues += FilterChainIssue(
                            run.first().first.index,
                            "Native backend unavailable: ${result.message}; Kotlin reference fallback used",
                            isError = false,
                        )
                    }
                    runKotlin(
                        resolvedRun,
                        "Kotlin reference fallback: native backend unavailable",
                    )
                }
                is NativeBackendResult.Failure -> {
                    val fallbackMessage =
                        "${result.message}; Kotlin reference fallback used for ${ids.joinToString()}"
                    issues += FilterChainIssue(
                        run.first().first.index,
                        fallbackMessage,
                        isError = !result.status.recoverable,
                    )
                    nativeBackend.recordFallback(fallbackMessage)
                    runKotlin(resolvedRun, "Kotlin reference fallback after ${result.status.name}")
                }
            }
            currentCoroutineContext().ensureActive()
        }

        resolved.operations.forEach { operation ->
            currentCoroutineContext().ensureActive()
            if (!operation.enabled) return@forEach
            val provider = operation.filter as? NativeOperationProvider
            if (backendSelection != ProcessingBackendSelection.KOTLIN_REFERENCE && provider != null) {
                pendingNative += operation to provider.nativeOperation(operation.parameters)
            } else {
                flushNativeRun()
                runKotlin(listOf(operation), "Kotlin reference backend selected")
            }
        }
        flushNativeRun()
        FilterProcessingResult(current, issues, applied, backendEvents)
    }

    fun processingNode(chain: FilterChain): ProcessingNode = ProcessingNode { input, context ->
        processImageFrame(input, chain, context)
    }

    private suspend fun processImageFrame(
        input: ImageFrame,
        chain: FilterChain,
        context: ProcessingContext,
    ): ImageFrame {
        val mode = when (context.intent) {
            ProcessingIntent.PREVIEW -> FilterExecutionMode.PREVIEW
            ProcessingIntent.FINAL_CAPTURE -> FilterExecutionMode.FINAL_CAPTURE
        }
        val result = process(
            RgbFloatFrame.fromImageFrame(input),
            chain,
            FilterExecutionContext(mode, context.attributes),
        )
        val addedMetadata = buildMap<String, FrameMetadataValue> {
            put("filter.applied", FrameMetadataValue.Text(result.appliedFilterIds.joinToString(",")))
            if (result.issues.isNotEmpty()) {
                put(
                    "filter.validation",
                    FrameMetadataValue.Text(result.issues.joinToString("; ") { it.message }),
                )
            }
        }
        val outputFrame = result.output.toImageFrame()
        return outputFrame.copy(metadata = outputFrame.metadata + addedMetadata)
    }
}
