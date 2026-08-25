package top.r2dblog.justcamera.hdr.capture

class HdrYuvLeaseToken internal constructor(val generation: Long, internal val leaseId: Long)

enum class HdrYuvReaderRelease { KEEP_READER, CLOSE_READER }

/** Pure lifecycle state preventing ImageReader close while a YUV Image is being copied. */
class HdrYuvOwnership {
    private data class ReaderState(
        var retired: Boolean = false,
        val leases: MutableSet<Long> = mutableSetOf(),
    )

    private val readers = mutableMapOf<Long, ReaderState>()
    private var nextLeaseId = 0L

    @Synchronized
    fun register(generation: Long) {
        require(generation !in readers) { "HDR YUV generation $generation is already registered" }
        readers[generation] = ReaderState()
    }

    @Synchronized
    fun acquire(generation: Long): HdrYuvLeaseToken? {
        val state = readers[generation]?.takeUnless { it.retired } ?: return null
        return HdrYuvLeaseToken(generation, ++nextLeaseId).also { state.leases += it.leaseId }
    }

    @Synchronized
    fun retire(generation: Long): HdrYuvReaderRelease {
        val state = readers[generation] ?: return HdrYuvReaderRelease.CLOSE_READER
        state.retired = true
        return if (state.leases.isEmpty()) {
            readers.remove(generation)
            HdrYuvReaderRelease.CLOSE_READER
        } else {
            HdrYuvReaderRelease.KEEP_READER
        }
    }

    @Synchronized
    fun release(token: HdrYuvLeaseToken): HdrYuvReaderRelease {
        val state = readers[token.generation] ?: return HdrYuvReaderRelease.KEEP_READER
        if (!state.leases.remove(token.leaseId)) return HdrYuvReaderRelease.KEEP_READER
        return if (state.retired && state.leases.isEmpty()) {
            readers.remove(token.generation)
            HdrYuvReaderRelease.CLOSE_READER
        } else {
            HdrYuvReaderRelease.KEEP_READER
        }
    }

    @Synchronized
    fun inFlightCount(generation: Long): Int = readers[generation]?.leases?.size ?: 0
}
