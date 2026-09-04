package net.osmand.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One directed observation exactly as the rcs2 wire contract takes it, built
 * from a finished passage and the canonical geometry it was measured against.
 *
 * The phone must produce the whole key itself, because the server has no map:
 * it can check that a key is well formed and that its fingerprint matches a
 * geometry it has already seen, but it cannot invent the endpoints. See ROADMAP
 * section 168.
 */
public final class RoadCrewDirectObservation {

	public static final int SEGMENT_KEY_VERSION = 2;
	/** Same fifteen-minute bucket the legacy path uses: never a precise moment. */
	public static final long OBSERVATION_BUCKET_MILLIS = 15 * 60 * 1_000L;
	/** Below this the server refuses the key, and the stretch says nothing anyway. */
	public static final double MINIMUM_SPAN_METERS = 1;

	public final long osmWayId;
	public final boolean forward;
	public final double fromMeasureMeters;
	public final double toMeasureMeters;
	public final double fromLatitude;
	public final double fromLongitude;
	public final double toLatitude;
	public final double toLongitude;
	public final int startPointIndex;
	public final int endPointIndex;
	public final String geometryFingerprint;
	public final int geometryFingerprintAlgorithm;
	public final String region;
	public final String mapVersion;
	public final long observedAtBucketMillis;
	public final int fixCount;
	public final long durationMillis;
	public final double forwardMovementMeters;
	public final long firstFixSequence;
	public final long lastFixSequence;
	public final double maximumDistanceMeters;
	public final double maximumHeadingDifferenceDegrees;

	private RoadCrewDirectObservation(long osmWayId, boolean forward,
			double fromMeasureMeters, double toMeasureMeters,
			double fromLatitude, double fromLongitude, double toLatitude, double toLongitude,
			int startPointIndex, int endPointIndex,
			String geometryFingerprint, int geometryFingerprintAlgorithm,
			String region, String mapVersion, long observedAtBucketMillis, int fixCount,
			long durationMillis, double forwardMovementMeters,
			long firstFixSequence, long lastFixSequence,
			double maximumDistanceMeters, double maximumHeadingDifferenceDegrees) {
		this.osmWayId = osmWayId;
		this.forward = forward;
		this.fromMeasureMeters = fromMeasureMeters;
		this.toMeasureMeters = toMeasureMeters;
		this.fromLatitude = fromLatitude;
		this.fromLongitude = fromLongitude;
		this.toLatitude = toLatitude;
		this.toLongitude = toLongitude;
		this.startPointIndex = startPointIndex;
		this.endPointIndex = endPointIndex;
		this.geometryFingerprint = geometryFingerprint;
		this.geometryFingerprintAlgorithm = geometryFingerprintAlgorithm;
		this.region = region;
		this.mapVersion = mapVersion;
		this.observedAtBucketMillis = observedAtBucketMillis;
		this.fixCount = fixCount;
		this.durationMillis = durationMillis;
		this.forwardMovementMeters = forwardMovementMeters;
		this.firstFixSequence = firstFixSequence;
		this.lastFixSequence = lastFixSequence;
		this.maximumDistanceMeters = maximumDistanceMeters;
		this.maximumHeadingDifferenceDegrees = maximumHeadingDifferenceDegrees;
	}

	/** rcs2:way:F or rcs2:way:R - the server recomputes it and refuses a mismatch. */
	public String getCanonicalId() {
		return "rcs" + SEGMENT_KEY_VERSION + ":" + osmWayId + ":" + getDirection();
	}

	public String getDirection() {
		return forward ? "F" : "R";
	}

	public double getLengthMeters() {
		return toMeasureMeters - fromMeasureMeters;
	}

	/**
	 * Turns a finished passage into the observations that go on the wire - one
	 * per span, since a traversal that wraps past the end of a ring covers two
	 * separate stretches of it and a single ascending interval cannot say so.
	 *
	 * Returns an empty list rather than throwing: a passage that cannot be
	 * expressed is simply not reported, and must never disturb the drive.
	 */
	public static List<RoadCrewDirectObservation> fromPassage(
			RoadCrewDirectPassageAccumulator.Passage passage,
			RoadCrewWayCanonical.CanonicalWay way, String region, String mapVersion) {
		if (passage == null || way == null || way.getPointCount() < 2
				|| passage.spans == null || passage.spans.isEmpty()) {
			return Collections.emptyList();
		}
		String fingerprint = RoadCrewWayCanonical.canonicalFingerprint(way);
		long bucket = passage.startTimeMillis - Math.floorMod(
				passage.startTimeMillis, OBSERVATION_BUCKET_MILLIS);
		long duration = Math.max(0, passage.endTimeMillis - passage.startTimeMillis);

		double totalSpanMeters = 0;
		for (RoadCrewDirectPassageAccumulator.Span span : passage.spans) {
			totalSpanMeters += Math.max(0, span.toMeasureMeters - span.fromMeasureMeters);
		}
		if (totalSpanMeters <= 0) {
			return Collections.emptyList();
		}

		List<RoadCrewDirectObservation> observations = new ArrayList<>(passage.spans.size());
		for (RoadCrewDirectPassageAccumulator.Span span : passage.spans) {
			double from = Math.max(0, Math.min(way.lengthMeters, span.fromMeasureMeters));
			double to = Math.max(0, Math.min(way.lengthMeters, span.toMeasureMeters));
			if (to - from < MINIMUM_SPAN_METERS) {
				continue;
			}
			// A wrapping ring produces more than one stretch out of one drive.
			// Effort is shared between them by length: for the ordinary
			// single-span passage the share is the whole thing, exactly.
			double share = (to - from) / totalSpanMeters;
			boolean single = passage.spans.size() == 1;
			int spanFixCount = single
					? passage.fixCount : Math.max(1, (int) Math.round(passage.fixCount * share));
			long spanDuration = single ? duration : Math.round(duration * share);
			double spanMovement = single
					? passage.progressMeters : passage.progressMeters * share;

			int startIndex = pointIndexAtOrBefore(way, from);
			int endIndex = pointIndexAtOrAfter(way, to);
			if (startIndex == endIndex) {
				// The stretch sits inside one leg of the polyline. The indexes
				// exist to say which leg, so the leg ends are the answer.
				endIndex = startIndex + 1 < way.measures.length ? startIndex + 1 : startIndex - 1;
			}
			if (startIndex < 0 || endIndex < 0) {
				continue;
			}
			observations.add(new RoadCrewDirectObservation(passage.wayId, passage.forward,
					from, to,
					latitudeAt(way, from), longitudeAt(way, from),
					latitudeAt(way, to), longitudeAt(way, to),
					wrapIndex(way, startIndex), wrapIndex(way, endIndex),
					fingerprint, RoadCrewWayCanonical.FINGERPRINT_ALGORITHM,
					region == null ? "" : region.trim(), mapVersion == null ? "" : mapVersion.trim(),
					bucket, spanFixCount, spanDuration, spanMovement,
					passage.firstFixSequence, passage.lastFixSequence,
					passage.maximumDistanceMeters, passage.maximumHeadingDifferenceDegrees));
		}
		return observations;
	}

	/**
	 * A ring carries a closing leg in its measures, so the last entry is the
	 * first point come round again. The wire wants the point index itself.
	 */
	private static int wrapIndex(RoadCrewWayCanonical.CanonicalWay way, int index) {
		int count = way.getPointCount();
		return index >= count ? index % count : index;
	}

	private static int pointIndexAtOrBefore(RoadCrewWayCanonical.CanonicalWay way, double measure) {
		int index = 0;
		for (int candidate = 0; candidate < way.measures.length; candidate++) {
			if (way.measures[candidate] <= measure) {
				index = candidate;
			} else {
				break;
			}
		}
		return index;
	}

	private static int pointIndexAtOrAfter(RoadCrewWayCanonical.CanonicalWay way, double measure) {
		for (int candidate = 0; candidate < way.measures.length; candidate++) {
			if (way.measures[candidate] >= measure) {
				return candidate;
			}
		}
		return way.measures.length - 1;
	}

	public static double latitudeAt(RoadCrewWayCanonical.CanonicalWay way, double measure) {
		return RoadCrewWayCanonical.latitudeFrom31(
				(int) Math.round(interpolate(way, measure, false)));
	}

	public static double longitudeAt(RoadCrewWayCanonical.CanonicalWay way, double measure) {
		return RoadCrewWayCanonical.longitudeFrom31(
				(int) Math.round(interpolate(way, measure, true)));
	}

	/**
	 * Walks the canonical polyline to the given distance. The interpolation is
	 * done in the projected coordinates the measures were computed from, so a
	 * point on a straight leg lands on that leg.
	 */
	private static double interpolate(RoadCrewWayCanonical.CanonicalWay way, double measure,
			boolean wantX) {
		double clamped = Math.max(0, Math.min(way.lengthMeters, measure));
		int leg = 0;
		for (int candidate = 0; candidate + 1 < way.measures.length; candidate++) {
			if (way.measures[candidate] <= clamped) {
				leg = candidate;
			} else {
				break;
			}
		}
		int count = way.getPointCount();
		int fromPoint = leg % count;
		int toPoint = (leg + 1) % count;
		double legStart = way.measures[leg];
		double legEnd = way.measures[leg + 1];
		double fraction = legEnd - legStart <= 0 ? 0 : (clamped - legStart) / (legEnd - legStart);
		double fromValue = wantX ? way.pointsX[fromPoint] : way.pointsY[fromPoint];
		double toValue = wantX ? way.pointsX[toPoint] : way.pointsY[toPoint];
		return fromValue + (toValue - fromValue) * fraction;
	}
}
