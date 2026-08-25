package top.r2dblog.justcamera.camera.hdr

import android.graphics.ImageFormat
import android.media.Image
import top.r2dblog.justcamera.hdr.yuv.OwnedYuvFrame
import top.r2dblog.justcamera.hdr.yuv.OwnedYuvPlane

internal object YuvImageCopier {
    fun copy(image: Image): OwnedYuvFrame {
        require(image.format == ImageFormat.YUV_420_888) { "HDR input must be YUV_420_888" }
        require(image.planes.size == 3) { "YUV_420_888 must expose three planes" }
        val planes = image.planes.map { plane ->
            val source = plane.buffer.duplicate()
            val bytes = ByteArray(source.remaining())
            source.get(bytes)
            OwnedYuvPlane.fromOwned(bytes, plane.rowStride, plane.pixelStride)
        }
        return OwnedYuvFrame.fromOwned(
            image.width,
            image.height,
            image.timestamp,
            planes[0],
            planes[1],
            planes[2],
        )
    }
}
