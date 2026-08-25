package top.r2dblog.justcamera.hdr.capture

data class HdrFramePair<I, R>(val timestampNanos: Long, val image: I, val result: R)

data class HdrPairingUpdate<I, R>(
    val ready: List<HdrFramePair<I, R>> = emptyList(),
    val discardedImages: List<I> = emptyList(),
    val staleGenerationIgnored: Boolean = false,
)

/** HDR-specific, generation-aware, bounded image/result timestamp pairing. */
class HdrFramePairingQueue<I, R>(
    private val maxPendingTimestamps: Int = 8,
    private val maxAgeNanos: Long = 5_000_000_000L,
) {
    private val images = linkedMapOf<Long, I>()
    private val results = linkedMapOf<Long, R>()
    private var activeGeneration: Long? = null
    private var latestTimestamp = Long.MIN_VALUE

    init {
        require(maxPendingTimestamps > 0)
        require(maxAgeNanos >= 0)
    }

    @Synchronized
    fun begin(generation: Long): List<I> = clearLocked().also {
        activeGeneration = generation
    }

    @Synchronized
    fun offerImage(generation: Long, timestampNanos: Long, image: I): HdrPairingUpdate<I, R> {
        if (generation != activeGeneration) {
            return HdrPairingUpdate(discardedImages = listOf(image), staleGenerationIgnored = true)
        }
        latestTimestamp = maxOf(latestTimestamp, timestampNanos)
        val result = results.remove(timestampNanos)
        if (result != null) {
            return HdrPairingUpdate(
                ready = listOf(HdrFramePair(timestampNanos, image, result)),
                discardedImages = evictLocked(),
            )
        }
        val replaced = images.put(timestampNanos, image)
        return HdrPairingUpdate(
            discardedImages = buildList {
                if (replaced != null) add(replaced)
                addAll(evictLocked())
            },
        )
    }

    @Synchronized
    fun offerResult(generation: Long, timestampNanos: Long, result: R): HdrPairingUpdate<I, R> {
        if (generation != activeGeneration) return HdrPairingUpdate(staleGenerationIgnored = true)
        latestTimestamp = maxOf(latestTimestamp, timestampNanos)
        val image = images.remove(timestampNanos)
        if (image != null) {
            return HdrPairingUpdate(
                ready = listOf(HdrFramePair(timestampNanos, image, result)),
                discardedImages = evictLocked(),
            )
        }
        results[timestampNanos] = result
        return HdrPairingUpdate(discardedImages = evictLocked())
    }

    @Synchronized
    fun cancel(): List<I> = clearLocked().also { activeGeneration = null }

    /** Advances the pairing clock so an external capture timeout can reclaim unpaired images. */
    @Synchronized
    fun expireThrough(timestampNanos: Long): HdrPairingUpdate<I, R> {
        latestTimestamp = maxOf(latestTimestamp, timestampNanos)
        return HdrPairingUpdate(discardedImages = evictLocked())
    }

    @Synchronized
    fun pendingTimestampCount(): Int = (images.keys + results.keys).toSet().size

    private fun clearLocked(): List<I> = images.values.toList().also {
        images.clear()
        results.clear()
        latestTimestamp = Long.MIN_VALUE
    }

    private fun evictLocked(): List<I> {
        val discarded = mutableListOf<I>()
        val staleBefore = latestTimestamp - maxAgeNanos
        images.keys.filter { it < staleBefore }.forEach { timestamp ->
            images.remove(timestamp)?.let(discarded::add)
        }
        results.keys.removeAll { it < staleBefore }
        while ((images.keys + results.keys).toSet().size > maxPendingTimestamps) {
            val oldest = (images.keys + results.keys).minOrNull() ?: break
            images.remove(oldest)?.let(discarded::add)
            results.remove(oldest)
        }
        return discarded
    }
}
