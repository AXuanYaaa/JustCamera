package top.r2dblog.justcamera.hdr.yuv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame
import top.r2dblog.justcamera.imaging.color.ColorTransfer

/**
 * Camera2's default JFIF/Rec.601 full-range YUV_420_888 transform to encoded sRGB, followed by
 * inverse sRGB transfer into the ISP-derived linear-sRGB HDR input domain.
 */
object Yuv420Converter {
    suspend fun toSceneLinear(input: OwnedYuvFrame): SceneLinearFrame {
        val output = FloatArray(input.width * input.height * 3)
        var outputOffset = 0
        for (row in 0 until input.height) {
            currentCoroutineContext().ensureActive()
            val chromaRow = row / 2
            for (column in 0 until input.width) {
                val chromaColumn = column / 2
                val y = input.y.byteAt(row * input.y.rowStrideBytes + column * input.y.pixelStrideBytes)
                val u = input.u.byteAt(
                    chromaRow * input.u.rowStrideBytes + chromaColumn * input.u.pixelStrideBytes,
                )
                val v = input.v.byteAt(
                    chromaRow * input.v.rowStrideBytes + chromaColumn * input.v.pixelStrideBytes,
                )
                val luminance = y / BYTE_RANGE
                val cb = (u - CHROMA_CENTER) / BYTE_RANGE
                val cr = (v - CHROMA_CENTER) / BYTE_RANGE
                output[outputOffset++] = ColorTransfer.srgbToLinear(
                    (luminance + RED_CR * cr).coerceIn(0f, 1f),
                )
                output[outputOffset++] = ColorTransfer.srgbToLinear(
                    (luminance - GREEN_CB * cb - GREEN_CR * cr).coerceIn(0f, 1f),
                )
                output[outputOffset++] = ColorTransfer.srgbToLinear(
                    (luminance + BLUE_CB * cb).coerceIn(0f, 1f),
                )
            }
        }
        return SceneLinearFrame.fromOwnedSamples(
            input.width,
            input.height,
            output,
            timestampNanos = input.timestampNanos,
        )
    }

    /** Returns encoded sRGB before inverse transfer; exposed internally for formula tests. */
    internal fun jfifRec601FullRange(y: Int, u: Int, v: Int): FloatArray {
        require(y in 0..255 && u in 0..255 && v in 0..255)
        val luminance = y / BYTE_RANGE
        val cb = (u - CHROMA_CENTER) / BYTE_RANGE
        val cr = (v - CHROMA_CENTER) / BYTE_RANGE
        return floatArrayOf(
            (luminance + RED_CR * cr).coerceIn(0f, 1f),
            (luminance - GREEN_CB * cb - GREEN_CR * cr).coerceIn(0f, 1f),
            (luminance + BLUE_CB * cb).coerceIn(0f, 1f),
        )
    }

    private const val BYTE_RANGE = 255f
    private const val CHROMA_CENTER = 128f
    private const val RED_CR = 1.402f
    private const val GREEN_CB = 0.344136f
    private const val GREEN_CR = 0.714136f
    private const val BLUE_CB = 1.772f
}
