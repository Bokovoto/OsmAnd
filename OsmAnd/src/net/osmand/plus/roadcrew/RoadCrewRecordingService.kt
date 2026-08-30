package net.osmand.plus.roadcrew

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.StateChangedListener
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.plus.helpers.LocationCallback
import net.osmand.plus.helpers.LocationServiceHelper
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.plus.settings.enums.LocationSource
import net.osmand.router.RoadCrewRecordingPolicy

/** Owns only observation GPS, never navigation, GPX recording or their notifications. */
class RoadCrewRecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as OsmandApplication
    private var gps: LocationServiceHelper? = null
    private var stopping = false
    private var lastStatus: RoadCrewMapObservationCoordinator.CollectionStatus? = null
    private val sourceListener = StateChangedListener<LocationSource> { handler.post { releaseGps(); refresh() } }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (!stopping) handler.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // No boot, process-redelivery, or external start can silently resume collection.
        if (intent?.action != START || !startRequested) {
            if (running !== this) stopSelf()
            return START_NOT_STICKY
        }
        startRequested = false
        if (!eligible(app) || !hasLocationPermission(app)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            stopping = false
            lastStatus = null
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = this
            app.settings.LOCATION_SOURCE.addListener(sourceListener)
            RoadCrewMapObservationCoordinator.ensureStarted(app)
            handler.post(tick)
        } catch (e: RuntimeException) {
            LOG.warn("Live Truck Map foreground start refused", e)
            finishRecording()
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (stopping) return
        if (!eligible(app) || !hasLocationPermission(app)) {
            finishRecording()
            return
        }
        RoadCrewMapObservationCoordinator.observeTripContext(app)
        if (needsOwnGps(app)) {
            if (gps == null) {
                try {
                    val helper = app.createLocationServiceHelper()
                    gps = helper
                    helper.requestLocationUpdates(object : LocationCallback() {
                        override fun onLocationResult(locations: List<Location>) {
                            // Recheck ownership: callbacks can race Home, profile and service changes.
                            if (!stopping && gps === helper && eligible(app) && needsOwnGps(app)) {
                                locations.lastOrNull()?.let {
                                    RoadCrewMapObservationCoordinator.updateLocationFromNavigationService(app, it)
                                }
                            }
                        }
                    })
                } catch (e: RuntimeException) {
                    LOG.warn("Live Truck Map GPS unavailable", e)
                    finishRecording()
                    return
                }
            }
        } else releaseGps()
        val status = RoadCrewMapObservationCoordinator.getStatus(app).status
        if (lastStatus != status) {
            lastStatus = status
            try { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification()) }
            catch (e: SecurityException) {
                // Notification visibility is not an additional collection-consent gate.
                LOG.warn("Live Truck Map notification update refused", e)
            }
        }
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, MapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val status = RoadCrewMapObservationCoordinator.getStatus(app).status
        val text = when (status) {
            RoadCrewMapObservationCoordinator.CollectionStatus.ACTIVE -> R.string.roadcrew_recording_notification_active
            RoadCrewMapObservationCoordinator.CollectionStatus.UPLOAD_ERROR -> R.string.roadcrew_live_truck_map_status_upload_error
            RoadCrewMapObservationCoordinator.CollectionStatus.UPLOAD_WARNING -> R.string.roadcrew_live_truck_map_status_upload_warning
            else -> R.string.roadcrew_live_truck_map_status_waiting_gps
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_track)
            .setContentTitle(getString(R.string.roadcrew_live_truck_map_title))
            .setContentText(getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(text)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun releaseGps() {
        val helper = gps
        gps = null
        try { helper?.removeLocationUpdates() }
        catch (e: RuntimeException) { LOG.warn("Live Truck Map GPS cleanup failed", e) }
    }

    private fun finishRecording() {
        if (stopping) return
        stopping = true
        if (running === this) running = null
        handler.removeCallbacksAndMessages(null)
        app.settings.LOCATION_SOURCE.removeListener(sourceListener)
        releaseGps()
        RoadCrewMapObservationCoordinator.recordingContextChanged(app)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        app.settings.LOCATION_SOURCE.removeListener(sourceListener)
        finishRecording()
        super.onDestroy()
    }

    companion object {
        private val LOG = PlatformUtil.getLog(RoadCrewRecordingService::class.java)
        private const val CHANNEL = "roadcrew_truck_recording_v1"
        private const val NOTIFICATION_ID = 52010
        private const val START = "org.roadcrew.app.RECORD_TRUCK"
        @Volatile private var running: RoadCrewRecordingService? = null
        private var startRequested = false
        private var profileListener: StateChangedListener<ApplicationMode>? = null

        @JvmStatic fun isRunning(): Boolean = running != null

        private fun isTruck(app: OsmandApplication): Boolean =
            RoadCrewMapObservationCoordinator.getInstance(app).isTruckProfileActive()

        private fun eligible(app: OsmandApplication): Boolean =
            app.packageName == "org.roadcrew.app" && RoadCrewMapObservationConsent.isEnabled(app) &&
                isTruck(app) && RoadCrewMapObservationCoordinator.getInstance(app).isNavigationRecordingActive &&
                !app.locationProvider.locationSimulation.isRouteAnimating

        private fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        private fun needsOwnGps(app: OsmandApplication): Boolean = RoadCrewRecordingPolicy.needsOwnGps(
            isRunning(), app.settings.MAP_ACTIVITY_ENABLED,
            app.navigationService != null || app.navigationCarAppService != null || app.carNavigationSession?.hasStarted() == true)

        @JvmStatic fun refreshFromForeground(app: OsmandApplication) {
            if (app.packageName != "org.roadcrew.app") return
            app.runInUIThread {
                if (profileListener == null) {
                    val listener = StateChangedListener<ApplicationMode> { refreshFromForeground(app) }
                    profileListener = listener
                    app.settings.APPLICATION_MODE.addListener(listener)
                }
                running?.let { it.refresh(); return@runInUIThread }
                if (startRequested || !RoadCrewRecordingPolicy.canStartService(
                        RoadCrewMapObservationConsent.isEnabled(app),
                        isTruck(app), app.locationProvider.locationSimulation.isRouteAnimating,
                        RoadCrewMapObservationCoordinator.getInstance(app).isNavigationRecordingActive,
                        app.settings.MAP_ACTIVITY_ENABLED, hasLocationPermission(app))) return@runInUIThread
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.getSystemService(NotificationManager::class.java).createNotificationChannel(
                        NotificationChannel(CHANNEL, app.getString(R.string.roadcrew_recording_channel), NotificationManager.IMPORTANCE_LOW))
                }
                startRequested = true
                try { ContextCompat.startForegroundService(app, Intent(app, RoadCrewRecordingService::class.java).setAction(START)) }
                catch (e: RuntimeException) {
                    startRequested = false
                    LOG.warn("Live Truck Map service could not start; foreground recording remains available", e)
                }
            }
        }

    }
}
