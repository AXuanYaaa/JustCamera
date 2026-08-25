package top.r2dblog.justcamera.imaging.pipeline

import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue
import top.r2dblog.justcamera.imaging.frame.ImageFrame

enum class ProcessingIntent { PREVIEW, FINAL_CAPTURE }

data class ProcessingContext(
    val intent: ProcessingIntent,
    val attributes: Map<String, FrameMetadataValue> = emptyMap(),
)

fun interface ProcessingNode {
    suspend fun process(input: ImageFrame, context: ProcessingContext): ImageFrame
}

class ProcessingPipeline(private val nodes: List<ProcessingNode>) {
    suspend fun process(input: ImageFrame, context: ProcessingContext): ImageFrame =
        nodes.fold(input) { frame, node -> node.process(frame, context) }

    val size: Int get() = nodes.size
}
