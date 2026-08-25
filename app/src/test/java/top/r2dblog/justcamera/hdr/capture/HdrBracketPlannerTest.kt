package top.r2dblog.justcamera.hdr.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.r2dblog.justcamera.camera.model.ValueRange

class HdrBracketPlannerTest {
    private val constraints = HdrBracketConstraints(
        manualSensor = true,
        sensitivityRange = ValueRange(100, 1600),
        exposureTimeRangeNanos = ValueRange(100_000L, 100_000_000L),
        maxFrameDurationNanos = 100_000_000L,
    )

    @Test
    fun plansOrderedThreeFrameBracketWithStableReference() {
        val result = HdrBracketPlanner.plan(
            HdrExposureBaseline(10_000_000L, 200, 33_333_333L),
            constraints,
        ) as HdrBracketPlanningResult.Planned

        assertEquals(listOf(-2.0, 0.0, 2.0), result.plan.entries.map { it.requestedEv })
        result.plan.entries.zip(listOf(-2.0, 0.0, 2.0)).forEach { (entry, expected) ->
            assertEquals(expected, entry.actualPlannedEv, 0.001)
        }
        assertEquals(1, result.plan.referenceFrameIndex)
        assertTrue(result.plan.entries.all { it.frameDurationNanos >= it.exposureTimeNanos })
        assertTrue(result.plan.entries.zipWithNext().all { (a, b) ->
            a.exposureTimeNanos.toDouble() * a.sensitivityIso <
                b.exposureTimeNanos.toDouble() * b.sensitivityIso
        })
    }

    @Test
    fun clampsExposureAndIsoToSensorAndFrameDurationLimits() {
        val limited = constraints.copy(
            sensitivityRange = ValueRange(100, 400),
            exposureTimeRangeNanos = ValueRange(1_000_000L, 20_000_000L),
            maxFrameDurationNanos = 15_000_000L,
            minimumFrameDurationNanos = 12_000_000L,
        )
        val result = HdrBracketPlanner.plan(
            HdrExposureBaseline(12_000_000L, 400),
            limited,
        ) as HdrBracketPlanningResult.Planned

        assertTrue(result.plan.entries.all { it.exposureTimeNanos in 1_000_000L..15_000_000L })
        assertTrue(result.plan.entries.all { it.sensitivityIso in 100..400 })
        assertTrue(result.plan.entries.all { it.frameDurationNanos >= 12_000_000L })
        assertTrue(result.plan.entries.all { it.actualPlannedEv.isFinite() })
    }

    @Test
    fun rejectsMissingManualSensorContract() {
        val result = HdrBracketPlanner.plan(
            HdrExposureBaseline(10_000_000L, 200),
            constraints.copy(manualSensor = false),
        )

        assertTrue(result is HdrBracketPlanningResult.Unsupported)
    }
}
