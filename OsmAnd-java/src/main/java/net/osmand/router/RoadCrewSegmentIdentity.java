package net.osmand.router;

import net.osmand.binary.ObfConstants;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stable RoadCrew identity for one directed part of an OSM way and a fail-closed
 * resolver for the temporary road ID and point indexes in a particular OBF map.
 */
public final class RoadCrewSegmentIdentity {

	public static final int KEY_VERSION = 1;
	public static final double DEFAULT_ENDPOINT_TOLERANCE_METERS = 20.0;
	public static final double DEFAULT_AMBIGUITY_MARGIN_METERS = 10.0;
	private static final double MAX_LENGTH_DELTA_RATIO = 0.20;
	private static final double MIN_LENGTH_DELTA_METERS = 30.0;
	private static final double COORDINATE_SCALE = 1_000_000.0;
	private static final double FINGERPRINT_SCALE = 100_000.0;

	private RoadCrewSegmentIdentity() {
	}

	public static SegmentKey create(RouteDataObject road, int startPointIndex, int endPointIndex) {
		validateRoadRange(road, startPointIndex, endPointIndex);
		long osmWayId = ObfConstants.getOsmObjectId(road);
		if (osmWayId <= 0) {
			throw new IllegalArgumentException("RoadCrew segment requires a positive OSM way ID");
		}
		return new SegmentKey(KEY_VERSION, osmWayId, regionName(road),
				latitude(road, startPointIndex), longitude(road, startPointIndex),
				latitude(road, endPointIndex), longitude(road, endPointIndex),
				fingerprint(road, startPointIndex, endPointIndex),
				pathLengthMeters(road, startPointIndex, endPointIndex));
	}

	/**
	 * Splits loaded route objects at endpoints and shared graph coordinates. Shape
	 * points between graph boundaries remain part of the same logical segment.
	 */
	public static List<SegmentBinding> buildLogicalSegments(Iterable<RouteDataObject> roads) {
		if (roads == null) {
			return Collections.emptyList();
		}
		List<RouteDataObject> materialized = new ArrayList<>();
		Map<Long, Set<Long>> roadsByCoordinate = new HashMap<>();
		for (RouteDataObject road : roads) {
			if (road == null || road.pointsX == null || road.pointsY == null || road.getPointsLength() < 2
					|| ObfConstants.getOsmObjectId(road) <= 0) {
				continue;
			}
			materialized.add(road);
			Set<Long> coordinatesOnRoad = new HashSet<>();
			for (int i = 0; i < road.getPointsLength(); i++) {
				coordinatesOnRoad.add(coordinateKey(road, i));
			}
			for (long coordinate : coordinatesOnRoad) {
				roadsByCoordinate.computeIfAbsent(coordinate, ignored -> new HashSet<>()).add(road.getId());
			}
		}

		List<SegmentBinding> result = new ArrayList<>();
		for (RouteDataObject road : materialized) {
			List<Integer> boundaries = new ArrayList<>();
			boundaries.add(0);
			for (int i = 1; i < road.getPointsLength() - 1; i++) {
				Set<Long> touchingRoads = roadsByCoordinate.get(coordinateKey(road, i));
				if (touchingRoads != null && touchingRoads.size() > 1) {
					boundaries.add(i);
				}
			}
			boundaries.add(road.getPointsLength() - 1);
			int oneway = road.getOneway();
			for (int i = 0; i < boundaries.size() - 1; i++) {
				int start = boundaries.get(i);
				int end = boundaries.get(i + 1);
				if (oneway >= 0) {
					result.add(new SegmentBinding(create(road, start, end), road.getId(), start, end));
				}
				if (oneway <= 0) {
					result.add(new SegmentBinding(create(road, end, start), road.getId(), end, start));
				}
			}
		}
		return Collections.unmodifiableList(result);
	}

	public static SegmentKey key(int version, long osmWayId, String region,
			double fromLatitude, double fromLongitude, double toLatitude, double toLongitude,
			String geometryFingerprint, double lengthMeters) {
		return new SegmentKey(version, osmWayId, region, fromLatitude, fromLongitude,
				toLatitude, toLongitude, geometryFingerprint, lengthMeters);
	}

	public static Resolution resolve(SegmentKey key, Iterable<RouteDataObject> roads) {
		return resolve(key, roads, DEFAULT_ENDPOINT_TOLERANCE_METERS, DEFAULT_AMBIGUITY_MARGIN_METERS);
	}

	public static Resolution resolve(SegmentKey key, Iterable<RouteDataObject> roads,
			double endpointToleranceMeters, double ambiguityMarginMeters) {
		if (key == null || key.version != KEY_VERSION || roads == null
				|| !Double.isFinite(endpointToleranceMeters) || endpointToleranceMeters <= 0
				|| !Double.isFinite(ambiguityMarginMeters) || ambiguityMarginMeters < 0) {
			return Resolution.unresolved(Status.INVALID_KEY, 0);
		}
		Map<String, Candidate> unique = new LinkedHashMap<>();
		for (RouteDataObject road : roads) {
			collectCandidates(key, road, endpointToleranceMeters, unique);
		}
		if (unique.isEmpty()) {
			return Resolution.unresolved(Status.NOT_FOUND, 0);
		}
		List<Candidate> candidates = new ArrayList<>(unique.values());
		List<Candidate> exact = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (key.geometryFingerprint.equals(candidate.fingerprint)) {
				exact.add(candidate);
			}
		}
		if (exact.size() == 1) {
			return exact.get(0).toResolution(Status.EXACT, candidates.size());
		}
		if (exact.size() > 1) {
			return Resolution.unresolved(Status.AMBIGUOUS, candidates.size());
		}

		Collections.sort(candidates, Comparator.comparingDouble(candidate -> candidate.score));
		if (candidates.size() > 1
				&& candidates.get(1).score - candidates.get(0).score < ambiguityMarginMeters) {
			return Resolution.unresolved(Status.AMBIGUOUS, candidates.size());
		}
		return candidates.get(0).toResolution(Status.REMAPPED, candidates.size());
	}

	private static void collectCandidates(SegmentKey key, RouteDataObject road, double endpointToleranceMeters,
			Map<String, Candidate> candidates) {
		if (road == null || road.pointsX == null || road.pointsY == null || road.getPointsLength() < 2
				|| ObfConstants.getOsmObjectId(road) != key.osmWayId || !regionMatches(key.region, regionName(road))) {
			return;
		}
		List<Integer> starts = matchingPointIndexes(road, key.fromLatitude, key.fromLongitude, endpointToleranceMeters);
		List<Integer> ends = matchingPointIndexes(road, key.toLatitude, key.toLongitude, endpointToleranceMeters);
		for (int start : starts) {
			for (int end : ends) {
				if (start == end) {
					continue;
				}
				double length = pathLengthMeters(road, start, end);
				double lengthDelta = Math.abs(length - key.lengthMeters);
				double allowedLengthDelta = Math.max(MIN_LENGTH_DELTA_METERS,
						key.lengthMeters * MAX_LENGTH_DELTA_RATIO);
				if (lengthDelta > allowedLengthDelta) {
					continue;
				}
				double startError = MapUtils.getDistance(key.fromLatitude, key.fromLongitude,
						latitude(road, start), longitude(road, start));
				double endError = MapUtils.getDistance(key.toLatitude, key.toLongitude,
						latitude(road, end), longitude(road, end));
				Candidate candidate = new Candidate(road, start, end,
						fingerprint(road, start, end), startError + endError + lengthDelta * 0.25);
				candidates.putIfAbsent(candidate.identity(), candidate);
			}
		}
	}

	private static List<Integer> matchingPointIndexes(RouteDataObject road, double latitude, double longitude,
			double toleranceMeters) {
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < road.getPointsLength(); i++) {
			if (MapUtils.getDistance(latitude, longitude, latitude(road, i), longitude(road, i)) <= toleranceMeters) {
				indexes.add(i);
			}
		}
		return indexes;
	}

	private static String fingerprint(RouteDataObject road, int startPointIndex, int endPointIndex) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			int step = startPointIndex < endPointIndex ? 1 : -1;
			for (int i = startPointIndex; ; i += step) {
				String point = Math.round(latitude(road, i) * FINGERPRINT_SCALE) + ","
						+ Math.round(longitude(road, i) * FINGERPRINT_SCALE) + ";";
				digest.update(point.getBytes(StandardCharsets.US_ASCII));
				if (i == endPointIndex) {
					break;
				}
			}
			byte[] hash = digest.digest();
			StringBuilder result = new StringBuilder(32);
			for (int i = 0; i < 16; i++) {
				result.append(String.format(Locale.US, "%02x", hash[i] & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private static double pathLengthMeters(RouteDataObject road, int startPointIndex, int endPointIndex) {
		double length = 0;
		int step = startPointIndex < endPointIndex ? 1 : -1;
		for (int i = startPointIndex; i != endPointIndex; i += step) {
			int next = i + step;
			length += MapUtils.getDistance(latitude(road, i), longitude(road, i),
					latitude(road, next), longitude(road, next));
		}
		return length;
	}

	private static void validateRoadRange(RouteDataObject road, int startPointIndex, int endPointIndex) {
		if (road == null || road.pointsX == null || road.pointsY == null
				|| startPointIndex < 0 || endPointIndex < 0
				|| startPointIndex >= road.getPointsLength() || endPointIndex >= road.getPointsLength()
				|| startPointIndex == endPointIndex) {
			throw new IllegalArgumentException("Invalid RoadCrew segment range");
		}
	}

	private static double latitude(RouteDataObject road, int index) {
		return MapUtils.get31LatitudeY(road.getPoint31YTile(index));
	}

	private static double longitude(RouteDataObject road, int index) {
		return MapUtils.get31LongitudeX(road.getPoint31XTile(index));
	}

	private static String regionName(RouteDataObject road) {
		return road.region == null || road.region.getName() == null ? "" : road.region.getName().trim();
	}

	private static long coordinateKey(RouteDataObject road, int index) {
		return ((long) road.getPoint31XTile(index) << 32) ^ (road.getPoint31YTile(index) & 0xffffffffL);
	}

	private static boolean regionMatches(String expected, String actual) {
		return expected.isEmpty() || actual.isEmpty() || expected.equalsIgnoreCase(actual);
	}

	private static boolean isCoordinate(double latitude, double longitude) {
		return Double.isFinite(latitude) && Double.isFinite(longitude)
				&& latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
	}

	public enum Status {
		EXACT,
		REMAPPED,
		NOT_FOUND,
		AMBIGUOUS,
		INVALID_KEY
	}

	public static final class SegmentKey {
		private final int version;
		private final long osmWayId;
		private final String region;
		private final double fromLatitude;
		private final double fromLongitude;
		private final double toLatitude;
		private final double toLongitude;
		private final String geometryFingerprint;
		private final double lengthMeters;
		private final String canonicalId;

		private SegmentKey(int version, long osmWayId, String region,
				double fromLatitude, double fromLongitude, double toLatitude, double toLongitude,
				String geometryFingerprint, double lengthMeters) {
			if (version != KEY_VERSION || osmWayId <= 0
					|| !isCoordinate(fromLatitude, fromLongitude) || !isCoordinate(toLatitude, toLongitude)
					|| geometryFingerprint == null || !geometryFingerprint.matches("[0-9a-fA-F]{32}")
					|| !Double.isFinite(lengthMeters) || lengthMeters <= 0) {
				throw new IllegalArgumentException("Invalid RoadCrew segment key");
			}
			this.version = version;
			this.osmWayId = osmWayId;
			this.region = region == null ? "" : region.trim();
			this.fromLatitude = roundCoordinate(fromLatitude);
			this.fromLongitude = roundCoordinate(fromLongitude);
			this.toLatitude = roundCoordinate(toLatitude);
			this.toLongitude = roundCoordinate(toLongitude);
			this.geometryFingerprint = geometryFingerprint.toLowerCase(Locale.US);
			this.lengthMeters = lengthMeters;
			this.canonicalId = String.format(Locale.US, "rcs%d:%d:%d,%d:%d,%d", version, osmWayId,
					Math.round(this.fromLatitude * FINGERPRINT_SCALE),
					Math.round(this.fromLongitude * FINGERPRINT_SCALE),
					Math.round(this.toLatitude * FINGERPRINT_SCALE),
					Math.round(this.toLongitude * FINGERPRINT_SCALE));
		}

		private static double roundCoordinate(double coordinate) {
			return Math.round(coordinate * COORDINATE_SCALE) / COORDINATE_SCALE;
		}

		public int getVersion() {
			return version;
		}

		public long getOsmWayId() {
			return osmWayId;
		}

		public String getRegion() {
			return region;
		}

		public double getFromLatitude() {
			return fromLatitude;
		}

		public double getFromLongitude() {
			return fromLongitude;
		}

		public double getToLatitude() {
			return toLatitude;
		}

		public double getToLongitude() {
			return toLongitude;
		}

		public String getGeometryFingerprint() {
			return geometryFingerprint;
		}

		public double getLengthMeters() {
			return lengthMeters;
		}

		public String getCanonicalId() {
			return canonicalId;
		}
	}

	public static final class Resolution {
		private final Status status;
		private final long roadId;
		private final int startPointIndex;
		private final int endPointIndex;
		private final int candidateCount;
		private final double score;

		private Resolution(Status status, long roadId, int startPointIndex, int endPointIndex,
				int candidateCount, double score) {
			this.status = status;
			this.roadId = roadId;
			this.startPointIndex = startPointIndex;
			this.endPointIndex = endPointIndex;
			this.candidateCount = candidateCount;
			this.score = score;
		}

		private static Resolution unresolved(Status status, int candidateCount) {
			return new Resolution(status, 0, -1, -1, candidateCount, Double.NaN);
		}

		public boolean isResolved() {
			return status == Status.EXACT || status == Status.REMAPPED;
		}

		public Status getStatus() {
			return status;
		}

		public long getRoadId() {
			return roadId;
		}

		public int getStartPointIndex() {
			return startPointIndex;
		}

		public int getEndPointIndex() {
			return endPointIndex;
		}

		public int getCandidateCount() {
			return candidateCount;
		}

		public double getScore() {
			return score;
		}
	}

	public static final class SegmentBinding {
		private final SegmentKey key;
		private final long roadId;
		private final int startPointIndex;
		private final int endPointIndex;

		private SegmentBinding(SegmentKey key, long roadId, int startPointIndex, int endPointIndex) {
			this.key = key;
			this.roadId = roadId;
			this.startPointIndex = startPointIndex;
			this.endPointIndex = endPointIndex;
		}

		public SegmentKey getKey() {
			return key;
		}

		public long getRoadId() {
			return roadId;
		}

		public int getStartPointIndex() {
			return startPointIndex;
		}

		public int getEndPointIndex() {
			return endPointIndex;
		}
	}

	private static final class Candidate {
		private final RouteDataObject road;
		private final int startPointIndex;
		private final int endPointIndex;
		private final String fingerprint;
		private final double score;

		private Candidate(RouteDataObject road, int startPointIndex, int endPointIndex,
				String fingerprint, double score) {
			this.road = road;
			this.startPointIndex = startPointIndex;
			this.endPointIndex = endPointIndex;
			this.fingerprint = fingerprint;
			this.score = score;
		}

		private String identity() {
			return road.getId() + ":" + startPointIndex + ":" + endPointIndex + ":" + fingerprint;
		}

		private Resolution toResolution(Status status, int candidateCount) {
			return new Resolution(status, road.getId(), startPointIndex, endPointIndex, candidateCount, score);
		}
	}
}
