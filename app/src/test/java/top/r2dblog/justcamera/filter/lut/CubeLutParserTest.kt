package top.r2dblog.justcamera.filter.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutParserTest {
    @Test
    fun parsesWhitespaceCommentsTitleAndDomain() {
        val result = CubeLutParser.parse(
            """
                # identity with custom domain
                TITLE "Tiny Identity"
                DOMAIN_MIN   -1 -1 -1
                DOMAIN_MAX 1 1 1
                LUT_3D_SIZE 2

                0 0 0
                1 0 0 # red corner
                0 1 0
                1 1 0
                0 0 1
                1 0 1
                0 1 1
                1 1 1
            """.trimIndent(),
        )

        assertTrue(result is CubeParseResult.Success)
        result as CubeParseResult.Success
        assertEquals("Tiny Identity", result.document.title)
        val lut = result.document.lut as Lut3D
        assertEquals(LutDomain(-1f, -1f, -1f), lut.domainMin)
        assertEquals(LutDomain(1f, 1f, 1f), lut.domainMax)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), lut.sample(0f, 0f, 0f), EPSILON)
    }

    @Test
    fun parsesOneDimensionalCube() {
        val result = CubeLutParser.parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1")
        assertTrue(result is CubeParseResult.Success)
        val lut = (result as CubeParseResult.Success).document.lut as Lut1D
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f), lut.sample(0.25f, 0.5f, 0.75f), EPSILON)
    }

    @Test
    fun rejectsMalformedSampleCountAndInvalidSizeWithUsefulErrors() {
        val count = CubeLutParser.parse("LUT_3D_SIZE 2\n0 0 0") as CubeParseResult.Failure
        assertTrue(count.errors.any { "Expected 8" in it.message })

        val size = CubeLutParser.parse("LUT_3D_SIZE 1\n0 0 0") as CubeParseResult.Failure
        assertTrue(size.errors.any { it.line == 1 && "2..65" in it.message })
    }

    private companion object { const val EPSILON = 0.0001f }
}
