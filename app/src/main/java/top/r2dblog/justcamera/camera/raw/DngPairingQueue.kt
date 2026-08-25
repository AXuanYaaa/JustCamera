package top.r2dblog.justcamera.camera.raw

data class DngPair<I, R>(val timestampNanos: Long, val image: I, val result: R)

data class DngPairingUpdate<I, R>(
    val ready: List<DngPair<I, R>> = emptyList(),
    val discardedImages: List<I> = emptyList(),
)

/**
 * Thread-safe, bounded timestamp pairing for RAW images and capture results.
 *
 * The caller owns every image passed in. Images returned as ready transfer to the DNG writer;
 * images returned as discarded must be closed immediately by the caller.
 */
class DngPairingQueue<I, R>(
    private val maxPendingTimestamps: Int = 4,
    private val maxAgeNanos: Long = 5_000_000_000L,
) {
    private val images = linkedMapOf<Long, I>()
    private val results = linkedMapOf<Long, R>()
    private var latestTimestamp = Long.MIN_VALUE

    init {
        require(maxPendingTimestamps > 0)
        require(maxAgeNanos >= 0)
    }

    @Synchronized
    fun offerImage(timestampNanos: Long, image: I): DngPairingUpdate<I, R> {
        latestTimestamp = maxOf(latestTimestamp, timestampNanos)
        val result = results.remove(timestampNanos)
        if (result != null) {
            val discarded = evict()
            return DngPairingUpdate(
                ready = listOf(DngPair(timestampNanos, image, result)),
                discardedImages = discarded,
            )
        }
        val replaced = images.put(timestampNanos, image)
        return DngPairingUpdate(
            discardedImages = buildList {
                if (replaced != null) add(replaced)
                addAll(evict())
            },
        )
    }

    @Synchronized
    fun offerResult(timestampNanos: Long, result: R): DngPairingUpdate<I, R> {
        latestTimestamp = maxOf(latestTimestamp, timestampNanos)
        val image = images.remove(timestampNanos)
        if (image != null) {
            return DngPairingUpdate(
                ready = listOf(DngPair(timestampNanos, image, result)),
                discardedImages = evict(),
            )
        }
        results[timestampNanos] = result
        return DngPairingUpdate(discardedImages = evict())
    }

    @Synchronized
    fun clear(): List<I> = images.values.toList().also {
        images.clear()
        results.clear()
        latestTimestamp = Long.MIN_VALUE
    }

    @Synchronized
    fun pendingTimestampCount(): Int = (images.keys + results.keys).toSet().size

    private fun evict(): List<I> {
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
