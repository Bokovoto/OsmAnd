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
	private RoadCrewDiagnostics diagnostics;
	private String mapVersion = "";
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

	/** Diagnostic build only; without one nothing is counted. */
	public void setDiagnostics(RoadCrewDiagnostics diagnostics) {
		this.diagnostics = diagnostics;
		accumulator.setDiagnostics(diagnostics);
	}

	private void count(String name) {
		if (diagnostics != null) {
			diagnostics.count(name);
		}
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
		if (sink == null || passage == null) {
			return;
		}
		// The geometry travels on the passage itself, taken from the fix that
		// started it. It used to be read from a field holding "the way of the
		// last accepted fix" - and a passage closes only once the NEXT way has
		// won two consecutive fixes, so that field already pointed at the next
		// road. The guard comparing the two then discarded exactly the valid
		// passages: fifteen of nineteen on the drive of 5 September, which is
		// what left coverage at 5.9%.
		//
		// Resolving it by way id at this moment would work and would still
		// depend on the order of events. Carrying it does not.
		WayInfo info = passage.attachment instanceof WayInfo
				? (WayInfo) passage.attachment : null;
		if (info == null) {
			count("observations_dropped_no_geometry");
			return;
		}
		if (info.osmWayId != passage.wayId) {
			// Now impossible: both come from the fix that started the passage.
			// Counted rather than thrown - nothing here may disturb the drive.
			count("observations_dropped_geometry_mismatch");
			return;
		}
		java.util.List<RoadCrewDirectObservation> observations =
				RoadCrewDirectObservation.fromPassage(passage, info.canonical, info.region, mapVersion);
		if (observations.isEmpty()) {
			count("observations_dropped_no_span");
			return;
		}
		count("observations_created");
		sink.accept(observations);
	}

	/** Called when the loader swaps the roads held in memory. */
	public void replaceRoads() {
		cache.clear();
	}

	public void reset() {
		count("pipeline_reset");
		accumulator.flush();
		cache.clear();
		hasPreviousFix = false;
		pendingMovementMeters = 0;
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
		count("fixes_seen");
		RoadCrewDirectPassageAccumulator.Fix directFix =
				toDirectFix(match, road, observedAtMillis, fixSequence);
		if (directFix == null) {
			accumulator.acceptNoMatch(observedAtMillis);
			return;
		}
		if (diagnostics != null) {
			diagnostics.matched(fixSequence, directFix.wayId, directFix.forward);
		}
		accumulator.accept(directFix);
		pendingMovementMeters = 0;
	}

	private RoadCrewDirectPassageAccumulator.Fix toDirectFix(
			RoadCrewSegmentMatcher.MatchResult match, RouteDataObject road, long observedAtMillis,
			long fixSequence) {
		if (match == null || !match.isMatched() || match.getSegment() == null) {
			count("no_match");
			return null;
		}
		if (road == null) {
			count("missing_road");
			return null;
		}
		WayInfo info;
		try {
			info = infoFor(road);
			lastInfo = info;
		} catch (RuntimeException ignored) {
			count("canonicalisation_failed");
			// A way this code cannot canonicalise is not a reason to disturb the
			// legacy path; it simply produces no directed observation.
			return null;
		}
		if (info == null) {
			count("missing_way_id");
			return null;
		}
		RoadCrewSegmentIdentity.SegmentBinding binding = match.getSegment();
		int startIndex = binding.getStartPointIndex();
		int endIndex = binding.getEndPointIndex();
		if (startIndex < 0 || endIndex < 0
				|| startIndex >= info.rawMeasures.length || endIndex >= info.rawMeasures.length) {
			count("invalid_indices");
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
				Math.max(0, match.getHeadingDifferenceDegrees()), info);
	}

	/**
	 * The direction the matcher actually resolved, expressed the same way the
	 * directed scheme expresses it.
	 *
	 * Written for the comparison telemetry of the legacy branch. That branch
	 * carries no direction of its own, and inferring one from the ends of a
	 * piece is wrong wherever a road doubles back - which would put an unknown
	 * error into the denominator of the whole experiment. The matcher already
	 * knew; this simply asks it, using exactly the code the directed branch
	 * uses, so the two can never disagree by construction.
	 *
	 * Static and stateless: it changes nothing about the passage it describes.
	 *
	 * @return "F", "R", or null when the way cannot be canonicalised
	 */
	public static String canonicalDirection(RouteDataObject road, int startPointIndex,
			int endPointIndex) {
		if (road == null || road.pointsX == null || road.pointsY == null
				|| road.getPointsLength() < 2 || startPointIndex == endPointIndex
				|| startPointIndex < 0 || endPointIndex < 0
				|| startPointIndex >= road.getPointsLength()
				|| endPointIndex >= road.getPointsLength()) {
			return null;
		}
		try {
			int[] xs = new int[road.getPointsLength()];
			int[] ys = new int[road.getPointsLength()];
			for (int index = 0; index < xs.length; index++) {
				xs[index] = road.getPoint31XTile(index);
				ys[index] = road.getPoint31YTile(index);
			}
			boolean rawForward = endPointIndex > startPointIndex;
			boolean forward = RoadCrewWayCanonical.canonicalise(xs, ys).reversed
					? !rawForward : rawForward;
			return forward ? "F" : "R";
		} catch (RuntimeException ignored) {
			// Telemetry must never disturb the drive; an unusable way simply
			// reports no direction and the analysis counts it as such.
			return null;
		}
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
