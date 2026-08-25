package top.r2dblog.justcamera.camera.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image

/** Encodes the supplied image but leaves Image.close() to the caller's ownership boundary. */
internal class MediaStoreDngSaver(context: Context) {
    private val outputStore = MediaStoreOutputStore(context)

    suspend fun save(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        displayName: String,
    ): Result<SavedMedia> = outputStore.save(displayName, DNG_MIME_TYPE) { stream ->
        DngCreator(characteristics, result).use { creator ->
            creator.writeImage(stream, image)
        }
    }

    private companion object {
        const val DNG_MIME_TYPE = "image/x-adobe-dng"
    }
}
