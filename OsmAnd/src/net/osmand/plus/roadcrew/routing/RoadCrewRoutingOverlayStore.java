package net.osmand.plus.roadcrew.routing;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.roadcrew.RoadCrewMapObservationConsent;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RoadCrewRoutingOverlay;

import org.apache.commons.logging.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class RoadCrewRoutingOverlayStore {

	public static final String FILE_NAME = "roadcrew-routing-overrides.json";
	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final Log log = PlatformUtil.getLog(RoadCrewRoutingOverlayStore.class);
	private static final Object LOCK = new Object();
	private static Cache cache;

	private RoadCrewRoutingOverlayStore() {
	}

	@NonNull
	public static RoadCrewRoutingOverlay.Snapshot load(@NonNull OsmandApplication app,
			@NonNull ApplicationMode mode) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())
				|| !mode.isDerivedRoutingFrom(ApplicationMode.TRUCK)
				|| !RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
			return RoadCrewRoutingOverlay.EMPTY;
		}
		File file = new File(app.getFilesDir(), FILE_NAME);
		if (!file.isFile()) {
			return RoadCrewRoutingOverlay.EMPTY;
		}
		long modified = file.lastModified();
		long length = file.length();
		// The cache keeps every well-formed override of the unchanged file; which of
		// them is in force is judged now, on every load - an expired restriction must
		// not outlive its validUntil, a future one must start at its validFrom
		// (ROADMAP 329, Codex's Test 118 review P2).
		long now = System.currentTimeMillis();
		synchronized (LOCK) {
			if (cache != null && cache.path.equals(file.getAbsolutePath())
					&& cache.modified == modified && cache.length == length) {
				return cache.snapshot.activeAt(now);
			}
			try (InputStreamReader reader = new InputStreamReader(
					new FileInputStream(file), StandardCharsets.UTF_8)) {
				RoadCrewRoutingOverlay.Snapshot snapshot = RoadCrewRoutingOverlay.parseAll(reader)
						.forProfile("truck");
				cache = new Cache(file.getAbsolutePath(), modified, length, snapshot);
				RoadCrewRoutingOverlay.Snapshot active = snapshot.activeAt(now);
				log.info("Loaded RoadCrew routing overlay " + snapshot.getRevision()
						+ " with " + snapshot.getOverrides().size() + " well-formed ("
						+ active.getOverrides().size() + " in force now) and "
						+ snapshot.getRejectedCount() + " rejected overrides");
				return active;
			} catch (Exception e) {
				// The last good file, still judged at this moment: an unreadable
				// update never keeps an expired restriction alive.
				log.error("Failed to load RoadCrew routing overlay " + file, e);
				return cache != null ? cache.snapshot.activeAt(now) : RoadCrewRoutingOverlay.EMPTY;
			}
		}
	}

	private static final class Cache {
		final String path;
		final long modified;
		final long length;
		final RoadCrewRoutingOverlay.Snapshot snapshot;

		Cache(String path, long modified, long length, RoadCrewRoutingOverlay.Snapshot snapshot) {
			this.path = path;
			this.modified = modified;
			this.length = length;
			this.snapshot = snapshot;
		}
	}
}
