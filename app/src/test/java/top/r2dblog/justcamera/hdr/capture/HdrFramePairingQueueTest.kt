package top.r2dblog.justcamera.hdr.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrFramePairingQueueTest {
    @Test
    fun pairsImageFirstAndResultFirstByExactTimestamp() {
        val queue = HdrFramePairingQueue<String, String>()
        queue.begin(7)

        queue.offerImage(7, 20, "image-20")
        val imageFirst = queue.offerResult(7, 20, "result-20")
        queue.offerResult(7, 10, "result-10")
        val resultFirst = queue.offerImage(7, 10, "image-10")

        assertEquals("image-20", imageFirst.ready.single().image)
        assertEquals("result-10", resultFirst.ready.single().result)
        assertEquals(0, queue.pendingTimestampCount())
    }

    @Test
    fun replacesDuplicateImageAndDiscardsStaleGenerations() {
        val queue = HdrFramePairingQueue<String, String>()
        queue.begin(2)
        queue.offerImage(2, 1, "first")
        val duplicate = queue.offerImage(2, 1, "replacement")
        val stale = queue.offerImage(1, 2, "stale")

        assertEquals(listOf("first"), duplicate.discardedImages)
        assertEquals(listOf("stale"), stale.discardedImages)
        assertTrue(stale.staleGenerationIgnored)
    }

    @Test
    fun ageAndCountBoundsEvictOldImagesAndCancelReturnsOwnership() {
        val queue = HdrFramePairingQueue<String, String>(maxPendingTimestamps = 2, maxAgeNanos = 5)
        queue.begin(1)
        queue.offerImage(1, 1, "old")
        val aged = queue.offerResult(1, 10, "new-result")
        queue.offerImage(1, 11, "new-image")
        queue.offerResult(1, 12, "newer-result")

        assertEquals(listOf("old"), aged.discardedImages)
        assertTrue(queue.pendingTimestampCount() <= 2)
        assertEquals(listOf("new-image"), queue.cancel())
    }

    @Test
    fun explicitTimeoutClockReclaimsUnpairedImage() {
        val queue = HdrFramePairingQueue<String, String>(maxAgeNanos = 10)
        queue.begin(3)
        queue.offerImage(3, 100, "timed-out")

        val expired = queue.expireThrough(111)

        assertEquals(listOf("timed-out"), expired.discardedImages)
        assertEquals(0, queue.pendingTimestampCount())
    }
}
