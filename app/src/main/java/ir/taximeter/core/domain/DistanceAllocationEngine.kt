package ir.taximeter.core.domain

/** Time window for one passenger's independent trip. A null alightedAtMs means active. */
data class TripWindow(
    val tripId: String,
    val boardedAtMs: Long,
    val alightedAtMs: Long? = null
)

data class ValidatedRouteSegment(
    val segmentId: Long,
    val startAtMs: Long,
    val endAtMs: Long,
    val distanceMeters: Long,
    val status: SegmentStatus
)

enum class SegmentStatus { VALID, GAP, INVALID, REVIEW_REQUIRED }

enum class AllocationQuality { FULL_SEGMENT, INTERPOLATED_BOUNDARY, REVIEW_REQUIRED }

data class TripDistanceAllocation(
    val tripId: String,
    val segmentId: Long,
    val allocatedMeters: Long,
    val quality: AllocationQuality
)

data class AllocationBatch(
    val allocations: List<TripDistanceAllocation>,
    val tripsRequiringReview: Set<String>
)

/**
 * Allocates one validated vehicle-route segment to every passenger trip whose time window overlaps it.
 * Partial-segment allocation is permitted only for short segments, using linear time interpolation.
 * This is an approximation policy, not a claim that vehicle speed was constant; callers must retain
 * the quality marker and configure the threshold using field tests.
 */
object DistanceAllocationEngine {
    fun allocate(
        segment: ValidatedRouteSegment,
        trips: List<TripWindow>,
        maxInterpolationDurationMs: Long
    ): AllocationBatch {
        require(segment.endAtMs >= segment.startAtMs) { "Segment end precedes start" }
        require(segment.distanceMeters >= 0) { "Negative segment distance" }
        require(maxInterpolationDurationMs >= 0) { "Negative interpolation threshold" }
        trips.forEach {
            require(it.alightedAtMs == null || it.alightedAtMs >= it.boardedAtMs) {
                "Trip end precedes boarding: ${it.tripId}"
            }
        }

        val duration = segment.endAtMs - segment.startAtMs
        val overlapping = trips.filter { trip ->
            val tripEnd = trip.alightedAtMs ?: Long.MAX_VALUE
            trip.boardedAtMs < segment.endAtMs && tripEnd > segment.startAtMs
        }
        if (overlapping.isEmpty()) return AllocationBatch(emptyList(), emptySet())

        if (segment.status != SegmentStatus.VALID || duration <= 0L) {
            return AllocationBatch(emptyList(), overlapping.map { it.tripId }.toSet())
        }

        val allocations = mutableListOf<TripDistanceAllocation>()
        val review = linkedSetOf<String>()
        for (trip in overlapping) {
            val overlapStart = maxOf(segment.startAtMs, trip.boardedAtMs)
            val overlapEnd = minOf(segment.endAtMs, trip.alightedAtMs ?: Long.MAX_VALUE)
            if (overlapEnd <= overlapStart) continue

            val isFull = overlapStart == segment.startAtMs && overlapEnd == segment.endAtMs
            if (isFull) {
                allocations += TripDistanceAllocation(
                    tripId = trip.tripId,
                    segmentId = segment.segmentId,
                    allocatedMeters = segment.distanceMeters,
                    quality = AllocationQuality.FULL_SEGMENT
                )
            } else {
                if (duration > maxInterpolationDurationMs) {
                    review += trip.tripId
                    continue
                }
                val overlapDuration = overlapEnd - overlapStart
                val allocated = try {
                    val numerator = Math.multiplyExact(segment.distanceMeters, overlapDuration)
                    Math.addExact(numerator, duration / 2L) / duration
                } catch (_: ArithmeticException) {
                    review += trip.tripId
                    continue
                }
                allocations += TripDistanceAllocation(
                    tripId = trip.tripId,
                    segmentId = segment.segmentId,
                    allocatedMeters = allocated.coerceIn(0L, segment.distanceMeters),
                    quality = AllocationQuality.INTERPOLATED_BOUNDARY
                )
            }
        }
        return AllocationBatch(allocations, review)
    }
}
