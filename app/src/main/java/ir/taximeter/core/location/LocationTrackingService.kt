package ir.taximeter.core.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import ir.taximeter.core.data.AuditEventEntity
import ir.taximeter.core.data.DistanceSegmentEntity
import ir.taximeter.core.data.LocationPointEntity
import ir.taximeter.core.data.PassengerTripEntity
import ir.taximeter.core.data.TaxiDatabase
import ir.taximeter.core.data.TripDistanceSegmentEntity
import ir.taximeter.core.data.TripEventEntity
import ir.taximeter.core.domain.AllocationQuality
import ir.taximeter.core.domain.DistanceAllocationEngine
import ir.taximeter.core.domain.SegmentStatus
import ir.taximeter.core.domain.TripWindow
import ir.taximeter.core.domain.ValidatedRouteSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * Foreground location service for an active taxi shift.
 * Start it from a visible Activity only after runtime location permission is granted.
 * Missing/low-quality GPS data is recorded as a gap; distance is never fabricated.
 */
class LocationTrackingService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val stopping = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)
    private val locationProcessingMutex = Mutex()
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val db by lazy { TaxiDatabase.getInstance(this) }

    @Volatile private var sessionId: Long = INVALID_SESSION_ID
    @Volatile private var previousUsablePoint: LocationPointEntity? = null
    @Volatile private var gapStartAtMs: Long? = null
    @Volatile private var lastProcessedElapsedNs: Long = -1L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            serviceScope.launch {
                locationProcessingMutex.withLock {
                    for (location in result.locations) {
                        if (stopping.get()) break
                        try {
                            processLocation(location)
                        } catch (error: Exception) {
                            recordTrackingFailure("LOCATION_PROCESSING_ERROR", error.message)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        val requestedSession = intent?.getLongExtra(EXTRA_SESSION_ID, INVALID_SESSION_ID)
        if (requestedSession != null && requestedSession <= 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!hasLocationPermission()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            val dao = db.taxiDao()
            val resolvedSessionId = requestedSession ?: dao.getActiveSession()?.id
            if (resolvedSessionId == null || resolvedSessionId <= 0L) {
                stopTracking()
                return@launch
            }
            sessionId = resolvedSessionId
            val session = dao.getSession(sessionId)
            if (session == null || session.status != "ACTIVE") {
                stopTracking()
                return@launch
            }
            if (!callbackRegistered.get()) {
                val latest = dao.getLatestLocationPoint(sessionId)
                // elapsedRealtime is monotonic only within a boot. If it went backwards, never bridge it.
                previousUsablePoint = latest?.takeIf { it.validity == "VALID" }
                gapStartAtMs = latest?.takeIf { it.validity != "VALID" }?.recordedAtMs
                // Compare ordering only within this service process. A reboot resets elapsedRealtime.
                lastProcessedElapsedNs = -1L
                requestLocationUpdates()
            }
        }
        return START_STICKY
    }

    private fun requestLocationUpdates() {
        if (stopping.get() || callbackRegistered.get()) return
        if (!hasLocationPermission()) {
            stopTracking()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        if (!callbackRegistered.compareAndSet(false, true)) return
        try {
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener {
                    callbackRegistered.set(false)
                    serviceScope.launch { recordTrackingFailure("LOCATION_REQUEST_FAILED", it.message) }
                    stopTracking()
                }
        } catch (security: SecurityException) {
            callbackRegistered.set(false)
            serviceScope.launch { recordTrackingFailure("LOCATION_PERMISSION_REVOKED", security.message) }
            stopTracking()
        }
    }

    private suspend fun processLocation(location: Location) {
        if (sessionId <= 0L || stopping.get()) return
        val nowWallMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val elapsedNs = location.elapsedRealtimeNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
        val persistedBaseline = previousUsablePoint
        if (lastProcessedElapsedNs < 0L && persistedBaseline != null &&
            elapsedNs <= persistedBaseline.elapsedRealtimeNs && nowWallMs <= persistedBaseline.recordedAtMs
        ) {
            return // cached stale fix from before service restart; wait for a fresh point
        }
        if (elapsedNs <= lastProcessedElapsedNs) {
            if (elapsedNs < lastProcessedElapsedNs) {
                db.withTransaction {
                    markActiveTripsForReview("GPS_OUT_OF_ORDER_FIX", "elapsedRealtimeNanos=$elapsedNs")
                }
            }
            return // duplicate or out-of-order fix; never add distance from it
        }

        val coordinatesValid = location.latitude.isFinite() && location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
        val accurate = coordinatesValid && location.hasAccuracy() &&
            location.accuracy.isFinite() && location.accuracy <= MAX_ACCURACY_METERS
        val pointValidity = if (accurate) "VALID" else "INVALID"
        val point = LocationPointEntity(
            sessionId = sessionId,
            recordedAtMs = nowWallMs,
            elapsedRealtimeNs = elapsedNs,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            provider = location.provider ?: "FUSED",
            validity = pointValidity,
            invalidReason = if (accurate) null else "ACCURACY_MISSING_OR_OVER_${MAX_ACCURACY_METERS}M"
        )

        db.withTransaction {
            val dao = db.taxiDao()
            val pointId = dao.insertLocationPoint(point)
            val currentPoint = point.copy(id = pointId)
            val previous = previousUsablePoint

            if (!accurate) {
                if (previous != null) {
                    val segmentId = dao.insertDistanceSegment(
                        DistanceSegmentEntity(
                            sessionId = sessionId,
                            startPointId = previous.id,
                            endPointId = pointId,
                            startAtMs = minOf(previous.recordedAtMs, nowWallMs),
                            endAtMs = maxOf(previous.recordedAtMs, nowWallMs),
                            distanceMeters = 0L,
                            status = "GAP",
                            method = "GPS",
                            qualityReason = point.invalidReason
                        )
                    )
                    markTripsForReview(
                        sessionId, minOf(previous.recordedAtMs, nowWallMs), maxOf(previous.recordedAtMs, nowWallMs),
                        "GPS_GAP_DETECTED", "segmentId=$segmentId;reason=${point.invalidReason}"
                    )
                } else {
                    markActiveTripsForReview("GPS_FIX_INVALID", point.invalidReason)
                }
                if (gapStartAtMs == null) gapStartAtMs = nowWallMs
                previousUsablePoint = null // never bridge across an inaccurate fix
                lastProcessedElapsedNs = elapsedNs
                return@withTransaction
            }

            if (previous == null) {
                val gapStart = gapStartAtMs
                if (gapStart != null) {
                    markTripsForReview(
                        sessionId, minOf(gapStart, nowWallMs), maxOf(gapStart, nowWallMs),
                        "GPS_GAP_RECOVERED", "No distance allocated across the GPS outage"
                    )
                    gapStartAtMs = null
                }
                previousUsablePoint = currentPoint
                lastProcessedElapsedNs = elapsedNs
                return@withTransaction
            }

            val deltaNs = elapsedNs - previous.elapsedRealtimeNs
            val deltaMs = deltaNs / 1_000_000L
            val elapsedConsistent = deltaNs > 0L
            val wallElapsedMs = nowWallMs - previous.recordedAtMs
            val rebootOrClockDiscontinuity = !elapsedConsistent || deltaMs <= 0L || wallElapsedMs <= 0L ||
                kotlin.math.abs(wallElapsedMs - deltaMs) > MAX_WALL_ELAPSED_DIVERGENCE_MS
            val distanceMetersFloat = FloatArray(1).also {
                Location.distanceBetween(
                    previous.latitude, previous.longitude,
                    currentPoint.latitude, currentPoint.longitude,
                    it
                )
            }[0]
            val distanceMeters = if (distanceMetersFloat.isFinite() && distanceMetersFloat >= 0f) {
                distanceMetersFloat.roundToLong()
            } else 0L
            val speedMetersPerSecond = if (deltaMs > 0L) distanceMeters.toDouble() / (deltaMs / 1000.0) else Double.POSITIVE_INFINITY

            val segmentStatus: String
            val reason: String?
            when {
                rebootOrClockDiscontinuity -> {
                    segmentStatus = "GAP"
                    reason = "CLOCK_OR_REBOOT_DISCONTINUITY"
                }
                deltaMs > MAX_SEGMENT_GAP_MS -> {
                    segmentStatus = "GAP"
                    reason = "LOCATION_TIME_GAP_${deltaMs}MS"
                }
                speedMetersPerSecond > MAX_PLAUSIBLE_SPEED_MPS -> {
                    segmentStatus = "REVIEW_REQUIRED"
                    reason = "IMPLAUSIBLE_SPEED_${speedMetersPerSecond}MPS"
                }
                else -> {
                    segmentStatus = "VALID"
                    reason = null
                }
            }

            val segmentId = dao.insertDistanceSegment(
                DistanceSegmentEntity(
                    sessionId = sessionId,
                    startPointId = previous.id,
                    endPointId = pointId,
                    startAtMs = minOf(previous.recordedAtMs, nowWallMs),
                    endAtMs = maxOf(previous.recordedAtMs, nowWallMs),
                    distanceMeters = if (segmentStatus == "VALID") distanceMeters else 0L,
                    status = segmentStatus,
                    method = "GPS",
                    qualityReason = reason
                )
            )

            if (segmentStatus == "VALID") {
                allocateSegment(
                    segmentId = segmentId,
                    startAtMs = previous.recordedAtMs,
                    endAtMs = nowWallMs,
                    distanceMeters = distanceMeters
                )
            } else {
                markTripsForReview(sessionId, minOf(previous.recordedAtMs, nowWallMs), maxOf(previous.recordedAtMs, nowWallMs),
                    if (segmentStatus == "GAP") "GPS_GAP_DETECTED" else "GPS_SEGMENT_REVIEW_REQUIRED",
                    "segmentId=$segmentId;reason=$reason")
            }

            // A good current fix becomes the new baseline even after a gap; we do not bridge the gap.
            previousUsablePoint = currentPoint
            lastProcessedElapsedNs = elapsedNs
        }
    }

    private suspend fun allocateSegment(
        segmentId: Long,
        startAtMs: Long,
        endAtMs: Long,
        distanceMeters: Long
    ) {
        val dao = db.taxiDao()
        val trips = dao.getTripsForSession(sessionId)
        val batch = DistanceAllocationEngine.allocate(
            segment = ValidatedRouteSegment(
                segmentId = segmentId,
                startAtMs = startAtMs,
                endAtMs = endAtMs,
                distanceMeters = distanceMeters,
                status = SegmentStatus.VALID
            ),
            trips = trips.map { TripWindow(it.id, it.boardedAtMs, it.alightedAtMs) },
            maxInterpolationDurationMs = MAX_INTERPOLATION_DURATION_MS
        )
        batch.allocations.forEach { allocation ->
            dao.insertAllocation(
                TripDistanceSegmentEntity(
                    tripId = allocation.tripId,
                    segmentId = allocation.segmentId,
                    allocatedMeters = allocation.allocatedMeters,
                    allocationMethod = when (allocation.quality) {
                        AllocationQuality.FULL_SEGMENT -> "FULL_SEGMENT"
                        AllocationQuality.INTERPOLATED_BOUNDARY -> "INTERPOLATED_BOUNDARY"
                        AllocationQuality.REVIEW_REQUIRED -> "REVIEWED"
                    }
                )
            )
        }
        batch.tripsRequiringReview.forEach { tripId ->
            val trip = dao.getTrip(tripId) ?: return@forEach
            dao.updateTrip(trip.copy(
                reviewStatus = "REQUIRED",
                hasUnresolvedGap = true,
                distanceStatus = "INCOMPLETE",
                fareStatus = if (trip.status == "CLOSED") "NEEDS_REVIEW" else trip.fareStatus,
                finalFareToman = if (trip.status == "CLOSED") null else trip.finalFareToman,
                updatedAtMs = System.currentTimeMillis()
            ))
            dao.insertTripEvent(TripEventEntity(
                tripId = tripId,
                eventType = "GPS_BOUNDARY_REVIEW_REQUIRED",
                occurredAtMs = System.currentTimeMillis(),
                reasonCode = "SEGMENT_BOUNDARY_NOT_RELIABLE",
                details = "segmentId=$segmentId"
            ))
        }
    }

    private suspend fun markTripsForReview(
        sessionId: Long,
        startAtMs: Long,
        endAtMs: Long,
        eventType: String,
        details: String
    ) {
        val dao = db.taxiDao()
        val trips = dao.getTripsForSession(sessionId).filter { trip ->
            val tripEnd = trip.alightedAtMs ?: Long.MAX_VALUE
            trip.boardedAtMs < endAtMs && tripEnd > startAtMs
        }
        trips.forEach { trip ->
            dao.updateTrip(trip.copy(
                reviewStatus = "REQUIRED",
                hasUnresolvedGap = true,
                distanceStatus = "GAP_DETECTED",
                fareStatus = if (trip.status == "CLOSED") "NEEDS_REVIEW" else trip.fareStatus,
                finalFareToman = if (trip.status == "CLOSED") null else trip.finalFareToman,
                updatedAtMs = System.currentTimeMillis()
            ))
            dao.insertTripEvent(TripEventEntity(
                tripId = trip.id,
                eventType = eventType,
                occurredAtMs = System.currentTimeMillis(),
                reasonCode = "GPS_DATA_INCOMPLETE",
                details = details
            ))
            dao.insertAuditEvent(AuditEventEntity(
                entityType = "PASSENGER_TRIP",
                entityId = trip.id,
                action = "GPS_REVIEW_REQUIRED",
                beforeJson = "{\"distanceStatus\":\"${trip.distanceStatus}\",\"fareStatus\":\"${trip.fareStatus}\"}",
                afterJson = "{\"distanceStatus\":\"GAP_DETECTED\",\"fareStatus\":\"${if (trip.status == "CLOSED") "NEEDS_REVIEW" else trip.fareStatus}\"}",
                reason = details,
                occurredAtMs = System.currentTimeMillis()
            ))
        }
    }

    private suspend fun markActiveTripsForReview(eventType: String, details: String?) {
        val dao = db.taxiDao()
        dao.getActiveTrips(sessionId).forEach { trip ->
            dao.updateTrip(trip.copy(
                reviewStatus = "REQUIRED",
                hasUnresolvedGap = true,
                distanceStatus = "GAP_DETECTED",
                updatedAtMs = System.currentTimeMillis()
            ))
            dao.insertTripEvent(TripEventEntity(
                tripId = trip.id,
                eventType = eventType,
                occurredAtMs = System.currentTimeMillis(),
                reasonCode = "GPS_DATA_UNRELIABLE",
                details = details
            ))
        }
    }

    private suspend fun recordTrackingFailure(code: String, details: String?) {
        if (sessionId <= 0L) return
        db.withTransaction {
            markActiveTripsForReview(code, details)
        }
    }

    private fun stopTracking() {
        if (!stopping.compareAndSet(false, true)) return
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
            // Service is stopping; the persisted data remains authoritative.
        }
        callbackRegistered.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping.set(true)
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) { }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ثبت مسیر تاکسی",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "ثبت مسیر خودرو هنگام فعال بودن شیفت تاکسی" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("تاکسی‌متر: ثبت مسیر فعال است")
        .setContentText("مسیر GPS برای محاسبه سفرهای فعال ثبت می‌شود.")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        const val ACTION_START = "ir.taximeter.core.location.START_TRACKING"
        const val ACTION_STOP = "ir.taximeter.core.location.STOP_TRACKING"
        const val EXTRA_SESSION_ID = "session_id"
        private const val INVALID_SESSION_ID = -1L
        private const val CHANNEL_ID = "taximeter_location"
        private const val NOTIFICATION_ID = 4107
        private const val LOCATION_INTERVAL_MS = 3_000L
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L
        private const val MAX_ACCURACY_METERS = 35f
        private const val MAX_SEGMENT_GAP_MS = 15_000L
        private const val MAX_INTERPOLATION_DURATION_MS = 5_000L
        private const val MAX_PLAUSIBLE_SPEED_MPS = 55.0 // conservative flag threshold, not a legal speed limit
        private const val MAX_WALL_ELAPSED_DIVERGENCE_MS = 120_000L

        /** Call from a visible Activity after location permission has been granted. */
        fun start(context: Context, sessionId: Long) {
            require(sessionId > 0L) { "sessionId must be positive" }
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) { "Location permission must be granted before starting tracking." }
            val intent = Intent(context, LocationTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Call from the app when the shift is ended by the driver. */
        fun stop(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
