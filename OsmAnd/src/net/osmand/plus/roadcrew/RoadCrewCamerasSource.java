package net.osmand.plus.roadcrew;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapDataObject;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapIndexReader.SearchPoiTypeFilter;
import net.osmand.binary.ObfConstants;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.map.OsmandRegions;
import net.osmand.osm.PoiCategory;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.resources.ResourceManager.ResourceListener;
import net.osmand.search.core.AmenityIndexRepository;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The stationary speed cameras around a point, read from the maps on the phone
 * (OpenStreetMap highway=speed_camera; 72 in the Bulgaria_europe.obf of
 * 01.09.2026). No server: a newer map brings newer cameras. Each camera gets
 * its country's rule once, when it is read (RoadCrewCameras.ruleForRegions).
 * Reading runs in the background; until it is done, the last cameras read stay.
 */
final class RoadCrewCamerasSource {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewCamerasSource.class);
	private static final String SPEED_CAMERA = "speed_camera";
	/** Read this far around the point - far more than any warning needs. */
	private static final double READ_RADIUS_METERS = 15_000;
	/** Read again once the point comes this close to the edge of what was read. */
	private static final double EDGE_METERS = 5_000;
	/** The truck's country is looked up again after this long or this far. */
	private static final long COUNTRY_INTERVAL_MILLIS = 30 * 1000L;
	private static final double COUNTRY_MOVE_METERS = 2_000;
	private static final int SEARCH_ZOOM = 15;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	private static final SearchPoiTypeFilter CAMERAS_ONLY = new SearchPoiTypeFilter() {
		@Override
		public boolean accept(PoiCategory type, String subcategory) {
			return SPEED_CAMERA.equals(subcategory);
		}

		@Override
		public boolean isEmpty() {
			return false;
		}
	};

	private static final class Area {
		final double centerLat;
		final double centerLon;
		final List<RoadCrewCameras.Camera> cameras;

		Area(double centerLat, double centerLon, List<RoadCrewCameras.Camera> cameras) {
			this.centerLat = centerLat;
			this.centerLon = centerLon;
			this.cameras = cameras;
		}

		boolean covers(double lat, double lon) {
			return MapUtils.getDistance(centerLat, centerLon, lat, lon) <= READ_RADIUS_METERS - EDGE_METERS;
		}
	}

	private static final RoadCrewCameraCache<Area> truckCache = new RoadCrewCameraCache<>();
	private static final RoadCrewCameraCache<Area> viewCache = new RoadCrewCameraCache<>();
	private static boolean listeningForMaps;
	@Nullable
	private static volatile RoadCrewCameras.Country truckCountry;
	private static long truckCountryAt;
	private static double truckCountryLat;
	private static double truckCountryLon;
	private static boolean truckCountryReading;

	private RoadCrewCamerasSource() {
	}

	/** The cameras around the truck; read again in the background as it drives on. */
	@NonNull
	static List<RoadCrewCameras.Camera> aroundTruck(@NonNull OsmandApplication app, double lat, double lon) {
		return around(app, truckCache, lat, lon);
	}

	/** The cameras to draw around the map's centre - the truck's, while the map is there. */
	@NonNull
	static List<RoadCrewCameras.Camera> aroundView(@NonNull OsmandApplication app, double lat, double lon) {
		listenForMaps(app);
		Area truck = truckCache.get();
		if (truck != null && truck.covers(lat, lon)) {
			return truck.cameras;
		}
		return around(app, viewCache, lat, lon);
	}

	private static List<RoadCrewCameras.Camera> around(OsmandApplication app,
			RoadCrewCameraCache<Area> cache, double lat, double lon) {
		listenForMaps(app);
		Area area = cache.get();
		if (area == null || !area.covers(lat, lon)) {
			long ticket = cache.begin(SystemClock.elapsedRealtime());
			if (ticket >= 0) {
				EXECUTOR.execute(() -> {
					Area result = null;
					try {
						List<RoadCrewCameras.Camera> cameras = read(app, lat, lon);
						if (cameras != null) {
							result = new Area(lat, lon, cameras);
						}
					} finally {
						cache.complete(ticket, result, SystemClock.elapsedRealtime());
					}
				});
			}
		}
		return area == null ? Collections.emptyList() : area.cameras;
	}

	private static synchronized void listenForMaps(OsmandApplication app) {
		if (listeningForMaps) {
			return;
		}
		listeningForMaps = true;
		app.getResourceManager().addResourceListener(new ResourceListener() {
			@Override public void onMapsIndexed() { invalidateMaps(); }
			@Override public void onReaderIndexed(BinaryMapIndexReader reader) { invalidateMaps(); }
			@Override public void onReaderClosed(BinaryMapIndexReader reader) { invalidateMaps(); }
			@Override public void onMapClosed(String name) { invalidateMaps(); }
		});
	}

	private static void invalidateMaps() {
		truckCache.invalidate();
		viewCache.invalidate();
	}

	/** The country the truck is in; null until it is known. */
	@Nullable
	static RoadCrewCameras.Country truckCountry(@NonNull OsmandApplication app, double lat, double lon) {
		long now = System.currentTimeMillis();
		synchronized (RoadCrewCamerasSource.class) {
			boolean due = now - truckCountryAt >= COUNTRY_INTERVAL_MILLIS
					|| MapUtils.getDistance(truckCountryLat, truckCountryLon, lat, lon) >= COUNTRY_MOVE_METERS;
			if (due && !truckCountryReading) {
				truckCountryReading = true;
				truckCountryAt = now;
				truckCountryLat = lat;
				truckCountryLon = lon;
				EXECUTOR.execute(() -> {
					try {
						truckCountry = RoadCrewCameras.countryForRegions(regionNames(app.getRegions(), lat, lon));
					} catch (IOException | RuntimeException e) {
						truckCountry = null;
						LOG.warn("RoadCrew cameras: country lookup failed", e);
					} finally {
						synchronized (RoadCrewCamerasSource.class) {
							truckCountryReading = false;
						}
					}
				});
			}
		}
		return truckCountry;
	}

	/** Null until the countries' borders are loaded: a French camera would get no rule. */
	@Nullable
	private static List<RoadCrewCameras.Camera> read(@NonNull OsmandApplication app, double lat, double lon) {
		OsmandRegions regions = app.getRegions();
		if (regions == null || !regions.isInitialized()) {
			return null;
		}
		double latSpan = READ_RADIUS_METERS / 111_320.0;
		double lonSpan = READ_RADIUS_METERS / (111_320.0 * Math.max(0.1, Math.cos(Math.toRadians(lat))));
		int top = MapUtils.get31TileNumberY(lat + latSpan);
		int bottom = MapUtils.get31TileNumberY(lat - latSpan);
		int left = MapUtils.get31TileNumberX(lon - lonSpan);
		int right = MapUtils.get31TileNumberX(lon + lonSpan);
		// The same camera is in two maps where they meet at a border.
		Map<Long, RoadCrewCameras.Camera> byOsmId = new LinkedHashMap<>();
		try {
			for (AmenityIndexRepository repository : app.getResourceManager().getAmenityRepositories()) {
				if (repository.isWorldMap() || !repository.checkContainsInt(top, left, bottom, right)) {
					continue;
				}
				List<Amenity> found = repository.searchAmenities(top, left, bottom, right, SEARCH_ZOOM,
						CAMERAS_ONLY, null, null, null, 0);
				if (found == null) {
					continue;
				}
				for (Amenity amenity : found) {
					long osmId = ObfConstants.getOsmObjectId(amenity);
					LatLon location = amenity.getLocation();
					if (byOsmId.containsKey(osmId) || location == null) {
						continue;
					}
					String limit = amenity.getAdditionalInfo("maxspeed");
					if (limit == null) {
						limit = amenity.getAdditionalInfo("enforcement_maxspeed");
					}
					RoadCrewCameras.Rule rule = RoadCrewCameras.ruleForRegions(
							regionNames(regions, location.getLatitude(), location.getLongitude()));
					byOsmId.put(osmId, new RoadCrewCameras.Camera("osm-" + osmId, location.getLatitude(),
							location.getLongitude(), RoadCrewCameras.parseLimit(limit), rule));
				}
			}
		} catch (IOException | RuntimeException e) {
			// A map being replaced by a download: the next read finds it again.
			LOG.warn("RoadCrew cameras: " + e.getMessage());
			return null;
		}
		return Collections.unmodifiableList(new ArrayList<>(byOsmId.values()));
	}

	/** The names of the map regions the point lies in, "europe_germany" and the like. */
	@NonNull
	private static List<String> regionNames(@Nullable OsmandRegions regions, double lat, double lon) throws IOException {
		if (regions == null || !regions.isInitialized()) {
			return Collections.emptyList();
		}
		int x = MapUtils.get31TileNumberX(lon);
		int y = MapUtils.get31TileNumberY(lat);
		List<String> names = new ArrayList<>();
		List<BinaryMapDataObject> found = regions.filterQueryResultsByPoint(regions.query(x, x, y, y), x, y);
		for (BinaryMapDataObject region : found) {
			String name = regions.getFullName(region);
			if (name != null) {
				names.add(name);
			}
		}
		return names;
	}
}
