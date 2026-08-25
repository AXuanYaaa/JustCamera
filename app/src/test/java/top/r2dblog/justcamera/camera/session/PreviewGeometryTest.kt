package top.r2dblog.justcamera.camera.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.model.SensorRect
import kotlin.math.abs

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
    fun jpegOrientationStillUsesCameraAndDisplayRotation() {
        assertEquals(0, OrientationCalculator.jpegOrientation(90, 90, CameraFacing.BACK))
        assertEquals(180, OrientationCalculator.jpegOrientation(90, 90, CameraFacing.FRONT))
    }

    @Test
    fun tallPortraitViewportUsesUniformCenterCropWithoutRotation() {
        val transform = transform(
            buffer = ImageSize(1920, 1080),
            viewport = PreviewViewport(1080, 2400),
            facing = CameraFacing.BACK,
        )
        val bounds = transform.transformedBounds

        assertClose(2400f / 1080f, transform.scale)
        assertClose(transform.scaleX, transform.scaleY)
        assertClose(2400f, bounds.height)
        assertTrue(bounds.width > 1080f)
        assertClose(540f, bounds.centerX)
        assertClose(1200f, bounds.centerY)
        assertClose((1080f - 1920f * transform.scale) / 2f, transform.translationX)
        assertClose((2400f - 1080f * transform.scale) / 2f, transform.translationY)
    }

    @Test
    fun centerCropIsUniformCenteredAndCoversRequestedViewports() {
        val cases = listOf(
            ImageSize(1920, 1080) to PreviewViewport(1080, 2400),
            ImageSize(1920, 1080) to PreviewViewport(1080, 1920),
            ImageSize(1440, 1080) to PreviewViewport(1080, 2400),
            ImageSize(1920, 1080) to PreviewViewport(2400, 1080),
        )

        cases.forEach { (buffer, viewport) ->
            listOf(CameraFacing.BACK, CameraFacing.FRONT).forEach { facing ->
                val transform = transform(buffer, viewport, facing)
                val bounds = transform.transformedBounds
                assertClose(transform.scaleX, transform.scaleY)
                assertTrue(bounds.width + EPSILON >= viewport.width)
                assertTrue(bounds.height + EPSILON >= viewport.height)
                assertClose(viewport.width / 2f, bounds.centerX)
                assertClose(viewport.height / 2f, bounds.centerY)
            }
        }
    }

    @Test
    fun circleRemainsCircularAfterPreviewTransform() {
        val transform = transform(
            ImageSize(1920, 1080),
            PreviewViewport(1080, 2400),
            CameraFacing.BACK,
        )
        val center = transform.mapBufferToViewport(PreviewPoint(960f, 540f))
        val right = transform.mapBufferToViewport(PreviewPoint(1060f, 540f))
        val down = transform.mapBufferToViewport(PreviewPoint(960f, 640f))

        assertClose(abs(right.x - center.x), abs(down.y - center.y))
        assertClose(center.y, right.y)
        assertClose(center.x, down.x)
    }

    @Test
    fun transformIntroducesNoRotation() {
        val transform = transform(
            ImageSize(1920, 1080),
            PreviewViewport(1080, 2400),
            CameraFacing.BACK,
        )
        val center = transform.mapBufferToViewport(PreviewPoint(960f, 540f))
        val sourceRight = transform.mapBufferToViewport(PreviewPoint(1060f, 540f))
        val sourceDown = transform.mapBufferToViewport(PreviewPoint(960f, 640f))

        assertTrue(sourceRight.x > center.x)
        assertClose(center.y, sourceRight.y)
        assertTrue(sourceDown.y > center.y)
        assertClose(center.x, sourceDown.x)
    }

    @Test
    fun frontCameraMirrorDoesNotRotateOrChangeScale() {
        val viewport = PreviewViewport(1080, 2400)
        val back = transform(ImageSize(1920, 1080), viewport, CameraFacing.BACK)
        val front = transform(ImageSize(1920, 1080), viewport, CameraFacing.FRONT)
        val center = front.mapBufferToViewport(PreviewPoint(960f, 540f))
        val sourceRight = front.mapBufferToViewport(PreviewPoint(1060f, 540f))
        val sourceDown = front.mapBufferToViewport(PreviewPoint(960f, 640f))

        assertClose(back.scale, front.scale)
        assertTrue(sourceRight.x < center.x)
        assertClose(center.y, sourceRight.y)
        assertTrue(sourceDown.y > center.y)
        assertClose(center.x, sourceDown.x)
    }

    @Test
    fun inverseTransformMapsVisibleCenterCropBackToPreviewBuffer() {
        val transform = transform(
            ImageSize(1920, 1080),
            PreviewViewport(1080, 2400),
            CameraFacing.BACK,
        )
        val center = transform.mapNormalizedViewportToBuffer(0.5f, 0.5f)
        assertClose(0.5f, center.x)
        assertClose(0.5f, center.y)

        val left = transform.mapNormalizedViewportToBuffer(0f, 0.5f)
        val right = transform.mapNormalizedViewportToBuffer(1f, 0.5f)
        assertTrue(left.x > 0f)
        assertTrue(right.x < 1f)
        assertClose(1f, left.x + right.x)

        val viewportPoint = PreviewPoint(0f, 1200f)
        val roundTrip = transform.mapBufferToViewport(
            transform.mapViewportToBuffer(viewportPoint),
        )
        assertClose(viewportPoint.x, roundTrip.x)
        assertClose(viewportPoint.y, roundTrip.y)
    }

    @Test
    fun frontCameraInverseMappingAccountsOnlyForMirror() {
        val viewport = PreviewViewport(1080, 2400)
        val back = transform(ImageSize(1920, 1080), viewport, CameraFacing.BACK)
        val front = transform(ImageSize(1920, 1080), viewport, CameraFacing.FRONT)

        val backLeft = back.mapNormalizedViewportToBuffer(0f, 0.5f)
        val frontLeft = front.mapNormalizedViewportToBuffer(0f, 0.5f)
        assertClose(1f, backLeft.x + frontLeft.x)
        assertClose(backLeft.y, frontLeft.y)
    }

    @Test
    fun meteringCropMatchesPreviewStreamAspectRatio() {
        val crop = PreviewMeteringCropCalculator.visibleSensorCrop(
            sensorCrop = SensorRect(0, 0, 4000, 3000),
            previewBuffer = ImageSize(1920, 1080),
        )

        assertEquals(SensorRect(0, 375, 4000, 2625), crop)
        assertClose(16f / 9f, crop.width.toFloat() / crop.height)
    }

    @Test
    fun meteringCropPreservesCenterForNarrowerPreview() {
        val crop = PreviewMeteringCropCalculator.visibleSensorCrop(
            sensorCrop = SensorRect(100, 200, 4100, 2450),
            previewBuffer = ImageSize(1440, 1080),
        )

        assertEquals(SensorRect(600, 200, 3600, 2450), crop)
        assertEquals(2100, (crop.left + crop.right) / 2)
        assertEquals(1325, (crop.top + crop.bottom) / 2)
    }

    private fun transform(
        buffer: ImageSize,
        viewport: PreviewViewport,
        facing: CameraFacing,
    ) = PreviewTransformCalculator.calculate(
        PreviewGeometry(buffer, facing),
        viewport,
    )

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("Expected $expected, actual $actual", abs(expected - actual) < EPSILON)
    }

    companion object {
        private const val EPSILON = 0.001f
    }
}
