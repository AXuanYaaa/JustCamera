package top.r2dblog.justcamera.camera.hdr

import android.media.Image
import android.media.ImageReader
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import top.r2dblog.justcamera.hdr.capture.HdrYuvLeaseToken
import top.r2dblog.justcamera.hdr.capture.HdrYuvOwnership
import top.r2dblog.justcamera.hdr.capture.HdrYuvReaderRelease

internal class HdrYuvImageLeaseRegistry {
    private val lock = Any()
    private val ownership = HdrYuvOwnership()
    private val generationByReader = IdentityHashMap<ImageReader, Long>()
    private val readerByGeneration = mutableMapOf<Long, ImageReader>()

    fun register(reader: ImageReader, generation: Long) {
        synchronized(lock) {
            check(reader !in generationByReader)
            ownership.register(generation)
            generationByReader[reader] = generation
            readerByGeneration[generation] = reader
        }
    }

    fun transfer(reader: ImageReader, image: Image, callbackGeneration: Long): HdrYuvImageLease? {
        val token = synchronized(lock) {
            generationByReader[reader]?.takeIf { it == callbackGeneration }
                ?.let(ownership::acquire)
        }
        if (token == null) {
            image.close()
            return null
        }
        return HdrYuvImageLease(image, token, this)
    }

    fun retire(reader: ImageReader) {
        var close: ImageReader? = null
        synchronized(lock) {
            val generation = generationByReader[reader]
            if (generation == null) {
                close = reader
            } else if (ownership.retire(generation) == HdrYuvReaderRelease.CLOSE_READER) {
                readerByGeneration.remove(generation)
                generationByReader.remove(reader)
                close = reader
            }
        }
        close?.close()
    }

    private fun release(token: HdrYuvLeaseToken) {
        var close: ImageReader? = null
        synchronized(lock) {
            if (ownership.release(token) == HdrYuvReaderRelease.CLOSE_READER) {
                close = readerByGeneration.remove(token.generation)
                close?.let { generationByReader.remove(it) }
            }
        }
        close?.close()
    }

    internal class HdrYuvImageLease(
        val image: Image,
        private val token: HdrYuvLeaseToken,
        private val registry: HdrYuvImageLeaseRegistry,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                image.close()
            } finally {
                registry.release(token)
            }
        }
    }
}
