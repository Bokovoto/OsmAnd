package net.osmand.router;

import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Feeds the directed accumulator from the map match the existing pipeline
 * already made, as ROADMAP section 165 checkpoint C describes.
 *
 * The two identity schemes must never run their own matcher. Two matchers would
 * resolve the same GPS fix differently and the comparison would end up measuring
 * their disagreement rather than the segmentation's:
 *
 *   GPS fix -> the one existing map match -> MatchResult
 *                                             |-- legacy segmentation  -> rcs1
 *                                             `-- this                 -> rcs2
 *
 * Nothing here changes the legacy path, which is the control in the experiment.
 */
public final class RoadCrewDirectPipeline {

	/** What the accumulator needs to know about one loaded way. */
	private static final class WayInfo {
		final long osmWayId;
		final RoadCrewWayCanonical.CanonicalWay canonical;
		/** Cumulative distance along the map file's own point order. */
		final double[] rawMeasures;
		final String region;

		WayInfo(long osmWayId, RoadCrewWayCanonical.CanonicalWay canonical, double[] rawMeasures,
				String region) {
			this.osmWayId = osmWayId;
			this.canonical = canonical;
			this.rawMeasures = rawMeasures;
			this.region = region;
		}
	}

	/** Receives finished passages already shaped for the wire. */
	public interface ObservationSink {
		void accept(java.util.List<RoadCrewDirectObservation> observations);
	}

	private final RoadCrewDirectPassageAccumulator accumulator;
	private ObservationSink observationSink;
	private String mapVersion = "";
	/**
	 * The way the last accepted fix belonged to. A passage is finished from
	 * inside accept() of the fix that starts the next one, so this field is
	 * updated only after the accumulator has returned - at the moment the
	 * accumulator calls back, it still holds the way the finished passage was
	 * measured against, which is the geometry the observation needs.
	 */
	private WayInfo passageWayInfo;
	// Keyed by the live object: the loader already bounds how long one survives,
	// and the same way can carry different geometry between map editions, so a
	// cache keyed by way id alone would eventually answer for the wrong road.
	private final Map<RouteDataObject, WayInfo> cache = new IdentityHashMap<>();

	private boolean hasPreviousFix;
	private double previousLatitude;
	private double previousLongitude;
	/**
	 * Ground the vehicle covered since the accumulator last heard anything. It
	 * accumulates across unmatched fixes too: a passage that survives a short
	 * matching gap must be judged against everything the truck moved during the
	 * gap, not only the last step, or an ordinary drive would be refused.
	 */
	private double pendingMovementMeters;
	/** Set by toDirectFix, consumed once the accumulator has accepted the fix. */
	private WayInfo lastInfo;

	public RoadCrewDirectPipeline(RoadCrewDirectPassageAccumulator.Config config,
			RoadCrewDirectPassageAccumulator.PassageSink sink) {
		this.accumulator = new RoadCrewDirectPassageAccumulator(config, passage -> {
			if (sink != null) {
				sink.accept(passage);
			}
			emit(passage);
		});
	}

	/** Turns on the wire-shaped output; without one only passages are produced. */
	public void setObservationSink(ObservationSink sink) {
		this.observationSink = sink;
	}

	/** Which map edition the geometry came from, for the descriptor registry. */
	public void setMapVersion(String mapVersion) {
		this.mapVersion = mapVersion == null ? "" : mapVersion;
	}

	private void emit(RoadCrewDirectPassageAccumulator.Passage passage) {
		ObservationSink sink = observationSink;
		WayInfo info = passageWayInfo;
		// Refusing rather than guessing: a passage whose geometry is no longer
		// the one it was measured against would produce endpoints for a road
		// nobody drove.
		if (sink == null || info == null || passage == null || info.osmWayId != passage.wayId) {
			return;
		}
		java.util.List<RoadCrewDirectObservation> observations =
				RoadCrewDirectObservation.fromPassage(passage, info.canonical, info.region, mapVersion);
		if (!observations.isEmpty()) {
			sink.accept(observations);
		}
	}

	/** Called when the loader swaps the roads held in memory. */
	public void replaceRoads() {
		cache.clear();
	}

	public void reset() {
		accumulator.flush();
		cache.clear();
		hasPreviousFix = false;
		pendingMovementMeters = 0;
		passageWayInfo = null;
		lastInfo = null;
	}

	public void flush() {
		accumulator.flush();
	}

	/**
	 * @param road the object the match resolved to, or null when there was none
	 */
	public void accept(RoadCrewSegmentMatcher.GpsFix fix, RoadCrewSegmentMatcher.MatchResult match,
			RouteDataObject road, long observedAtMillis) {
		accept(fix, match, road, observedAtMillis, 0);
	}

	public void accept(RoadCrewSegmentMatcher.GpsFix fix, RoadCrewSegmentMatcher.MatchResult match,
			RouteDataObject road, long observedAtMillis, long fixSequence) {
		if (fix != null) {
			if (hasPreviousFix) {
				pendingMovementMeters += MapUtils.getDistance(previousLatitude, previousLongitude,
						fix.getLatitude(), fix.getLongitude());
			}
			previousLatitude = fix.getLatitude();
			previousLongitude = fix.getLongitude();
			hasPreviousFix = true;
		}
		lastInfo = null;
		RoadCrewDirectPassageAccumulator.Fix directFix =
				toDirectFix(match, road, observedAtMillis, fixSequence);
		if (directFix == null) {
			accumulator.acceptNoMatch(observedAtMillis);
			return;
		}
		accumulator.accept(directFix);
		passageWayInfo = lastInfo;
		pendingMovementMeters = 0;
	}

	private RoadCrewDirectPassageAccumulator.Fix toDirectFix(
			RoadCrewSegmentMatcher.MatchResult match, RouteDataObject road, long observedAtMillis,
			long fixSequence) {
		if (match == null || !match.isMatched() || road == null || match.getSegment() == null) {
			return null;
		}
		WayInfo info;
		try {
			info = infoFor(road);
			lastInfo = info;
		} catch (RuntimeException ignored) {
			// A way this code cannot canonicalise is not a reason to disturb the
			// legacy path; it simply produces no directed observation.
			return null;
		}
		if (info == null) {
			return null;
		}
		RoadCrewSegmentIdentity.SegmentBinding binding = match.getSegment();
		int startIndex = binding.getStartPointIndex();
		int endIndex = binding.getEndPointIndex();
		if (startIndex < 0 || endIndex < 0
				|| startIndex >= info.rawMeasures.length || endIndex >= info.rawMeasures.length) {
			return null;
		}
		// The direction comes from the matcher's own decision about which way
		// along the road this edge is being driven, never from the GPS heading:
		// the matcher has already weighed that and a single index says nothing.
		boolean rawForward = endIndex > startIndex;
		boolean canonicalForward = info.canonical.reversed ? !rawForward : rawForward;

		double progress = Math.max(0, match.getProgressMeters());
		double rawMeasure = rawForward
				? info.rawMeasures[startIndex] + progress
				: info.rawMeasures[startIndex] - progress;
		double canonicalMeasure = RoadCrewWayCanonical.canonicalMeasure(rawMeasure, info.canonical);

		return new RoadCrewDirectPassageAccumulator.Fix(info.osmWayId, canonicalForward,
				canonicalMeasure, info.canonical.closed, info.canonical.lengthMeters,
				observedAtMillis, pendingMovementMeters, fixSequence,
				Math.max(0, match.getDistanceMeters()),
				Math.max(0, match.getHeadingDifferenceDegrees()));
	}

	private WayInfo infoFor(RouteDataObject road) {
		WayInfo cached = cache.get(road);
		if (cached != null) {
			return cached;
		}
		if (road.pointsX == null || road.pointsY == null || road.getPointsLength() < 2) {
			return null;
		}
		long osmWayId = net.osmand.binary.ObfConstants.getOsmObjectId(road);
		if (osmWayId <= 0) {
			return null;
		}
		int[] xs = new int[road.getPointsLength()];
		int[] ys = new int[road.getPointsLength()];
		for (int index = 0; index < xs.length; index++) {
			xs[index] = road.getPoint31XTile(index);
			ys[index] = road.getPoint31YTile(index);
		}
		double[] rawMeasures = new double[xs.length];
		for (int index = 1; index < xs.length; index++) {
			rawMeasures[index] = rawMeasures[index - 1]
					+ RoadCrewWayCanonical.distanceMeters(
							xs[index - 1], ys[index - 1], xs[index], ys[index]);
		}
		String region = road.region == null || road.region.getName() == null
				? "" : road.region.getName().trim();
		WayInfo info = new WayInfo(osmWayId, RoadCrewWayCanonical.canonicalise(xs, ys), rawMeasures,
				region);
		cache.put(road, info);
		return info;
	}
}
