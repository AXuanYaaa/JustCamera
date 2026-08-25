package top.r2dblog.justcamera.imaging.pipeline

import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import top.r2dblog.justcamera.imaging.frame.FrameFormat
import top.r2dblog.justcamera.imaging.frame.FrameMetadataValue
import top.r2dblog.justcamera.imaging.frame.ImageFrame
import top.r2dblog.justcamera.imaging.frame.ImagePlane
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame
import top.r2dblog.justcamera.filter.builtin.BuiltInFilterCatalog
import top.r2dblog.justcamera.filter.model.FilterChain
import top.r2dblog.justcamera.filter.model.FilterOperation
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.processing.FilterEngine

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

    @Test
    fun filterEngineUsesTheExistingProcessingPipeline() = runTest {
        val input = RgbFloatFrame.create(
            1,
            1,
            RgbChannelLayout.RGB,
            floatArrayOf(0.2f, 0.25f, 0.3f),
        ).toImageFrame()
        val chain = FilterChain(
            listOf(
                FilterOperation(
                    "builtin.exposure",
                    FilterParameters(
                        mapOf("exposure" to FilterParameterValue.FloatValue(1f)),
                    ),
                ),
            ),
        )
        val pipeline = ProcessingPipeline(
            listOf(FilterEngine(BuiltInFilterCatalog.registry()).processingNode(chain)),
        )

        val output = pipeline.process(input, ProcessingContext(ProcessingIntent.FINAL_CAPTURE))
        val rgb = RgbFloatFrame.fromImageFrame(output)

        assertEquals(0.4f, rgb.sample(0, 0), 0.0001f)
        assertEquals("builtin.exposure", (output.metadata["filter.applied"] as FrameMetadataValue.Text).value)
    }
}
