package ir.taximeter.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "taxi_sessions")
data class TaxiSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val status: String = "ACTIVE",
    val createdAtMs: Long = startedAtMs
)

@Entity(tableName = "fare_configs", indices = [Index(value = ["version"], unique = true)])
data class FareConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val version: Int,
    val ratePerKmToman: Long,
    val minimumFareToman: Long,
    val roundingPolicy: String = "NEAREST_TOMAN_HALF_UP",
    val createdAtMs: Long,
    val effectiveFromMs: Long,
    val isActive: Boolean = false
)

@Entity(
    tableName = "passenger_trips",
    foreignKeys = [
        ForeignKey(entity = TaxiSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FareConfigEntity::class, parentColumns = ["id"], childColumns = ["fareConfigId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("sessionId"), Index("fareConfigId"), Index(value = ["sessionId", "seatNumber", "status"])]
)
data class PassengerTripEntity(
    @PrimaryKey val id: String,
    val sessionId: Long,
    val seatNumber: Int,
    val mode: String = "SHARED",
    val status: String = "ACTIVE",
    val reviewStatus: String = "NONE",
    val boardedAtMs: Long,
    val alightedAtMs: Long? = null,
    val boardingLat: Double? = null,
    val boardingLon: Double? = null,
    val alightingLat: Double? = null,
    val alightingLon: Double? = null,
    val fareConfigId: Long,
    val distanceMeters: Long = 0,
    val distanceStatus: String = "INCOMPLETE",
    val distanceMethod: String = "GPS",
    val calculatedFareToman: Long? = null,
    val finalFareToman: Long? = null,
    val fareStatus: String = "PENDING",
    val agreedFareToman: Long? = null,
    val charterFareToman: Long? = null,
    val hasUnresolvedGap: Boolean = false,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "location_points",
    foreignKeys = [ForeignKey(entity = TaxiSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["sessionId", "elapsedRealtimeNs"])]
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val recordedAtMs: Long,
    val elapsedRealtimeNs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String = "GPS",
    val validity: String = "UNREVIEWED",
    val invalidReason: String? = null
)

@Entity(
    tableName = "distance_segments",
    foreignKeys = [
        ForeignKey(entity = TaxiSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LocationPointEntity::class, parentColumns = ["id"], childColumns = ["startPointId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LocationPointEntity::class, parentColumns = ["id"], childColumns = ["endPointId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("sessionId"), Index("startPointId"), Index("endPointId"), Index(value = ["sessionId", "startAtMs", "endAtMs"])]
)
data class DistanceSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startPointId: Long?,
    val endPointId: Long?,
    val startAtMs: Long,
    val endAtMs: Long,
    val distanceMeters: Long = 0,
    val status: String,
    val method: String = "GPS",
    val qualityReason: String? = null
)

@Entity(
    tableName = "trip_distance_segments",
    primaryKeys = ["tripId", "segmentId"],
    foreignKeys = [
        ForeignKey(entity = PassengerTripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = DistanceSegmentEntity::class, parentColumns = ["id"], childColumns = ["segmentId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("segmentId")]
)
data class TripDistanceSegmentEntity(
    val tripId: String,
    val segmentId: Long,
    val allocatedMeters: Long,
    val allocationMethod: String
)

@Entity(
    tableName = "trip_events",
    foreignKeys = [ForeignKey(entity = PassengerTripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["tripId", "occurredAtMs"])]
)
data class TripEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val eventType: String,
    val occurredAtMs: Long,
    val elapsedRealtimeNs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val reasonCode: String? = null,
    val details: String? = null
)

@Entity(
    tableName = "fare_adjustments",
    foreignKeys = [ForeignKey(entity = PassengerTripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["tripId", "createdAtMs"])]
)
data class FareAdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val originalFareToman: Long,
    val adjustedFareToman: Long,
    val reason: String,
    val driverNote: String? = null,
    val createdAtMs: Long
)

@Entity(tableName = "audit_events", indices = [Index(value = ["entityType", "entityId", "occurredAtMs"])])
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val action: String,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val reason: String? = null,
    val occurredAtMs: Long
)
