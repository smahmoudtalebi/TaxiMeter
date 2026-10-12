package ir.taximeter.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface TaxiDao {
    @Insert
    suspend fun insertSession(session: TaxiSessionEntity): Long

    @Query("SELECT * FROM taxi_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): TaxiSessionEntity?

    @Query("SELECT * FROM taxi_sessions WHERE status = 'ACTIVE' ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getActiveSession(): TaxiSessionEntity?

    @Query("SELECT * FROM taxi_sessions ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getLatestSession(): TaxiSessionEntity?

    @Update
    suspend fun updateSession(session: TaxiSessionEntity)

    @Insert
    suspend fun insertFareConfig(config: FareConfigEntity): Long

    @Query("SELECT * FROM fare_configs WHERE isActive = 1 ORDER BY version DESC LIMIT 1")
    suspend fun getActiveFareConfig(): FareConfigEntity?

    @Query("UPDATE fare_configs SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateActiveFareConfigs()

    @Query("UPDATE fare_configs SET isActive = 1 WHERE id = :id")
    suspend fun activateFareConfig(id: Long)

    @Query("SELECT * FROM fare_configs WHERE id = :id LIMIT 1")
    suspend fun getFareConfig(id: Long): FareConfigEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM fare_configs")
    suspend fun getMaxFareVersion(): Int

    @Insert
    suspend fun insertTrip(trip: PassengerTripEntity)

    @Update
    suspend fun updateTrip(trip: PassengerTripEntity)

    @Query("SELECT * FROM passenger_trips WHERE id = :id LIMIT 1")
    suspend fun getTrip(id: String): PassengerTripEntity?

    @Query("SELECT * FROM passenger_trips WHERE sessionId = :sessionId ORDER BY boardedAtMs")
    suspend fun getTripsForSession(sessionId: Long): List<PassengerTripEntity>

    /** Closed trips with GPS endpoints, used only as historical route references for uncertain trips. */
    @Query("SELECT * FROM passenger_trips WHERE status = 'CLOSED' AND fareStatus = 'FINAL' AND distanceStatus = 'VERIFIED' AND hasUnresolvedGap = 0 AND distanceMethod = 'GPS' AND distanceMeters > 0 AND boardingLat IS NOT NULL AND boardingLon IS NOT NULL AND alightingLat IS NOT NULL AND alightingLon IS NOT NULL ORDER BY boardedAtMs DESC LIMIT 500")
    suspend fun getHistoricalTripsWithEndpoints(): List<PassengerTripEntity>

    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId ORDER BY recordedAtMs DESC, id DESC LIMIT 1")
    suspend fun getLatestLocationPoint(sessionId: Long): LocationPointEntity?

    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId AND validity = 'VALID' ORDER BY recordedAtMs DESC, id DESC LIMIT 1")
    suspend fun getLatestValidLocationPoint(sessionId: Long): LocationPointEntity?

    @Query("SELECT COUNT(*) FROM distance_segments WHERE sessionId = :sessionId AND status = 'VALID' AND startAtMs < :endMs AND endAtMs > :startMs")
    suspend fun countValidSegmentsInRange(sessionId: Long, startMs: Long, endMs: Long): Int

    @Query("SELECT COUNT(*) FROM distance_segments WHERE sessionId = :sessionId AND status != 'VALID' AND startAtMs < :endMs AND endAtMs > :startMs")
    suspend fun countNonValidSegmentsInRange(sessionId: Long, startMs: Long, endMs: Long): Int

    @Query("SELECT * FROM passenger_trips WHERE sessionId = :sessionId AND status = 'ACTIVE' ORDER BY boardedAtMs")
    suspend fun getActiveTrips(sessionId: Long): List<PassengerTripEntity>

    @Query("SELECT * FROM passenger_trips WHERE sessionId = :sessionId AND seatNumber = :seat AND status = 'ACTIVE' LIMIT 1")
    suspend fun getActiveTripForSeat(sessionId: Long, seat: Int): PassengerTripEntity?

    @Insert
    suspend fun insertLocationPoint(point: LocationPointEntity): Long

    @Insert
    suspend fun insertDistanceSegment(segment: DistanceSegmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllocation(allocation: TripDistanceSegmentEntity)

    @Query("SELECT * FROM trip_distance_segments WHERE tripId = :tripId")
    suspend fun getAllocationsForTrip(tripId: String): List<TripDistanceSegmentEntity>

    @Query("SELECT COALESCE(SUM(allocatedMeters), 0) FROM trip_distance_segments WHERE tripId = :tripId")
    suspend fun getAllocatedDistanceMeters(tripId: String): Long

    @Query("SELECT COUNT(*) FROM trip_distance_segments tds JOIN distance_segments ds ON ds.id = tds.segmentId WHERE tds.tripId = :tripId AND ds.status != 'VALID'")
    suspend fun countNonValidAllocatedSegments(tripId: String): Int

    @Insert
    suspend fun insertTripEvent(event: TripEventEntity): Long

    @Insert
    suspend fun insertFareAdjustment(adjustment: FareAdjustmentEntity): Long

    @Insert
    suspend fun insertAuditEvent(event: AuditEventEntity): Long

    @Transaction
    suspend fun activateFareConfigAtomically(config: FareConfigEntity) {
        deactivateActiveFareConfigs()
        val id = insertFareConfig(config.copy(isActive = true))
        activateFareConfig(id)
    }
}
