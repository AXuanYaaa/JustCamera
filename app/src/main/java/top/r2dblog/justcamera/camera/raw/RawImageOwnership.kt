package top.r2dblog.justcamera.camera.raw

class RawImageLeaseToken internal constructor(
    val generation: Long,
    internal val leaseId: Long,
)

enum class RawReaderRelease {
    KEEP_READER,
    CLOSE_READER,
}

/**
 * Pure state for the RAW reader/image ownership invariant.
 *
 * A registered reader may be retired immediately, but it may only be physically closed after
 * every lease acquired from it has been released. Retired generations reject new leases.
 */
class RawImageOwnership {
    private data class ReaderState(
        var retired: Boolean = false,
        val leases: MutableSet<Long> = mutableSetOf(),
    )

    private val readers = mutableMapOf<Long, ReaderState>()
    private var nextLeaseId = 0L

    @Synchronized
    fun register(generation: Long) {
        require(generation !in readers) { "RAW reader generation $generation is already registered" }
        readers[generation] = ReaderState()
    }

    @Synchronized
    fun acquire(generation: Long): RawImageLeaseToken? {
        val state = readers[generation]?.takeUnless { it.retired } ?: return null
        return RawImageLeaseToken(generation, ++nextLeaseId).also {
            state.leases += it.leaseId
        }
    }

    @Synchronized
    fun retire(generation: Long): RawReaderRelease {
        val state = readers[generation] ?: return RawReaderRelease.CLOSE_READER
        state.retired = true
        return if (state.leases.isEmpty()) {
            readers.remove(generation)
            RawReaderRelease.CLOSE_READER
        } else {
            RawReaderRelease.KEEP_READER
        }
    }

    @Synchronized
    fun release(token: RawImageLeaseToken): RawReaderRelease {
        val state = readers[token.generation] ?: return RawReaderRelease.KEEP_READER
        if (!state.leases.remove(token.leaseId)) return RawReaderRelease.KEEP_READER
        return if (state.retired && state.leases.isEmpty()) {
            readers.remove(token.generation)
            RawReaderRelease.CLOSE_READER
        } else {
            RawReaderRelease.KEEP_READER
        }
    }

    @Synchronized
    fun inFlightCount(generation: Long): Int = readers[generation]?.leases?.size ?: 0

    @Synchronized
    fun isRegistered(generation: Long): Boolean = generation in readers
}
