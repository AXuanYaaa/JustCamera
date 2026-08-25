package top.r2dblog.justcamera.filter.processing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.api.ImageFilter
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.model.FilterValidationIssue
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

/** CPU correctness engine. Work is dispatched off UI/camera threads and remains cancellable. */
class FilterEngine(
    private val registry: FilterRegistry,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val validator = FilterChainValidator(registry)

    suspend fun process(
        input: RgbFloatFrame,
        chain: FilterChain,
        context: FilterExecutionContext,
    ): FilterProcessingResult = withContext(dispatcher) {
        val resolved = validator.resolve(chain, context.mode)
        val applied = mutableListOf<String>()
        var current = input
        resolved.operations.forEach { operation ->
            ensureActive()
            if (operation.enabled) {
                current = operation.filter.process(current, operation.parameters, context)
                applied += operation.filter.descriptor.id
            }
        }
        FilterProcessingResult(current, resolved.issues, applied)
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
