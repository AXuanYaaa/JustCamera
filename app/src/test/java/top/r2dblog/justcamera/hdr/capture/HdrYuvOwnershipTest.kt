package top.r2dblog.justcamera.hdr.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdrYuvOwnershipTest {
    @Test
    fun retirementDefersReaderCloseUntilLastCopyLeaseReleases() {
        val ownership = HdrYuvOwnership()
        ownership.register(4)
        val first = ownership.acquire(4)!!
        val second = ownership.acquire(4)!!

        assertEquals(HdrYuvReaderRelease.KEEP_READER, ownership.retire(4))
        assertNull(ownership.acquire(4))
        assertEquals(HdrYuvReaderRelease.KEEP_READER, ownership.release(first))
        assertEquals(HdrYuvReaderRelease.CLOSE_READER, ownership.release(second))
        assertEquals(0, ownership.inFlightCount(4))
    }
}
