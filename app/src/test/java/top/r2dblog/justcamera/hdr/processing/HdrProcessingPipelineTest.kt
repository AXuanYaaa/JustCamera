package top.r2dblog.justcamera.hdr.processing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.hdr.model.HdrExposureMetadata
import top.r2dblog.justcamera.hdr.model.HdrFrameSet
import top.r2dblog.justcamera.hdr.model.HdrInputFrame
import top.r2dblog.justcamera.hdr.model.HdrProcessingStage
import top.r2dblog.justcamera.hdr.yuv.OwnedYuvFrame
import top.r2dblog.justcamera.hdr.yuv.OwnedYuvPlane

class HdrProcessingPipelineTest {
    @Test
    fun executesReferencePipelineAndHandsOffNormalizedDisplayFrame() = runTest {
        val inputs = listOf(
            input(0, 70, 5_000_000, false),
            input(1, 126, 10_000_000, true),
            input(2, 200, 20_000_000, false),
        )
        val stages = mutableListOf<HdrProcessingStage>()

        val output = HdrProcessingPipeline().process(HdrFrameSet(inputs, 1), 90, stages::add)

        assertEquals(HdrProcessingStage.entries, stages)
        assertEquals(90, output.toneMapped.rotationDegrees)
        assertTrue(output.sceneLinearHdr.copySamples().all { it.isFinite() && it >= 0f })
        assertTrue(output.toneMapped.copyPixels().all { it.isFinite() && it in 0f..1f })
        assertEquals(setOf(0, 1, 2), output.diagnostics.translations.keys)
    }

    private fun input(index: Int, y: Int, exposureTime: Long, reference: Boolean): HdrInputFrame {
        val timestamp = index.toLong() + 1
        val yPlane = ByteArray(16) { y.toByte() }
        val chroma = ByteArray(4) { 128.toByte() }
        val frame = OwnedYuvFrame.create(
            4,
            4,
            timestamp,
            OwnedYuvPlane.create(yPlane, 4, 1),
            OwnedYuvPlane.create(chroma, 2, 1),
            OwnedYuvPlane.create(chroma, 2, 1),
        )
        return HdrInputFrame(
            frame,
            HdrExposureMetadata(
                exposureTime,
                100,
                timestamp,
                index,
                0.0,
                reference,
            ),
        )
    }
}
