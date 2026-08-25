package top.r2dblog.justcamera.camera.raw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTopologyFallbackPolicyTest {
    @Test
    fun transientCameraAccessAndLifecycleFailuresPreserveRaw() {
        val policy = RawTopologyFallbackPolicy()
        policy.select("rear")

        assertFalse(policy.record("rear", RawTopologyFailure.TRANSIENT_CAMERA_ACCESS))
        assertFalse(policy.record("rear", RawTopologyFailure.LIFECYCLE_INTERRUPTION))
        assertFalse(policy.isDisabled("rear"))
    }

    @Test
    fun explicitStreamCombinationRejectionDisablesRawForSelection() {
        val policy = RawTopologyFallbackPolicy()
        policy.select("rear")

        assertTrue(policy.record("rear", RawTopologyFailure.CONFIGURATION_REJECTED))
        assertTrue(policy.isDisabled("rear"))
    }

    @Test
    fun switchingAwayAndBackStartsANewRawSelectionTenure() {
        val policy = RawTopologyFallbackPolicy()
        policy.select("rear")
        policy.record("rear", RawTopologyFailure.OUTPUT_COMBINATION_REJECTED)

        policy.select("front")
        policy.select("rear")

        assertFalse(policy.isDisabled("rear"))
    }
}
