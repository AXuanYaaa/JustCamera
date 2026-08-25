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
    fun computesFrontAndBackJpegOrientation() {
        assertEquals(0, OrientationCalculator.jpegOrientation(90, 90, CameraFacing.BACK))
        assertEquals(180, OrientationCalculator.jpegOrientation(90, 90, CameraFacing.FRONT))
    }

    @Test
    fun centerCropIsUniformCenteredAndCoversEveryRequestedViewport() {
        val cases = listOf(
            ImageSize(1920, 1080) to PreviewViewport(1080, 2400),
            ImageSize(1920, 1080) to PreviewViewport(1080, 1920),
            ImageSize(1440, 1080) to PreviewViewport(1080, 2400),
            ImageSize(1920, 1080) to PreviewViewport(2400, 1080),
        )

        cases.forEach { (buffer, viewport) ->
            listOf(CameraFacing.BACK, CameraFacing.FRONT).forEach { facing ->
                for (sensor in listOf(0, 90, 180, 270)) {
                    for (display in listOf(0, 90, 180, 270)) {
                        val transform = transform(buffer, viewport, sensor, display, facing)
                        val bounds = transform.transformedBounds
                        assertClose(transform.scaleX, transform.scaleY)
                        assertTrue(bounds.width + EPSILON >= viewport.width)
                        assertTrue(bounds.height + EPSILON >= viewport.height)
                        assertClose(viewport.width / 2f, bounds.centerX)
                        assertClose(viewport.height / 2f, bounds.centerY)
                    }
                }
            }
        }
    }

    @Test
    fun transformOrientationMatchesSensorDisplayRelationship() {
        for (facing in listOf(CameraFacing.BACK, CameraFacing.FRONT)) {
            for (sensor in listOf(0, 90, 180, 270)) {
                for (display in listOf(0, 90, 180, 270)) {
                    val transform = transform(
                        ImageSize(1920, 1080),
                        PreviewViewport(1080, 2400),
                        sensor,
                        display,
                        facing,
                    )
                    val center = transform.mapBufferToViewport(PreviewPoint(960f, 540f))
                    val sourceRight = transform.mapBufferToViewport(PreviewPoint(1060f, 540f))
                    val dx = sourceRight.x - center.x
                    val dy = sourceRight.y - center.y
                    val rotation = transform.geometry.relativeRotationDegrees
                    val expected = expectedSourceRightDirection(rotation, facing == CameraFacing.FRONT)
                    assertEquals(expected.first, dx.signWithTolerance())
                    assertEquals(expected.second, dy.signWithTolerance())
                }
            }
        }
    }

    @Test
    fun inverseTransformMapsVisibleCenterCropBackToBuffer() {
        val back = transform(
            ImageSize(1920, 1080),
            PreviewViewport(1080, 2400),
            sensor = 0,
            display = 0,
            facing = CameraFacing.BACK,
        )
        val center = back.mapNormalizedViewportToBuffer(0.5f, 0.5f)
        assertClose(0.5f, center.x)
        assertClose(0.5f, center.y)

        val left = back.mapNormalizedViewportToBuffer(0f, 0.5f)
        val right = back.mapNormalizedViewportToBuffer(1f, 0.5f)
        assertTrue(left.x > 0f)
        assertTrue(right.x < 1f)
        assertClose(1f, left.x + right.x)

        val leftViewport = PreviewPoint(0f, 1200f)
        val roundTrip = back.mapBufferToViewport(back.mapViewportToBuffer(leftViewport))
        assertClose(leftViewport.x, roundTrip.x)
        assertClose(leftViewport.y, roundTrip.y)
    }

    @Test
    fun frontCameraInverseMappingAccountsForMirror() {
        val viewport = PreviewViewport(1080, 2400)
        val back = transform(ImageSize(1920, 1080), viewport, 0, 0, CameraFacing.BACK)
        val front = transform(ImageSize(1920, 1080), viewport, 0, 0, CameraFacing.FRONT)

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
        sensor: Int,
        display: Int,
        facing: CameraFacing,
    ) = PreviewTransformCalculator.calculate(
        PreviewGeometry(buffer, sensor, display, facing),
        viewport,
    )

    private fun expectedSourceRightDirection(
        rotation: Int,
        mirrored: Boolean,
    ): Pair<Int, Int> {
        val unmirrored = when (rotation) {
            90 -> 0 to 1
            180 -> -1 to 0
            270 -> 0 to -1
            else -> 1 to 0
        }
        return if (mirrored) -unmirrored.first to unmirrored.second else unmirrored
    }

    private fun Float.signWithTolerance(): Int = when {
        this > EPSILON -> 1
        this < -EPSILON -> -1
        else -> 0
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("Expected $expected, actual $actual", abs(expected - actual) < EPSILON)
    }

    companion object {
        private const val EPSILON = 0.001f
    }
}
