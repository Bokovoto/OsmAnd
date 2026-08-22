package net.osmand.plus.roadcrew.routing;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
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
		if (!ROADCREW_PACKAGE.equals(app.getPackageName()) || !mode.isDerivedRoutingFrom(ApplicationMode.TRUCK)) {
			return RoadCrewRoutingOverlay.EMPTY;
		}
		File file = new File(app.getFilesDir(), FILE_NAME);
		if (!file.isFile()) {
			return RoadCrewRoutingOverlay.EMPTY;
		}
		long modified = file.lastModified();
		long length = file.length();
		synchronized (LOCK) {
			if (cache != null && cache.path.equals(file.getAbsolutePath())
					&& cache.modified == modified && cache.length == length) {
				return cache.snapshot;
			}
			try (InputStreamReader reader = new InputStreamReader(
					new FileInputStream(file), StandardCharsets.UTF_8)) {
				RoadCrewRoutingOverlay.Snapshot snapshot = RoadCrewRoutingOverlay.parse(reader, System.currentTimeMillis())
						.forProfile("truck");
				cache = new Cache(file.getAbsolutePath(), modified, length, snapshot);
				log.info("Loaded RoadCrew routing overlay " + snapshot.getRevision()
						+ " with " + snapshot.getOverrides().size() + " active and "
						+ snapshot.getRejectedCount() + " rejected overrides");
				return snapshot;
			} catch (Exception e) {
				log.error("Failed to load RoadCrew routing overlay " + file, e);
				return cache != null ? cache.snapshot : RoadCrewRoutingOverlay.EMPTY;
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
