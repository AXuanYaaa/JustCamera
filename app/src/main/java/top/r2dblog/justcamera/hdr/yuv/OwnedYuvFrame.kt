package top.r2dblog.justcamera.hdr.yuv

class OwnedYuvPlane private constructor(
    data: ByteArray,
    val rowStrideBytes: Int,
    val pixelStrideBytes: Int,
    copyData: Boolean,
) {
    private val bytes = if (copyData) data.copyOf() else data

    init {
        require(rowStrideBytes > 0 && pixelStrideBytes > 0)
        require(bytes.isNotEmpty())
    }

    val byteCount: Int get() = bytes.size

    fun byteAt(index: Int): Int {
        require(index in bytes.indices) { "YUV plane index $index outside ${bytes.size}" }
        return bytes[index].toInt() and 0xff
    }

    fun copyBytes(): ByteArray = bytes.copyOf()

    companion object {
        fun create(data: ByteArray, rowStrideBytes: Int, pixelStrideBytes: Int) =
            OwnedYuvPlane(data, rowStrideBytes, pixelStrideBytes, copyData = true)

        internal fun fromOwned(data: ByteArray, rowStrideBytes: Int, pixelStrideBytes: Int) =
            OwnedYuvPlane(data, rowStrideBytes, pixelStrideBytes, copyData = false)
    }
}

class OwnedYuvFrame private constructor(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val y: OwnedYuvPlane,
    val u: OwnedYuvPlane,
    val v: OwnedYuvPlane,
) {
    init {
        require(width > 0 && height > 0)
        require(timestampNanos >= 0)
        validatePlane(y, width, height, "Y")
        validatePlane(u, (width + 1) / 2, (height + 1) / 2, "U")
        validatePlane(v, (width + 1) / 2, (height + 1) / 2, "V")
    }

    companion object {
        fun create(
            width: Int,
            height: Int,
            timestampNanos: Long,
            y: OwnedYuvPlane,
            u: OwnedYuvPlane,
            v: OwnedYuvPlane,
        ) = OwnedYuvFrame(width, height, timestampNanos, y, u, v)

        internal fun fromOwned(
            width: Int,
            height: Int,
            timestampNanos: Long,
            y: OwnedYuvPlane,
            u: OwnedYuvPlane,
            v: OwnedYuvPlane,
        ) = OwnedYuvFrame(width, height, timestampNanos, y, u, v)

        private fun validatePlane(
            plane: OwnedYuvPlane,
            planeWidth: Int,
            planeHeight: Int,
            name: String,
        ) {
            val lastIndex = (planeHeight - 1L) * plane.rowStrideBytes +
                (planeWidth - 1L) * plane.pixelStrideBytes
            require(lastIndex < plane.byteCount) {
                "$name plane is undersized: lastIndex=$lastIndex bytes=${plane.byteCount}"
            }
        }
    }
}
