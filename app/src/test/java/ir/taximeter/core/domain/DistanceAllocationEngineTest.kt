package ir.taximeter.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceAllocationEngineTest {
    @Test fun fullSegmentIsAssignedToEveryTripActiveForTheWholeSegment() {
        val segment = ValidatedRouteSegment(1, 1_000, 3_000, 1_000, SegmentStatus.VALID)
        val trips = listOf(TripWindow("A", 0, 4_000), TripWindow("B", 1_000, null))
        val result = DistanceAllocationEngine.allocate(segment, trips, 5_000)
        assertEquals(2, result.allocations.size)
        assertEquals(setOf("A", "B"), result.allocations.map { it.tripId }.toSet())
        assertTrue(result.allocations.all { it.allocatedMeters == 1_000L })
    }

    @Test fun shortBoundarySegmentIsInterpolatedByTimeAndMarked() {
        val segment = ValidatedRouteSegment(2, 0, 1_000, 500, SegmentStatus.VALID)
        val result = DistanceAllocationEngine.allocate(
            segment,
            listOf(TripWindow("A", 500, 1_500)),
            maxInterpolationDurationMs = 1_000
        )
        assertEquals(1, result.allocations.size)
        assertEquals(250L, result.allocations.single().allocatedMeters)
        assertEquals(AllocationQuality.INTERPOLATED_BOUNDARY, result.allocations.single().quality)
    }

    @Test fun longBoundarySegmentRequiresReviewRatherThanGuessing() {
        val segment = ValidatedRouteSegment(3, 0, 10_000, 2_000, SegmentStatus.VALID)
        val result = DistanceAllocationEngine.allocate(
            segment,
            listOf(TripWindow("A", 5_000, 20_000)),
            maxInterpolationDurationMs = 2_000
        )
        assertTrue(result.allocations.isEmpty())
        assertEquals(setOf("A"), result.tripsRequiringReview)
    }

    @Test fun gapSegmentIsNeverAllocated() {
        val segment = ValidatedRouteSegment(4, 0, 1_000, 700, SegmentStatus.GAP)
        val result = DistanceAllocationEngine.allocate(segment, listOf(TripWindow("A", 0, 2_000)), 2_000)
        assertTrue(result.allocations.isEmpty())
        assertEquals(setOf("A"), result.tripsRequiringReview)
    }

    @Test fun nonOverlappingTripReceivesNothing() {
        val segment = ValidatedRouteSegment(5, 1_000, 2_000, 500, SegmentStatus.VALID)
        val result = DistanceAllocationEngine.allocate(segment, listOf(TripWindow("A", 2_000, 3_000)), 2_000)
        assertTrue(result.allocations.isEmpty())
        assertTrue(result.tripsRequiringReview.isEmpty())
    }
}
