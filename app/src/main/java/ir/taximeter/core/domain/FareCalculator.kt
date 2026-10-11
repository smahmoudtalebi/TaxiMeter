package ir.taximeter.core.domain

/** Fare values are stored as whole Iranian toman. */
object FareCalculator {
    /**
     * Calculates max(minimum fare, distance fare), rounding to nearest toman.
     * Exact half-toman values round upward. Uses integer arithmetic to avoid
     * floating-point currency errors. distanceMeters must be verified and complete.
     */
    fun calculateSharedFare(
        distanceMeters: Long,
        ratePerKmToman: Long,
        minimumFareToman: Long,
        distanceVerified: Boolean,
        hasUnresolvedGap: Boolean
    ): FareResult {
        require(distanceMeters >= 0) { "Distance cannot be negative" }
        require(ratePerKmToman >= 0) { "Rate cannot be negative" }
        require(minimumFareToman >= 0) { "Minimum fare cannot be negative" }

        if (!distanceVerified || hasUnresolvedGap) return FareResult.NeedsReview

        // Toman/km * meters / 1000; add 500 to round half-up to whole toman.
        val product = Math.multiplyExact(distanceMeters, ratePerKmToman)
        val distanceFare = Math.addExact(product, 500L) / 1000L
        return FareResult.Calculated(maxOf(minimumFareToman, distanceFare))
    }
}

sealed interface FareResult {
    data class Calculated(val amountToman: Long) : FareResult
    data object NeedsReview : FareResult
}
