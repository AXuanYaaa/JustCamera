package top.r2dblog.justcamera.camera.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory
import java.io.File
import java.io.IOException

data class SavedMedia(val uri: Uri, val displayName: String)

class MediaStoreJpegSaver(private val context: Context) {
    suspend fun save(bytes: ByteArray, displayName: String): Result<SavedMedia> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var insertedUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DCIM}/JustCamera",
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val directory = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM,
                            ),
                            "JustCamera",
                        )
                        if (!directory.exists() && !directory.mkdirs()) {
                            throw IOException("Unable to create ${directory.absolutePath}")
                        }
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.DATA, File(directory, displayName).absolutePath)
                    }
                }

                insertedUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: throw IOException("MediaStore rejected the new image")

                resolver.openOutputStream(insertedUri, "w")?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                } ?: throw IOException("Unable to open MediaStore output stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishedRows = resolver.update(
                        insertedUri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    if (publishedRows != 1) {
                        throw IOException(
                            "MediaStore failed to publish $displayName (updated=$publishedRows)",
                        )
                    }
                }
                JcLog.info(LogCategory.STORAGE, "Saved $displayName to MediaStore")
                Result.success(SavedMedia(insertedUri, displayName))
            } catch (error: IOException) {
                insertedUri?.let(::cleanupFailedInsert)
                JcLog.error(LogCategory.STORAGE, "JPEG save failed", error)
                Result.failure(error)
            } catch (error: SecurityException) {
                insertedUri?.let(::cleanupFailedInsert)
                JcLog.error(LogCategory.STORAGE, "MediaStore permission denied", error)
                Result.failure(error)
            } catch (error: IllegalArgumentException) {
                insertedUri?.let(::cleanupFailedInsert)
                JcLog.error(LogCategory.STORAGE, "MediaStore rejected the JPEG item", error)
                Result.failure(error)
            }
        }

    private fun cleanupFailedInsert(uri: Uri) {
        try {
            val deletedRows = context.contentResolver.delete(uri, null, null)
            if (deletedRows != 1) {
                JcLog.warn(
                    LogCategory.STORAGE,
                    "MediaStore cleanup did not delete the failed item (deleted=$deletedRows)",
                )
            }
        } catch (error: SecurityException) {
            JcLog.warn(LogCategory.STORAGE, "Permission denied while cleaning failed item", error)
        } catch (error: IllegalArgumentException) {
            JcLog.warn(LogCategory.STORAGE, "MediaStore rejected failed-item cleanup", error)
        }
    }
}
