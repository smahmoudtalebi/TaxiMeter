package ir.taximeter.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FareCalculatorTest {
    @Test fun belowMinimumUsesMinimum() {
        assertEquals(
            FareResult.Calculated(20_000),
            FareCalculator.calculateSharedFare(1_000, 5_000, 20_000, true, false)
        )
    }

    @Test fun distanceFareAboveMinimumIsUsed() {
        assertEquals(
            FareResult.Calculated(30_000),
            FareCalculator.calculateSharedFare(6_000, 5_000, 20_000, true, false)
        )
    }

    @Test fun halfTomanRoundsUp() {
        // 0.5 km at 1 toman/km is 0.5 toman; minimum is zero.
        assertEquals(
            FareResult.Calculated(1),
            FareCalculator.calculateSharedFare(500, 1, 0, true, false)
        )
    }

    @Test fun unresolvedGpsGapRequiresReview() {
        assertSame(
            FareResult.NeedsReview,
            FareCalculator.calculateSharedFare(4_000, 5_000, 20_000, false, true)
        )
    }

    @Test fun verifiedFlagAloneDoesNotOverrideGap() {
        assertSame(
            FareResult.NeedsReview,
            FareCalculator.calculateSharedFare(4_000, 5_000, 20_000, true, true)
        )
    }
}
