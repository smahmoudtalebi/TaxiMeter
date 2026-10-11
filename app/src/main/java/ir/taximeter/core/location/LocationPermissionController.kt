package ir.taximeter.core.location

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Runtime permission flow to be owned by the driver Activity.
 * Location permission is required; notification permission is requested on Android 13+ but
 * its denial does not itself block location tracking.
 */
class LocationPermissionController(
    activity: ComponentActivity,
    private val onLocationPermissionResult: (granted: Boolean) -> Unit
) {
    private val appActivity = activity

    private val notificationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Notifications improve transparency, but Android does not make POST_NOTIFICATIONS
        // a prerequisite for starting a foreground service.
        onLocationPermissionResult(hasLocationPermission())
    }

    private val locationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            onLocationPermissionResult(false)
        } else {
            requestNotificationPermissionIfNeeded()
        }
    }

    fun request() {
        if (!hasLocationPermission()) {
            locationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            requestNotificationPermissionIfNeeded()
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onLocationPermissionResult(hasLocationPermission())
        }
    }
}
