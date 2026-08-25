package top.r2dblog.justcamera.camera.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawImageOwnershipTest {
    @Test
    fun lifecycleCloseDefersReaderUntilPendingDngLeaseFinishes() {
        val ownership = RawImageOwnership()
        ownership.register(generation = 7)
        val lease = requireNotNull(ownership.acquire(generation = 7))

        assertEquals(RawReaderRelease.KEEP_READER, ownership.retire(generation = 7))
        assertEquals(RawReaderRelease.KEEP_READER, ownership.retire(generation = 7))
        assertTrue(ownership.isRegistered(generation = 7))
        assertEquals(1, ownership.inFlightCount(generation = 7))

        assertEquals(RawReaderRelease.CLOSE_READER, ownership.release(lease))
        assertFalse(ownership.isRegistered(generation = 7))
    }

    @Test
    fun finalInflightImageIsTheOnlyLeaseThatClosesRetiredReader() {
        val ownership = RawImageOwnership()
        ownership.register(generation = 3)
        val first = requireNotNull(ownership.acquire(generation = 3))
        val second = requireNotNull(ownership.acquire(generation = 3))

        assertEquals(RawReaderRelease.KEEP_READER, ownership.retire(generation = 3))
        assertEquals(RawReaderRelease.KEEP_READER, ownership.release(first))
        assertEquals(RawReaderRelease.CLOSE_READER, ownership.release(second))
        assertEquals(RawReaderRelease.KEEP_READER, ownership.release(second))
    }

    @Test
    fun retiredGenerationRejectsStaleImageOwnership() {
        val ownership = RawImageOwnership()
        ownership.register(generation = 11)

        assertEquals(RawReaderRelease.CLOSE_READER, ownership.retire(generation = 11))
        assertNull(ownership.acquire(generation = 11))
    }
}
