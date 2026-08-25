package top.r2dblog.justcamera.camera.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.model.SensorRect
import kotlin.math.abs
import kotlin.math.sqrt

class PreviewGeometryTest {
    @Test
    fun previewSizeSelectionIsStableAndIndependentFromPhoneViewport() {
        val choices = listOf(
            ImageSize(4000, 3000),
            ImageSize(1920, 1080),
            ImageSize(1280, 720),
            ImageSize(1440, 1080),
        )

        assertEquals(ImageSize(1920, 1080), PreviewSizeSelector.select(choices))
        assertEquals(
            ImageSize(1280, 720),
            PreviewSizeSelector.select(listOf(ImageSize(1440, 1080), ImageSize(1280, 720))),
        )
        assertEquals(
            ImageSize(2560, 1440),
            PreviewSizeSelector.select(listOf(ImageSize(4000, 3000), ImageSize(2560, 1440))),
        )
    }

    @Test
    fun jpegOrientationRemainsIndependentFromPreviewRotation() {
        assertEquals(0, OrientationCalculator.jpegOrientation(90, 90, CameraFacing.BACK))
        assertEquals(180, OrientationCalculator.jpegOrientation(90, 90, CameraFacing.FRONT))
        assertEquals(
            180,
            OrientationCalculator.relativePreviewRotationDegrees(90, 90, CameraFacing.BACK),
        )
        assertEquals(
            0,
            OrientationCalculator.relativePreviewRotationDegrees(90, 90, CameraFacing.FRONT),
        )
    }

    @Test
    fun relativePreviewRotationCoversEveryRightAngleAndFacing() {
        val sensors = listOf(0, 90, 180, 270)
        val expectedBack = mapOf(
            0 to listOf(0, 90, 180, 270),
            90 to listOf(90, 180, 270, 0),
            180 to listOf(180, 270, 0, 90),
            270 to listOf(270, 0, 90, 180),
        )
        val expectedFront = mapOf(
            0 to listOf(0, 90, 180, 270),
            90 to listOf(270, 0, 90, 180),
            180 to listOf(180, 270, 0, 90),
            270 to listOf(90, 180, 270, 0),
        )

        expectedBack.forEach { (display, expected) ->
            sensors.forEachIndexed { index, sensor ->
                assertEquals(
                    expected[index],
                    OrientationCalculator.relativePreviewRotationDegrees(
                        sensor,
                        display,
                        CameraFacing.BACK,
                    ),
                )
            }
        }
        expectedFront.forEach { (display, expected) ->
            sensors.forEachIndexed { index, sensor ->
                assertEquals(
                    expected[index],
                    OrientationCalculator.relativePreviewRotationDegrees(
                        sensor,
                        display,
                        CameraFacing.FRONT,
                    ),
                )
            }
        }
    }

    @Test
    fun phoneLike1920By1080MappingIsUprightUniformCenteredAndCovering() {
        val transform = transform(
            buffer = PreviewBufferSize(1920, 1080),
            viewport = PreviewViewportSize(1080, 2400),
            sensor = 90,
            display = 0,
            facing = CameraFacing.BACK,
        )

        assertEquals(PreviewRotation(90), transform.geometry.relativeRotation)
        assertEquals(PreviewBufferSize(1080, 1920), transform.effectiveBufferSize)
        assertClose(1.25f, transform.uniformScale)
        assertClose(-135f, transform.transformedBounds.left)
        assertClose(0f, transform.transformedBounds.top)
        assertClose(1215f, transform.transformedBounds.right)
        assertClose(2400f, transform.transformedBounds.bottom)
        assertMatrixClose(
            listOf(0f, -1.25f, 1215f, 1.25f, 0f, 0f, 0f, 0f, 1f),
            transform.bufferToViewportMatrix.values,
        )
        assertMatrixClose(
            listOf(1.25f, 0f, -135f, 0f, 1f, 0f, 0f, 0f, 1f),
            transform.textureViewMatrix.values,
        )
        assertCenterCoverageAndOrthonormalScale(transform)

        val center = transform.mapBufferToViewport(PreviewPoint(960f, 540f))
        val sourceRight = transform.mapBufferToViewport(PreviewPoint(1060f, 540f))
        assertClose(center.x, sourceRight.x)
        assertTrue(sourceRight.y > center.y)
    }

    @Test
    fun phoneLike1440By1080MappingUsesOnlyCenterCrop() {
        val transform = transform(
            buffer = PreviewBufferSize(1440, 1080),
            viewport = PreviewViewportSize(1080, 2400),
            sensor = 90,
            display = 0,
            facing = CameraFacing.BACK,
        )

        assertEquals(PreviewBufferSize(1080, 1440), transform.effectiveBufferSize)
        assertClose(2400f / 1440f, transform.uniformScale)
        assertClose(-360f, transform.transformedBounds.left)
        assertClose(0f, transform.transformedBounds.top)
        assertClose(1440f, transform.transformedBounds.right)
        assertClose(2400f, transform.transformedBounds.bottom)
        assertCenterCoverageAndOrthonormalScale(transform)
    }

    @Test
    fun finalTextureViewPipelineEqualsCanonicalBufferMatrix() {
        val cases = listOf(
            transform(PreviewBufferSize(1920, 1080), PreviewViewportSize(1080, 2400), 90, 0),
            transform(PreviewBufferSize(1920, 1080), PreviewViewportSize(2400, 1080), 90, 90),
            transform(PreviewBufferSize(1440, 1080), PreviewViewportSize(1080, 2400), 90, 0),
            transform(
                PreviewBufferSize(1920, 1080),
                PreviewViewportSize(1080, 2400),
                270,
                0,
                CameraFacing.FRONT,
            ),
        )

        cases.forEach { transform ->
            val buffer = transform.geometry.bufferSize
            listOf(
                PreviewPoint(0f, 0f),
                PreviewPoint(buffer.width.toFloat(), 0f),
                PreviewPoint(0f, buffer.height.toFloat()),
                PreviewPoint(buffer.width / 2f, buffer.height / 2f),
            ).forEach { point ->
                assertPointClose(
                    transform.mapBufferToViewport(point),
                    transform.mapThroughTextureView(point),
                )
            }
            assertCenterCoverageAndOrthonormalScale(transform)
        }
    }

    @Test
    fun everySensorDisplayRightAngleAndFacingRemainsUniformAndCentered() {
        listOf(CameraFacing.BACK, CameraFacing.FRONT).forEach { facing ->
            listOf(0, 90, 180, 270).forEach { sensor ->
                listOf(0, 90, 180, 270).forEach { display ->
                    val transform = transform(
                        PreviewBufferSize(1920, 1080),
                        PreviewViewportSize(1080, 2400),
                        sensor,
                        display,
                        facing,
                    )
                    assertCenterCoverageAndOrthonormalScale(transform)
                }
            }
        }
    }

    @Test
    fun syntheticCircleRemainsACircleThroughActualTextureViewModel() {
        val transform = transform(
            PreviewBufferSize(1920, 1080),
            PreviewViewportSize(1080, 2400),
            sensor = 90,
            display = 0,
        )
        val center = PreviewPoint(960f, 540f)
        val radius = 100f
        val mappedCenter = transform.mapThroughTextureView(center)
        val radii = listOf(
            PreviewPoint(center.x + radius, center.y),
            PreviewPoint(center.x, center.y + radius),
            PreviewPoint(center.x - radius, center.y),
            PreviewPoint(center.x, center.y - radius),
        ).map { distance(mappedCenter, transform.mapThroughTextureView(it)) }

        radii.forEach { assertClose(radii.first(), it) }
        assertClose(radius * transform.uniformScale, radii.first())
    }

    @Test
    fun producerAutoMirrorIsNotAppliedAgainByTextureViewMatrix() {
        val auto = transform(
            PreviewBufferSize(1920, 1080),
            PreviewViewportSize(1080, 2400),
            sensor = 270,
            display = 0,
            facing = CameraFacing.FRONT,
            mirrorOwner = PreviewMirrorOwner.OUTPUT_CONFIGURATION_AUTO,
        )
        val manual = transform(
            PreviewBufferSize(1920, 1080),
            PreviewViewportSize(1080, 2400),
            sensor = 270,
            display = 0,
            facing = CameraFacing.FRONT,
            mirrorOwner = PreviewMirrorOwner.APPLICATION,
        )

        assertTrue(determinant(auto.bufferToViewportMatrix) < 0f)
        assertTrue(determinant(auto.textureViewIntrinsicMatrix) < 0f)
        assertTrue(determinant(auto.textureViewMatrix) > 0f)
        assertTrue(determinant(manual.textureViewIntrinsicMatrix) > 0f)
        assertTrue(determinant(manual.textureViewMatrix) < 0f)
    }

    @Test
    fun inverseFocusMappingUsesTheSameFinalTransform() {
        val transform = transform(
            PreviewBufferSize(1920, 1080),
            PreviewViewportSize(1080, 2400),
            sensor = 90,
            display = 0,
        )
        val viewportPoint = PreviewPoint(200f, 700f)
        val bufferPoint = transform.mapViewportToBuffer(viewportPoint)
        assertPointClose(viewportPoint, transform.mapBufferToViewport(bufferPoint))

        val normalizedCenter = transform.mapNormalizedViewportToBuffer(0.5f, 0.5f)
        assertClose(0.5f, normalizedCenter.x)
        assertClose(0.5f, normalizedCenter.y)
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

    private fun assertCenterCoverageAndOrthonormalScale(transform: PreviewTransform) {
        val center = PreviewPoint(
            transform.geometry.bufferSize.width / 2f,
            transform.geometry.bufferSize.height / 2f,
        )
        val mappedCenter = transform.mapThroughTextureView(center)
        val xPoint = transform.mapThroughTextureView(PreviewPoint(center.x + 100f, center.y))
        val yPoint = transform.mapThroughTextureView(PreviewPoint(center.x, center.y + 100f))
        val xVector = PreviewPoint(xPoint.x - mappedCenter.x, xPoint.y - mappedCenter.y)
        val yVector = PreviewPoint(yPoint.x - mappedCenter.x, yPoint.y - mappedCenter.y)

        assertClose(100f * transform.uniformScale, vectorLength(xVector))
        assertClose(vectorLength(xVector), vectorLength(yVector))
        assertClose(0f, xVector.x * yVector.x + xVector.y * yVector.y)
        assertClose(transform.viewportSize.width / 2f, mappedCenter.x)
        assertClose(transform.viewportSize.height / 2f, mappedCenter.y)
        assertTrue(transform.transformedBounds.width + EPSILON >= transform.viewportSize.width)
        assertTrue(transform.transformedBounds.height + EPSILON >= transform.viewportSize.height)
        assertClose(transform.viewportSize.width / 2f, transform.transformedBounds.centerX)
        assertClose(transform.viewportSize.height / 2f, transform.transformedBounds.centerY)
    }

    private fun transform(
        buffer: PreviewBufferSize,
        viewport: PreviewViewportSize,
        sensor: Int,
        display: Int,
        facing: CameraFacing = CameraFacing.BACK,
        mirrorOwner: PreviewMirrorOwner = PreviewMirrorOwner.OUTPUT_CONFIGURATION_AUTO,
    ) = PreviewTransformCalculator.calculate(
        geometry = PreviewGeometry(
            bufferSize = buffer,
            sensorOrientation = PreviewRotation.fromDegrees(sensor),
            displayRotation = PreviewRotation.fromDegrees(display),
            cameraFacing = facing,
            mirrorOwner = mirrorOwner,
        ),
        viewportSize = viewport,
    )

    private fun determinant(matrix: PreviewMatrix): Float =
        matrix.scaleX * matrix.scaleY - matrix.skewX * matrix.skewY

    private fun vectorLength(point: PreviewPoint): Float = sqrt(point.x * point.x + point.y * point.y)

    private fun distance(first: PreviewPoint, second: PreviewPoint): Float = vectorLength(
        PreviewPoint(second.x - first.x, second.y - first.y),
    )

    private fun assertPointClose(expected: PreviewPoint, actual: PreviewPoint) {
        assertClose(expected.x, actual.x)
        assertClose(expected.y, actual.y)
    }

    private fun assertMatrixClose(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedValue, actualValue) ->
            assertClose(expectedValue, actualValue)
        }
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("Expected $expected, actual $actual", abs(expected - actual) < EPSILON)
    }

    companion object {
        private const val EPSILON = 0.01f
    }
}
