package net.osmand.router;

import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded OBF route-object loader used by the RoadCrew shadow-mode segment
 * pipeline. Loading and analysis do not modify routing configuration.
 */
public final class RoadCrewObfSegmentLoader {

	public static final double MAX_RADIUS_METERS = 5_000.0;
	public static final int MAX_OBJECT_LIMIT = 20_000;

	private RoadCrewObfSegmentLoader() {
	}

	public static ShadowSnapshot analyze(BinaryMapIndexReader[] readers, double latitude, double longitude,
			double radiusMeters, int maxObjects, Cancellable cancellable) throws IOException {
		LoadResult loaded = load(readers, latitude, longitude, radiusMeters, maxObjects, cancellable);
		long started = System.nanoTime();
		List<RoadCrewSegmentIdentity.SegmentBinding> segments =
				RoadCrewSegmentIdentity.buildLogicalSegments(loaded.routeObjects);
		long analysisMillis = (System.nanoTime() - started) / 1_000_000L;
		Set<Long> osmWays = new HashSet<>();
		for (RoadCrewSegmentIdentity.SegmentBinding segment : segments) {
			osmWays.add(segment.getKey().getOsmWayId());
		}
		return new ShadowSnapshot(latitude, longitude, radiusMeters, loaded.readerCount,
				loaded.routeObjects.size(), osmWays.size(), segments, loaded.truncated,
				loaded.cancelled, loaded.loadMillis, analysisMillis);
	}

	public static LoadResult load(BinaryMapIndexReader[] readers, double latitude, double longitude,
			double radiusMeters, int maxObjects, Cancellable cancellable) throws IOException {
		validate(latitude, longitude, radiusMeters, maxObjects);
		if (readers == null || readers.length == 0) {
			return new LoadResult(Collections.emptyList(), 0, false, isCancelled(cancellable), 0);
		}
		Bounds31 bounds = Bounds31.around(latitude, longitude, radiusMeters);
		Map<Long, RouteDataObject> objects = new LinkedHashMap<>();
		Set<Long> deletedIds = new HashSet<>();
		int readerCount = 0;
		boolean[] truncated = {false};
		long started = System.nanoTime();
		for (BinaryMapIndexReader reader : readers) {
			if (isCancelled(cancellable) || objects.size() >= maxObjects) {
				truncated[0] = objects.size() >= maxObjects;
				break;
			}
			if (reader == null || !reader.containsRouteData()) {
				continue;
			}
			readerCount++;
			BinaryMapIndexReader.SearchRequest<RouteDataObject> request =
					BinaryMapIndexReader.buildSearchRouteRequest(bounds.left, bounds.right,
							bounds.top, bounds.bottom, null);
			synchronized (reader) {
				for (RouteRegion region : reader.getRoutingIndexes()) {
					if (isCancelled(cancellable) || objects.size() >= maxObjects) {
						truncated[0] = objects.size() >= maxObjects;
						break;
					}
					List<RouteSubregion> subregions = reader.searchRouteIndexTree(request, region.getSubregions());
					if (subregions.isEmpty()) {
						continue;
					}
					reader.loadRouteIndexData(subregions, new ResultMatcher<RouteDataObject>() {
						@Override
						public boolean publish(RouteDataObject object) {
							if (object == null || object.pointsX == null || object.pointsY == null
									|| object.getPointsLength() < 2 || !bounds.intersects(object)) {
								return false;
							}
							if (object.isRoadDeleted()) {
								deletedIds.add(object.getId());
								objects.remove(object.getId());
								return false;
							}
							if (!deletedIds.contains(object.getId())) {
								objects.putIfAbsent(object.getId(), object);
							}
							if (objects.size() >= maxObjects) {
								truncated[0] = true;
							}
							return true;
						}

						@Override
						public boolean isCancelled() {
							return RoadCrewObfSegmentLoader.isCancelled(cancellable)
									|| objects.size() >= maxObjects;
						}
					});
				}
			}
		}
		long loadMillis = (System.nanoTime() - started) / 1_000_000L;
		return new LoadResult(new ArrayList<>(objects.values()), readerCount, truncated[0],
				isCancelled(cancellable), loadMillis);
	}

	private static boolean isCancelled(Cancellable cancellable) {
		return cancellable != null && cancellable.isCancelled();
	}

	private static void validate(double latitude, double longitude, double radiusMeters, int maxObjects) {
		if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
				|| !Double.isFinite(longitude) || longitude < -180 || longitude > 180
				|| !Double.isFinite(radiusMeters) || radiusMeters <= 0 || radiusMeters > MAX_RADIUS_METERS
				|| maxObjects <= 0 || maxObjects > MAX_OBJECT_LIMIT) {
			throw new IllegalArgumentException("Invalid RoadCrew OBF shadow-load request");
		}
	}

	public interface Cancellable {
		boolean isCancelled();
	}

	public static final class LoadResult {
		private final List<RouteDataObject> routeObjects;
		private final int readerCount;
		private final boolean truncated;
		private final boolean cancelled;
		private final long loadMillis;

		private LoadResult(List<RouteDataObject> routeObjects, int readerCount, boolean truncated,
				boolean cancelled, long loadMillis) {
			this.routeObjects = Collections.unmodifiableList(routeObjects);
			this.readerCount = readerCount;
			this.truncated = truncated;
			this.cancelled = cancelled;
			this.loadMillis = loadMillis;
		}

		public List<RouteDataObject> getRouteObjects() {
			return routeObjects;
		}

		public int getReaderCount() {
			return readerCount;
		}

		public boolean isTruncated() {
			return truncated;
		}

		public boolean isCancelled() {
			return cancelled;
		}

		public long getLoadMillis() {
			return loadMillis;
		}
	}

	public static final class ShadowSnapshot {
		private final double latitude;
		private final double longitude;
		private final double radiusMeters;
		private final int readerCount;
		private final int routeObjectCount;
		private final int osmWayCount;
		private final List<RoadCrewSegmentIdentity.SegmentBinding> segments;
		private final boolean truncated;
		private final boolean cancelled;
		private final long loadMillis;
		private final long analysisMillis;

		private ShadowSnapshot(double latitude, double longitude, double radiusMeters, int readerCount,
				int routeObjectCount, int osmWayCount,
				List<RoadCrewSegmentIdentity.SegmentBinding> segments, boolean truncated,
				boolean cancelled, long loadMillis, long analysisMillis) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.radiusMeters = radiusMeters;
			this.readerCount = readerCount;
			this.routeObjectCount = routeObjectCount;
			this.osmWayCount = osmWayCount;
			this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
			this.truncated = truncated;
			this.cancelled = cancelled;
			this.loadMillis = loadMillis;
			this.analysisMillis = analysisMillis;
		}

		public double getLatitude() {
			return latitude;
		}

		public double getLongitude() {
			return longitude;
		}

		public double getRadiusMeters() {
			return radiusMeters;
		}

		public int getReaderCount() {
			return readerCount;
		}

		public int getRouteObjectCount() {
			return routeObjectCount;
		}

		public int getOsmWayCount() {
			return osmWayCount;
		}

		public List<RoadCrewSegmentIdentity.SegmentBinding> getSegments() {
			return segments;
		}

		public boolean isTruncated() {
			return truncated;
		}

		public boolean isCancelled() {
			return cancelled;
		}

		public long getLoadMillis() {
			return loadMillis;
		}

		public long getAnalysisMillis() {
			return analysisMillis;
		}
	}

	private static final class Bounds31 {
		private final int left;
		private final int right;
		private final int top;
		private final int bottom;

		private Bounds31(int left, int right, int top, int bottom) {
			this.left = left;
			this.right = right;
			this.top = top;
			this.bottom = bottom;
		}

		private static Bounds31 around(double latitude, double longitude, double radiusMeters) {
			double latitudeDelta = radiusMeters / 111_320.0;
			double longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(latitude)));
			double longitudeDelta = radiusMeters / (111_320.0 * longitudeScale);
			return new Bounds31(
					MapUtils.get31TileNumberX(longitude - longitudeDelta),
					MapUtils.get31TileNumberX(longitude + longitudeDelta),
					MapUtils.get31TileNumberY(latitude + latitudeDelta),
					MapUtils.get31TileNumberY(latitude - latitudeDelta));
		}

		private boolean intersects(RouteDataObject object) {
			int objectLeft = Integer.MAX_VALUE;
			int objectRight = Integer.MIN_VALUE;
			int objectTop = Integer.MAX_VALUE;
			int objectBottom = Integer.MIN_VALUE;
			for (int i = 0; i < object.getPointsLength(); i++) {
				objectLeft = Math.min(objectLeft, object.getPoint31XTile(i));
				objectRight = Math.max(objectRight, object.getPoint31XTile(i));
				objectTop = Math.min(objectTop, object.getPoint31YTile(i));
				objectBottom = Math.max(objectBottom, object.getPoint31YTile(i));
			}
			return objectRight >= left && objectLeft <= right && objectBottom >= top && objectTop <= bottom;
		}
	}
}
