package top.r2dblog.justcamera.hdr.yuv

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.r2dblog.justcamera.hdr.model.SceneLinearFrame
import top.r2dblog.justcamera.imaging.color.ColorTransfer

/** BT.601 limited-range YUV_420_888 to encoded sRGB, followed by the PH3 inverse sRGB transfer. */
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
                val luminance = ((y - 16f) / 219f).coerceIn(0f, 1f)
                val cb = (u - 128f) / 224f
                val cr = (v - 128f) / 224f
                output[outputOffset++] = ColorTransfer.srgbToLinear(
                    (luminance + 1.402f * cr).coerceIn(0f, 1f),
                )
                output[outputOffset++] = ColorTransfer.srgbToLinear(
                    (luminance - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 1f),
                )
                output[outputOffset++] = ColorTransfer.srgbToLinear(
                    (luminance + 1.772f * cb).coerceIn(0f, 1f),
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

    internal fun limitedBt601(y: Int, u: Int, v: Int): FloatArray {
        require(y in 0..255 && u in 0..255 && v in 0..255)
        val luminance = ((y - 16f) / 219f).coerceIn(0f, 1f)
        val cb = (u - 128f) / 224f
        val cr = (v - 128f) / 224f
        return floatArrayOf(
            (luminance + 1.402f * cr).coerceIn(0f, 1f),
            (luminance - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 1f),
            (luminance + 1.772f * cb).coerceIn(0f, 1f),
        )
    }
}
