package top.r2dblog.justcamera.camera.session

import org.junit.Assert.assertEquals
import org.junit.Test
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize

class PreviewGeometryTest {
    @Test
    fun selectsBoundedSizeClosestToViewAspectRatio() {
        val selected = PreviewSizeSelector.select(
            choices = listOf(
                ImageSize(4000, 3000),
                ImageSize(1920, 1080),
                ImageSize(1280, 720),
                ImageSize(1440, 1080),
            ),
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertEquals(ImageSize(1920, 1080), selected)
    }

    @Test
    fun computesFrontAndBackJpegOrientation() {
        assertEquals(
            0,
            OrientationCalculator.jpegOrientation(90, 90, CameraFacing.BACK),
        )
        assertEquals(
            180,
            OrientationCalculator.jpegOrientation(90, 90, CameraFacing.FRONT),
        )
    }
}
