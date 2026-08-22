package net.osmand.router;

import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed matcher from one accepted moving GPS fix to one directed
 * RoadCrew road segment. It does not persist or upload the fix.
 */
public final class RoadCrewSegmentMatcher {

	public static final double MIN_SPEED_METERS_PER_SECOND = 2.0;
	public static final double MAX_ACCEPTED_ACCURACY_METERS = 35.0;
	public static final double MAX_HEADING_DIFFERENCE_DEGREES = 50.0;
	public static final double AMBIGUITY_SCORE_MARGIN = 6.0;
	private static final double MIN_DISTANCE_LIMIT_METERS = 12.0;
	private static final double MAX_DISTANCE_LIMIT_METERS = 40.0;
	private static final double HEADING_SCORE_WEIGHT = 0.25;

	private RoadCrewSegmentMatcher() {
	}

	public static PreparedSegments prepare(Iterable<RouteDataObject> roads) {
		if (roads == null) {
			return new PreparedSegments(Collections.emptyList());
		}
		Map<Long, RouteDataObject> roadsById = new LinkedHashMap<>();
		for (RouteDataObject road : roads) {
			if (road != null && road.pointsX != null && road.pointsY != null && road.getPointsLength() >= 2) {
				roadsById.putIfAbsent(road.getId(), road);
			}
		}
		List<RoadCrewSegmentIdentity.SegmentBinding> bindings =
				RoadCrewSegmentIdentity.buildLogicalSegments(roadsById.values());
		Map<String, CandidateGeometry> unique = new LinkedHashMap<>();
		for (RoadCrewSegmentIdentity.SegmentBinding binding : bindings) {
			RouteDataObject road = roadsById.get(binding.getRoadId());
			if (road == null) {
				continue;
			}
			String identity = binding.getKey().getCanonicalId() + ":"
					+ binding.getKey().getGeometryFingerprint();
			unique.putIfAbsent(identity, new CandidateGeometry(binding, road));
		}
		return new PreparedSegments(new ArrayList<>(unique.values()));
	}

	public static MatchResult match(GpsFix fix, Iterable<RouteDataObject> roads) {
		return prepare(roads).match(fix);
	}

	public enum Status {
		MATCHED,
		INVALID_FIX,
		LOW_SPEED,
		POOR_ACCURACY,
		NO_SEGMENTS,
		NO_NEARBY_SEGMENT,
		DIRECTION_MISMATCH,
		AMBIGUOUS
	}

	public static final class GpsFix {
		private final double latitude;
		private final double longitude;
		private final double accuracyMeters;
		private final double speedMetersPerSecond;
		private final double bearingDegrees;

		public GpsFix(double latitude, double longitude, double accuracyMeters,
				double speedMetersPerSecond, double bearingDegrees) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.accuracyMeters = accuracyMeters;
			this.speedMetersPerSecond = speedMetersPerSecond;
			this.bearingDegrees = bearingDegrees;
		}

		public double getLatitude() {
			return latitude;
		}

		public double getLongitude() {
			return longitude;
		}

		public double getAccuracyMeters() {
			return accuracyMeters;
		}

		public double getSpeedMetersPerSecond() {
			return speedMetersPerSecond;
		}

		public double getBearingDegrees() {
			return bearingDegrees;
		}
	}

	public static final class PreparedSegments {
		private final List<CandidateGeometry> candidates;

		private PreparedSegments(List<CandidateGeometry> candidates) {
			this.candidates = Collections.unmodifiableList(candidates);
		}

		public int size() {
			return candidates.size();
		}

		public MatchResult match(GpsFix fix) {
			if (!isValidFix(fix)) {
				return MatchResult.unmatched(Status.INVALID_FIX, 0, 0);
			}
			if (fix.accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS) {
				return MatchResult.unmatched(Status.POOR_ACCURACY, 0, 0);
			}
			if (fix.speedMetersPerSecond < MIN_SPEED_METERS_PER_SECOND) {
				return MatchResult.unmatched(Status.LOW_SPEED, 0, 0);
			}
			if (candidates.isEmpty()) {
				return MatchResult.unmatched(Status.NO_SEGMENTS, 0, 0);
			}

			double distanceLimit = Math.min(MAX_DISTANCE_LIMIT_METERS,
					Math.max(MIN_DISTANCE_LIMIT_METERS, fix.accuracyMeters + 8.0));
			List<ScoredCandidate> eligible = new ArrayList<>();
			int nearbyCount = 0;
			for (CandidateGeometry candidate : candidates) {
				NearestEdge nearest = candidate.nearestEdge(fix.latitude, fix.longitude);
				if (nearest == null || nearest.distanceMeters > distanceLimit) {
					continue;
				}
				nearbyCount++;
				double headingDifference = Math.abs(MapUtils.degreesDiff(
						fix.bearingDegrees, nearest.bearingDegrees));
				if (headingDifference > MAX_HEADING_DIFFERENCE_DEGREES) {
					continue;
				}
				double score = nearest.distanceMeters + headingDifference * HEADING_SCORE_WEIGHT;
				eligible.add(new ScoredCandidate(candidate.binding, nearest.distanceMeters,
						headingDifference, nearest.progressMeters, score));
			}
			if (eligible.isEmpty()) {
				return MatchResult.unmatched(nearbyCount == 0
						? Status.NO_NEARBY_SEGMENT : Status.DIRECTION_MISMATCH, nearbyCount, 0);
			}
			eligible.sort(Comparator.comparingDouble(candidate -> candidate.score));
			ScoredCandidate best = eligible.get(0);
			if (eligible.size() > 1
					&& eligible.get(1).score - best.score < AMBIGUITY_SCORE_MARGIN) {
				return MatchResult.ambiguous(best, nearbyCount, eligible.size());
			}
			return MatchResult.matched(best, nearbyCount, eligible.size());
		}
	}

	public static final class MatchResult {
		private final Status status;
		private final RoadCrewSegmentIdentity.SegmentBinding segment;
		private final double distanceMeters;
		private final double headingDifferenceDegrees;
		private final double progressMeters;
		private final double segmentLengthMeters;
		private final double score;
		private final int nearbyCandidateCount;
		private final int directionCandidateCount;

		private MatchResult(Status status, RoadCrewSegmentIdentity.SegmentBinding segment,
				double distanceMeters, double headingDifferenceDegrees,
				double progressMeters, double segmentLengthMeters, double score,
				int nearbyCandidateCount, int directionCandidateCount) {
			this.status = status;
			this.segment = segment;
			this.distanceMeters = distanceMeters;
			this.headingDifferenceDegrees = headingDifferenceDegrees;
			this.progressMeters = progressMeters;
			this.segmentLengthMeters = segmentLengthMeters;
			this.score = score;
			this.nearbyCandidateCount = nearbyCandidateCount;
			this.directionCandidateCount = directionCandidateCount;
		}

		private static MatchResult matched(ScoredCandidate candidate, int nearbyCount, int directionCount) {
			return new MatchResult(Status.MATCHED, candidate.binding, candidate.distanceMeters,
					candidate.headingDifferenceDegrees, candidate.progressMeters,
					candidate.binding.getKey().getLengthMeters(), candidate.score, nearbyCount, directionCount);
		}

		private static MatchResult ambiguous(ScoredCandidate candidate, int nearbyCount, int directionCount) {
			return new MatchResult(Status.AMBIGUOUS, null, candidate.distanceMeters,
					candidate.headingDifferenceDegrees, Double.NaN, Double.NaN,
					candidate.score, nearbyCount, directionCount);
		}

		private static MatchResult unmatched(Status status, int nearbyCount, int directionCount) {
			return new MatchResult(status, null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
					nearbyCount, directionCount);
		}

		public boolean isMatched() {
			return status == Status.MATCHED;
		}

		public Status getStatus() {
			return status;
		}

		public RoadCrewSegmentIdentity.SegmentBinding getSegment() {
			return segment;
		}

		public double getDistanceMeters() {
			return distanceMeters;
		}

		public double getHeadingDifferenceDegrees() {
			return headingDifferenceDegrees;
		}

		public double getProgressMeters() {
			return progressMeters;
		}

		public double getSegmentLengthMeters() {
			return segmentLengthMeters;
		}

		public double getScore() {
			return score;
		}

		public int getNearbyCandidateCount() {
			return nearbyCandidateCount;
		}

		public int getDirectionCandidateCount() {
			return directionCandidateCount;
		}
	}

	private static boolean isValidFix(GpsFix fix) {
		return fix != null
				&& Double.isFinite(fix.latitude) && fix.latitude >= -90 && fix.latitude <= 90
				&& Double.isFinite(fix.longitude) && fix.longitude >= -180 && fix.longitude <= 180
				&& Double.isFinite(fix.accuracyMeters) && fix.accuracyMeters > 0
				&& Double.isFinite(fix.speedMetersPerSecond) && fix.speedMetersPerSecond >= 0
				&& Double.isFinite(fix.bearingDegrees)
				&& fix.bearingDegrees >= 0 && fix.bearingDegrees < 360;
	}

	private static double latitude(RouteDataObject road, int index) {
		return MapUtils.get31LatitudeY(road.getPoint31YTile(index));
	}

	private static double longitude(RouteDataObject road, int index) {
		return MapUtils.get31LongitudeX(road.getPoint31XTile(index));
	}

	private static double bearing(double fromLatitude, double fromLongitude,
			double toLatitude, double toLongitude) {
		double fromLatRadians = Math.toRadians(fromLatitude);
		double toLatRadians = Math.toRadians(toLatitude);
		double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
		double y = Math.sin(longitudeDelta) * Math.cos(toLatRadians);
		double x = Math.cos(fromLatRadians) * Math.sin(toLatRadians)
				- Math.sin(fromLatRadians) * Math.cos(toLatRadians) * Math.cos(longitudeDelta);
		return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
	}

	private static final class CandidateGeometry {
		private final RoadCrewSegmentIdentity.SegmentBinding binding;
		private final RouteDataObject road;

		private CandidateGeometry(RoadCrewSegmentIdentity.SegmentBinding binding, RouteDataObject road) {
			this.binding = binding;
			this.road = road;
		}

		private NearestEdge nearestEdge(double latitude, double longitude) {
			int start = binding.getStartPointIndex();
			int end = binding.getEndPointIndex();
			int step = start < end ? 1 : -1;
			NearestEdge nearest = null;
			double completedMeters = 0;
			for (int index = start; index != end; index += step) {
				int next = index + step;
				double fromLatitude = latitude(road, index);
				double fromLongitude = longitude(road, index);
				double toLatitude = latitude(road, next);
				double toLongitude = longitude(road, next);
				if (fromLatitude == toLatitude && fromLongitude == toLongitude) {
					continue;
				}
				double edgeLength = MapUtils.getDistance(fromLatitude, fromLongitude,
						toLatitude, toLongitude);
				double distance = MapUtils.getOrthogonalDistance(latitude, longitude,
						fromLatitude, fromLongitude, toLatitude, toLongitude);
				if (nearest == null || distance < nearest.distanceMeters) {
					double projection = MapUtils.getProjectionCoeff(latitude, longitude,
							fromLatitude, fromLongitude, toLatitude, toLongitude);
					nearest = new NearestEdge(distance,
							bearing(fromLatitude, fromLongitude, toLatitude, toLongitude),
							completedMeters + edgeLength * projection);
				}
				completedMeters += edgeLength;
			}
			return nearest;
		}
	}

	private static final class NearestEdge {
		private final double distanceMeters;
		private final double bearingDegrees;
		private final double progressMeters;

		private NearestEdge(double distanceMeters, double bearingDegrees, double progressMeters) {
			this.distanceMeters = distanceMeters;
			this.bearingDegrees = bearingDegrees;
			this.progressMeters = progressMeters;
		}
	}

	private static final class ScoredCandidate {
		private final RoadCrewSegmentIdentity.SegmentBinding binding;
		private final double distanceMeters;
		private final double headingDifferenceDegrees;
		private final double progressMeters;
		private final double score;

		private ScoredCandidate(RoadCrewSegmentIdentity.SegmentBinding binding, double distanceMeters,
				double headingDifferenceDegrees, double progressMeters, double score) {
			this.binding = binding;
			this.distanceMeters = distanceMeters;
			this.headingDifferenceDegrees = headingDifferenceDegrees;
			this.progressMeters = progressMeters;
			this.score = score;
		}
	}
}
