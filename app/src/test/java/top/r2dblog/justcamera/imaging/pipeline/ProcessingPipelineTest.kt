package top.r2dblog.justcamera.imaging.pipeline

import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import top.r2dblog.justcamera.imaging.frame.FrameFormat
import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue
import top.r2dblog.justcamera.imaging.frame.ImageFrame
import top.r2dblog.justcamera.imaging.frame.ImagePlane

class ProcessingPipelineTest {
    @Test
    fun executesNodesInDeclarationOrder() = runTest {
        val visited = mutableListOf<String>()
        val pipeline = ProcessingPipeline(
            listOf("convert", "tone", "encode").map { name ->
                ProcessingNode { input, _ ->
                    visited += name
                    input.copy(
                        metadata = input.metadata +
                            (name to FrameMetadataValue.Flag(true)),
                    )
                }
            },
        )
        val input = ImageFrame(
            width = 2,
            height = 2,
            format = FrameFormat.RGBA_8888,
            timestampNanos = 7,
            rotationDegrees = 0,
            planes = listOf(ImagePlane(ByteBuffer.allocate(16), 8, 4)),
        )

        val output = pipeline.process(input, ProcessingContext(ProcessingIntent.FINAL_CAPTURE))

        assertEquals(listOf("convert", "tone", "encode"), visited)
        assertEquals(setOf("convert", "tone", "encode"), output.metadata.keys)
    }
}
