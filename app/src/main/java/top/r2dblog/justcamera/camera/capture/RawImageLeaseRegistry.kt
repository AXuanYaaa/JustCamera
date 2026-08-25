package top.r2dblog.justcamera.camera.capture

import android.media.Image
import android.media.ImageReader
import top.r2dblog.justcamera.camera.raw.RawImageLeaseToken
import top.r2dblog.justcamera.camera.raw.RawImageOwnership
import top.r2dblog.justcamera.camera.raw.RawReaderRelease
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges the pure RAW ownership state to Android Image/ImageReader resources. */
internal class RawImageLeaseRegistry {
    private val lock = Any()
    private val ownership = RawImageOwnership()
    private val generationByReader = IdentityHashMap<ImageReader, Long>()
    private val readerByGeneration = mutableMapOf<Long, ImageReader>()

    fun register(reader: ImageReader, generation: Long) {
        synchronized(lock) {
            check(reader !in generationByReader) { "RAW ImageReader is already registered" }
            ownership.register(generation)
            generationByReader[reader] = generation
            readerByGeneration[generation] = reader
        }
    }

    fun transfer(
        reader: ImageReader,
        image: Image,
        callbackGeneration: Long,
    ): RawImageLease? {
        val token = synchronized(lock) {
            val generation = generationByReader[reader]
            if (generation != callbackGeneration) null else ownership.acquire(generation)
        }
        if (token == null) {
            image.close()
            return null
        }

        val timestamp = try {
            image.timestamp
        } catch (error: RuntimeException) {
            image.close()
            release(token)
            throw error
        }
        return RawImageLease(image, timestamp, token, this)
    }

    /**
     * Logically detaches a reader immediately. Physical close is deferred only while leased RAW
     * images from that reader are being consumed by DngCreator.
     */
    fun retire(reader: ImageReader) {
        var readerToClose: ImageReader? = null
        synchronized(lock) {
            val generation = generationByReader[reader]
            if (generation == null) {
                readerToClose = reader
            } else if (ownership.retire(generation) == RawReaderRelease.CLOSE_READER) {
                readerByGeneration.remove(generation)
                generationByReader.remove(reader)
                readerToClose = reader
            }
        }
        readerToClose?.close()
    }

    private fun release(token: RawImageLeaseToken) {
        var readerToClose: ImageReader? = null
        synchronized(lock) {
            if (ownership.release(token) == RawReaderRelease.CLOSE_READER) {
                readerToClose = readerByGeneration.remove(token.generation)
                readerToClose?.let { generationByReader.remove(it) }
            }
        }
        readerToClose?.close()
    }

    internal class RawImageLease(
        val image: Image,
        val timestampNanos: Long,
        val generation: Long,
        private val token: RawImageLeaseToken,
        private val registry: RawImageLeaseRegistry,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        constructor(
            image: Image,
            timestampNanos: Long,
            token: RawImageLeaseToken,
            registry: RawImageLeaseRegistry,
        ) : this(image, timestampNanos, token.generation, token, registry)

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
