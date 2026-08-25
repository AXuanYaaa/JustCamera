package top.r2dblog.justcamera.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DngPairingQueueTest {
    @Test
    fun pairsWhenResultOrImageArrivesFirst() {
        val resultFirst = DngPairingQueue<String, String>()
        assertTrue(resultFirst.offerResult(10, "result").ready.isEmpty())
        assertEquals(
            DngPair(10, "image", "result"),
            resultFirst.offerImage(10, "image").ready.single(),
        )

        val imageFirst = DngPairingQueue<String, String>()
        assertTrue(imageFirst.offerImage(20, "image").ready.isEmpty())
        assertEquals(
            DngPair(20, "image", "result"),
            imageFirst.offerResult(20, "result").ready.single(),
        )
    }

    @Test
    fun evictsStaleImagesAndKeepsQueueBounded() {
        val queue = DngPairingQueue<String, String>(maxPendingTimestamps = 2, maxAgeNanos = 5)
        queue.offerImage(1, "stale")
        queue.offerResult(2, "orphan-result")
        val update = queue.offerImage(10, "new")
        assertEquals(listOf("stale"), update.discardedImages)
        assertTrue(queue.pendingTimestampCount() <= 2)
    }

    @Test
    fun clearReturnsEveryUnpairedImageForClosing() {
        val queue = DngPairingQueue<String, String>()
        queue.offerImage(1, "one")
        queue.offerImage(2, "two")
        assertEquals(listOf("one", "two"), queue.clear())
        assertEquals(0, queue.pendingTimestampCount())
    }
}
