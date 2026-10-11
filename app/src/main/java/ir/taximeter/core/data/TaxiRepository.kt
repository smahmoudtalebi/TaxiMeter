package ir.taximeter.core.data

import androidx.room.withTransaction
import ir.taximeter.core.domain.BoardingValidation
import ir.taximeter.core.domain.FareCalculator
import ir.taximeter.core.domain.FareResult
import ir.taximeter.core.domain.TripRules
import java.util.UUID

/**
 * Transactional application boundary for trip lifecycle operations.
 * GPS collection/segment validation is deliberately separate from this repository.
 */
class TaxiRepository(private val db: TaxiDatabase) {
    private val dao = db.taxiDao()

    suspend fun startSession(nowMs: Long): Long = db.withTransaction {
        check(dao.getActiveSession() == null) { "یک شیفت فعال از قبل وجود دارد." }
        dao.insertSession(TaxiSessionEntity(startedAtMs = nowMs, createdAtMs = nowMs))
    }

    suspend fun boardPassenger(
        sessionId: Long,
        seatNumber: Int,
        nowMs: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        gpsLocationFresh: Boolean = true
    ): BoardPassengerResult = db.withTransaction {
        val session = dao.getSession(sessionId)
            ?: return@withTransaction BoardPassengerResult.Failure("شیفت پیدا نشد.")
        if (session.status != "ACTIVE" || session.endedAtMs != null) {
            return@withTransaction BoardPassengerResult.Failure("شیفت فعال نیست.")
        }

        val activeTrips = dao.getActiveTrips(sessionId)
        when (TripRules.validateBoarding(seatNumber, activeTrips)) {
            BoardingValidation.InvalidSeat -> return@withTransaction BoardPassengerResult.Failure("شماره صندلی باید بین ۱ تا ۴ باشد.")
            BoardingValidation.SeatOccupied -> return@withTransaction BoardPassengerResult.Failure("این صندلی اشغال است.")
            BoardingValidation.VehicleFull -> return@withTransaction BoardPassengerResult.Failure("ظرفیت خودرو تکمیل است.")
            BoardingValidation.Allowed -> Unit
        }

        val config = dao.getActiveFareConfig()
            ?: return@withTransaction BoardPassengerResult.Failure("تعرفه فعالی تعریف نشده است.")

        val tripId = UUID.randomUUID().toString()
        dao.insertTrip(
            PassengerTripEntity(
                id = tripId,
                sessionId = sessionId,
                seatNumber = seatNumber,
                boardedAtMs = nowMs,
                boardingLat = latitude,
                boardingLon = longitude,
                fareConfigId = config.id,
                reviewStatus = if (gpsLocationFresh) "NONE" else "REQUIRED",
                hasUnresolvedGap = !gpsLocationFresh,
                distanceStatus = if (gpsLocationFresh) "INCOMPLETE" else "GAP_DETECTED",
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        )
        dao.insertTripEvent(
            TripEventEntity(
                tripId = tripId,
                eventType = "PASSENGER_BOARDED",
                occurredAtMs = nowMs,
                latitude = latitude,
                longitude = longitude,
                reasonCode = if (gpsLocationFresh) null else "GPS_NOT_FRESH_AT_BOARDING",
                details = "fareConfigId=${config.id};fareVersion=${config.version};gpsLocationFresh=$gpsLocationFresh"
            )
        )
        BoardPassengerResult.Success(tripId, seatNumber, config.version)
    }

    /**
     * Ends passenger occupancy and releases the seat even when GPS is incomplete.
     * Incomplete distance leaves the fare pending review; it is never silently guessed.
     */
    suspend fun alightPassenger(
        tripId: String,
        nowMs: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        gpsCoverageComplete: Boolean,
        unresolvedGpsGap: Boolean
    ): AlightPassengerResult = db.withTransaction {
        val trip = dao.getTrip(tripId)
            ?: return@withTransaction AlightPassengerResult.Failure("سفر پیدا نشد.")
        if (trip.status != "ACTIVE") {
            return@withTransaction AlightPassengerResult.Failure("این سفر قبلاً بسته شده است.")
        }
        if (nowMs < trip.boardedAtMs) {
            return@withTransaction AlightPassengerResult.Failure("زمان پیاده‌شدن نمی‌تواند پیش از زمان سوارشدن باشد.")
        }

        val config = dao.getFareConfig(trip.fareConfigId)
            ?: return@withTransaction AlightPassengerResult.Failure("نسخه تعرفه سفر پیدا نشد.")
        val distanceMeters = dao.getAllocatedDistanceMeters(tripId)
        val invalidAllocatedSegments = dao.countNonValidAllocatedSegments(tripId)
        val gapExists = unresolvedGpsGap || invalidAllocatedSegments > 0
        val fareResult = FareCalculator.calculateSharedFare(
            distanceMeters = distanceMeters,
            ratePerKmToman = config.ratePerKmToman,
            minimumFareToman = config.minimumFareToman,
            distanceVerified = gpsCoverageComplete && invalidAllocatedSegments == 0,
            hasUnresolvedGap = gapExists
        )

        val amount = (fareResult as? FareResult.Calculated)?.amountToman
        val needsReview = fareResult == FareResult.NeedsReview
        dao.updateTrip(
            trip.copy(
                status = "CLOSED",
                reviewStatus = if (needsReview) "REQUIRED" else "NONE",
                alightedAtMs = nowMs,
                alightingLat = latitude,
                alightingLon = longitude,
                distanceMeters = distanceMeters,
                distanceStatus = when {
                    !needsReview -> "VERIFIED"
                    gapExists -> "GAP_DETECTED"
                    else -> "INCOMPLETE"
                },
                distanceMethod = "GPS",
                calculatedFareToman = amount,
                finalFareToman = amount,
                fareStatus = if (needsReview) "NEEDS_REVIEW" else "FINAL",
                hasUnresolvedGap = gapExists,
                updatedAtMs = nowMs
            )
        )
        dao.insertTripEvent(
            TripEventEntity(
                tripId = tripId,
                eventType = "PASSENGER_ALIGHTED",
                occurredAtMs = nowMs,
                latitude = latitude,
                longitude = longitude,
                reasonCode = if (needsReview) "GPS_REVIEW_REQUIRED" else null,
                details = "distanceMeters=$distanceMeters;fareStatus=${if (needsReview) "NEEDS_REVIEW" else "FINAL"}"
            )
        )
        if (needsReview) AlightPassengerResult.NeedsReview(tripId, distanceMeters)
        else AlightPassengerResult.Finalized(tripId, distanceMeters, amount!!)
    }

    suspend fun adjustFare(
        tripId: String,
        newFareToman: Long,
        reason: String,
        driverNote: String? = null,
        nowMs: Long
    ): FareAdjustmentResult = db.withTransaction {
        val trip = dao.getTrip(tripId)
            ?: return@withTransaction FareAdjustmentResult.Failure("سفر پیدا نشد.")
        if (trip.status != "CLOSED") {
            return@withTransaction FareAdjustmentResult.Failure("اصلاح کرایه فقط پس از پایان سفر مجاز است.")
        }
        val validationError = TripRules.validateFareAdjustment(newFareToman, reason)
        if (validationError != null) return@withTransaction FareAdjustmentResult.Failure(validationError)
        val originalCalculated = trip.calculatedFareToman
            ?: return@withTransaction FareAdjustmentResult.Failure("کرایه اولیه هنوز معتبر و نهایی نشده است؛ ابتدا بررسی سفر را تکمیل کنید.")

        dao.insertFareAdjustment(
            FareAdjustmentEntity(
                tripId = tripId,
                originalFareToman = originalCalculated,
                adjustedFareToman = newFareToman,
                reason = reason.trim(),
                driverNote = driverNote?.trim()?.takeIf { it.isNotEmpty() },
                createdAtMs = nowMs
            )
        )
        dao.updateTrip(trip.copy(finalFareToman = newFareToman, updatedAtMs = nowMs))
        dao.insertAuditEvent(
            AuditEventEntity(
                entityType = "PASSENGER_TRIP",
                entityId = tripId,
                action = "FARE_ADJUSTED",
                beforeJson = "{\"calculatedFareToman\":$originalCalculated,\"finalFareToman\":${trip.finalFareToman}}",
                afterJson = "{\"calculatedFareToman\":$originalCalculated,\"finalFareToman\":$newFareToman}",
                reason = reason.trim(),
                occurredAtMs = nowMs
            )
        )
        FareAdjustmentResult.Success(originalCalculated, newFareToman)
    }

    suspend fun closeSession(sessionId: Long, nowMs: Long): SessionCloseResult = db.withTransaction {
        val session = dao.getSession(sessionId)
            ?: return@withTransaction SessionCloseResult.Failure("شیفت پیدا نشد.")
        if (session.status != "ACTIVE") return@withTransaction SessionCloseResult.Failure("شیفت قبلاً بسته شده است.")
        if (dao.getActiveTrips(sessionId).isNotEmpty()) {
            return@withTransaction SessionCloseResult.Failure("تا وقتی سفر فعالی وجود دارد، شیفت بسته نمی‌شود.")
        }
        dao.updateSession(session.copy(status = "CLOSED", endedAtMs = nowMs))
        SessionCloseResult.Success
    }
}

sealed interface BoardPassengerResult {
    data class Success(val tripId: String, val seatNumber: Int, val fareVersion: Int) : BoardPassengerResult
    data class Failure(val message: String) : BoardPassengerResult
}

sealed interface AlightPassengerResult {
    data class Finalized(val tripId: String, val distanceMeters: Long, val fareToman: Long) : AlightPassengerResult
    data class NeedsReview(val tripId: String, val recordedDistanceMeters: Long) : AlightPassengerResult
    data class Failure(val message: String) : AlightPassengerResult
}

sealed interface FareAdjustmentResult {
    data class Success(val originalCalculatedFareToman: Long, val newFinalFareToman: Long) : FareAdjustmentResult
    data class Failure(val message: String) : FareAdjustmentResult
}

sealed interface SessionCloseResult {
    data object Success : SessionCloseResult
    data class Failure(val message: String) : SessionCloseResult
}
