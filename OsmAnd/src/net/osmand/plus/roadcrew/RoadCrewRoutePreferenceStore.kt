package net.osmand.plus.roadcrew

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.router.RoadCrewRoutePreferences

/** Reads the atomic server snapshot off the UI thread during route preparation. */
object RoadCrewRoutePreferenceStore {
    private val log = PlatformUtil.getLog(RoadCrewRoutePreferenceStore::class.java)

    @JvmStatic
    fun load(app: OsmandApplication, mode: ApplicationMode): RoadCrewRoutePreferences {
        if (app.packageName != "org.roadcrew.app"
            || !mode.isDerivedRoutingFrom(ApplicationMode.TRUCK)
            || !RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
            return RoadCrewRoutePreferences.EMPTY
        }
        val file = RoadCrewMapObservationConsent.getShadowSnapshotFile(app)
        if (!file.isFile || file.length() > 2_000_000) return RoadCrewRoutePreferences.EMPTY
        return try {
            // Re-read each calculation so expiry and revocations are not hidden by memory caching.
            file.bufferedReader(Charsets.UTF_8).use {
                RoadCrewRoutePreferences.parse(it, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            log.warn("Ignoring unavailable RoadCrew route preferences; using ordinary routing", e)
            RoadCrewRoutePreferences.EMPTY
        }
    }
}
