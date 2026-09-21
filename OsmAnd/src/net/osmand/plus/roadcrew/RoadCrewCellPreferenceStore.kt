package net.osmand.plus.roadcrew

import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import net.osmand.plus.settings.backend.ApplicationMode
import net.osmand.router.RoadCrewCellPreferences

/** Reads the rcs2 cell snapshot off the UI thread during route preparation. */
object RoadCrewCellPreferenceStore {
    private val log = PlatformUtil.getLog(RoadCrewCellPreferenceStore::class.java)

    @JvmStatic
    fun load(app: OsmandApplication, mode: ApplicationMode): RoadCrewCellPreferences {
        if (app.packageName != "org.roadcrew.app"
            || !mode.isDerivedRoutingFrom(ApplicationMode.TRUCK)
            || !RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)
        ) {
            return RoadCrewCellPreferences.EMPTY
        }
        val file = RoadCrewMapObservationConsent.getCellPreferencesFile(app)
        if (!file.isFile || file.length() > 16_000_000) return RoadCrewCellPreferences.EMPTY
        return try {
            // Re-read each calculation, like the older store: expiry and a
            // withdrawn consent must not be hidden by a cached object.
            file.bufferedReader(Charsets.UTF_8).use {
                RoadCrewCellPreferences.parse(it, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            log.warn("Ignoring unavailable RoadCrew cell preferences; using ordinary routing", e)
            RoadCrewCellPreferences.EMPTY
        }
    }
}
