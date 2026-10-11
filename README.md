# TaxiMeterCore — Android core prototype

This repository is an early **core prototype**, not yet a driver-ready taxi-meter application. It establishes the data model and transactional trip lifecycle for a rotating/shared taxi with up to four simultaneous passenger trips.

## Decisions encoded

- Currency is Iranian toman.
- Each passenger has an independent trip ID and distance/fare.
- Each new trip snapshots the active fare configuration; later tariff changes do not rewrite active or closed trips.
- Fare rounding is to the nearest whole toman, with exact half-toman values rounded upward.
- Unknown distance during a GPS gap is not estimated automatically. The trip is closed for seat occupancy purposes but its fare remains pending review.
- Driver fare corrections require a reason and preserve the calculated fare and adjustment history.
- Stop time does not add fare in the default distance-only fare model.

## Implemented in this prototype

- Room entities and DAO for sessions, fare versions, passenger trips, GPS points, route segments, allocations, events, fare adjustments, and audit events.
- Transactional repository operations for starting a shift, boarding a passenger, ending a trip, adjusting a finalized fare, and closing a shift.
- Shared-fare calculation using integer arithmetic.
- Pure Kotlin route-segment allocation policy and unit tests. Partial segment allocation is only allowed under a configured short-duration threshold and is explicitly marked as interpolated; otherwise affected trips require review.

## Not yet implemented / not field validated

- Driver-facing Persian UI and shift/session management screens.
- Integration of `LocationPermissionController` into the final driver Activity.
- On-device field validation of GPS thresholds and battery behavior.
- Historical route fallback workflow, agreed-fare UI, charter reservation enforcement.
- Exportable shift/trip reports and on-device installation tests.

## Build environment

Open the project in Android Studio with JDK 17 and Android SDK 35 available. The source has not been built or tested in a full Android Studio environment in this delivery; run Gradle sync and unit tests before relying on it. Any compiler or Room schema errors found there must be fixed before deployment.


## GPS foreground service (prototype added in v0.3)

`LocationTrackingService` now requests high-accuracy fused locations while running as an Android location foreground service. It persists location points and route segments, allocates valid segments to overlapping passenger trips, and flags trips for review when points are inaccurate, time gaps are too large, or a segment implies an implausible speed. It does not invent distance for a gap.

### Required app integration

1. Integrate `LocationPermissionController` into a `ComponentActivity`. Call `request()` in response to the driver's action; its callback reports whether location permission was granted. It requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, and optionally asks for notification permission on Android 13+. The manifest declares these permissions plus `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`.
2. Create an active taxi session and an active fare configuration through the app's normal workflow.
3. After permission is granted and while the Activity is visible, call `LocationTrackingService.start(context, sessionId)`.
4. When the driver ends the shift, close all active passenger trips, close the session, and call `LocationTrackingService.stop(context)`.
5. If the app process or device is restarted, verify session state and GPS continuity before accepting more distance. A long gap is marked for review.

Example integration pattern in a `ComponentActivity` (the actual driver UI is still to be built):

```kotlin
private lateinit var locationPermissions: LocationPermissionController

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    locationPermissions = LocationPermissionController(this) { granted ->
        if (granted) {
            // Call only after the app has created/recovered an active session.
            LocationTrackingService.start(this, activeSessionId)
        } else {
            // Show an explanation and let the driver retry permission setup.
        }
    }
}

// Invoke after the driver taps the start-shift/start-tracking action:
locationPermissions.request()
```

The service's initial thresholds are deliberately conservative and are not validated fare-meter accuracy claims: maximum accepted location accuracy 35 m, maximum segment gap 15 s, maximum interpolation interval 5 s, and implausible-speed flag at 55 m/s. These values must be calibrated with field tests. A valid segment is a straight-line distance between sampled coordinates, not map-matched road distance, so urban GPS conditions may still cause undercounting or noise.

The project does not yet include a complete driver Activity to request permissions and create/manage sessions, and has not been compiled or tested on a physical Android device in this environment. Treat this as a GPS integration prototype, not a certified fare meter.

## v0.4 — Persian driver UI integration

The prototype now includes a native Android `MainActivity` with a Persian right-to-left layout. It is connected to the local Room database, transactional repository, and location foreground service.

- Start a shift after defining a configurable experimental tariff and granting location permission.
- Resume GPS tracking for an existing active shift.
- Four seat cards show free/occupied status, recorded distance, and GPS review flags.
- Boarding creates a unique trip with the active tariff version. If no fresh GPS fix is available, the trip is explicitly marked for review.
- Alighting closes only the selected passenger trip. A fare is finalized only when the stored route coverage is considered complete; otherwise the app reports review required and does not guess missing distance.
- End shift is blocked while passenger trips remain active.
- Recent trips remain visible after a shift closes.
- A finalized fare can be adjusted by the driver only with a reason; the original calculated amount and adjustment history are preserved.
- Changing a tariff creates a new version for new trips; existing trips retain their stored tariff ID.

### Important limitations

This is still a development prototype, not a certified or field-validated fare meter. No full Android/Gradle build or device/emulator test could be run in the current environment. The UI's GPS coverage check is conservative but not a proof of complete road distance; field validation is required. Trips flagged for GPS review currently have no dedicated in-app workflow to resolve the missing distance, so they remain review-pending rather than being automatically settled. Agreed-fare and charter modes are represented in the data model but their complete driver workflows are not yet implemented. Reports are shown on screen but export is not yet implemented.
