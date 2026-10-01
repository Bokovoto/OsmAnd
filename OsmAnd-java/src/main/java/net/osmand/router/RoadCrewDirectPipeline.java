package net.osmand.router;

import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
		/** The map's own object, for the questions of ROADMAP 330. */
		final RouteDataObject road;

		WayInfo(long osmWayId, RoadCrewWayCanonical.CanonicalWay canonical, double[] rawMeasures,
				String region, RouteDataObject road) {
			this.road = road;
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
	private RoadCrewDirectPassageAccumulator.FixTrace trace;
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

	/** ROADMAP 330: the map questions, over the roads the matcher holds. */
	private final RoadCrewRoadTopology topology;
	/** The next observation's place in the course; never reset while the course lasts. */
	private int nextPassageIndex;
	/** A passage was dropped here, so whatever comes next has nothing to join. */
	private boolean chainBroken;

	public RoadCrewDirectPipeline(RoadCrewDirectPassageAccumulator.Config config,
			RoadCrewDirectPassageAccumulator.PassageSink sink) {
		this(config, sink, null);
	}

	/**
	 * @param roads loads the roads around a point the path needs and memory
	 *              lacks; without it only the roads in memory can prove anything
	 */
	public RoadCrewDirectPipeline(RoadCrewDirectPassageAccumulator.Config config,
			RoadCrewDirectPassageAccumulator.PassageSink sink, RoadCrewRoadTopology.RoadSource roads) {
		this.accumulator = new RoadCrewDirectPassageAccumulator(config, passage -> {
			if (sink != null) {
				sink.accept(passage);
			}
			emit(passage);
		});
		this.topology = new RoadCrewRoadTopology(roads);
		accumulator.setTopology(new MapProof());
	}

	/**
	 * Answers the accumulator's questions in its own terms - canonical measures,
	 * WayInfo attachments - by asking the map in the map file's terms.
	 */
	private final class MapProof implements RoadCrewDirectPassageAccumulator.Topology {
		@Override
		public boolean meetAt(Object fromWay, Object toWay, List<double[]> positions,
				double radiusMeters) {
			if (!(fromWay instanceof WayInfo) || !(toWay instanceof WayInfo)) {
				return false;
			}
			return RoadCrewRoadTopology.meetAt(((WayInfo) fromWay).road, ((WayInfo) toWay).road,
					positions, radiusMeters);
		}

		@Override
		public RoadCrewDirectPassageAccumulator.Junction junction(Object fromWay, Object toWay,
				List<double[]> positions, double radiusMeters) {
			if (!(fromWay instanceof WayInfo) || !(toWay instanceof WayInfo)) {
				return null;
			}
			WayInfo from = (WayInfo) fromWay;
			WayInfo to = (WayInfo) toWay;
			List<int[]> nodes = RoadCrewRoadTopology.sharedNodes(from.road, to.road, positions,
					radiusMeters);
			if (nodes.isEmpty()) {
				return null;
			}
			// Each node's place on each way, in the same canonical measures as the
			// fixes - the matcher's own conversion, not a second one.
			double[] onFrom = new double[nodes.size()];
			double[] onTo = new double[nodes.size()];
			for (int index = 0; index < nodes.size(); index++) {
				onFrom[index] = canonicalMeasureOf(from, nodes.get(index)[0]);
				onTo[index] = canonicalMeasureOf(to, nodes.get(index)[1]);
			}
			return new RoadCrewDirectPassageAccumulator.Junction(onFrom, onTo);
		}

		@Override
		public List<RoadCrewDirectPassageAccumulator.Leg> withoutBranch(Object fromWay,
				boolean fromForward, double fromMeasure, Object toWay, boolean toForward,
				double toMeasure) {
			if (!(fromWay instanceof WayInfo) || !(toWay instanceof WayInfo)) {
				return null;
			}
			WayInfo from = (WayInfo) fromWay;
			WayInfo to = (WayInfo) toWay;
			if (from.road == null || to.road == null || from.canonical.closed || to.canonical.closed) {
				return null;
			}
			List<RoadCrewRoadTopology.Step> steps;
			try {
				steps = topology.withoutBranch(from.road, rawForward(from, fromForward),
						rawMeasure(from, fromMeasure), to.road, rawForward(to, toForward),
						rawMeasure(to, toMeasure));
			} catch (IOException | RuntimeException e) {
				// Nothing checked, nothing proven - and never a reason to disturb the drive.
				count("topology_failed");
				return null;
			}
			if (steps == null) {
				return null;
			}
			List<RoadCrewDirectPassageAccumulator.Leg> legs = new ArrayList<>(steps.size());
			for (RoadCrewRoadTopology.Step step : steps) {
				WayInfo info;
				try {
					info = infoFor(step.road);
				} catch (RuntimeException e) {
					info = null;
				}
				if (info == null || info.canonical.closed) {
					return null;
				}
				legs.add(new RoadCrewDirectPassageAccumulator.Leg(info.osmWayId,
						info.canonical.reversed ? !step.forward : step.forward,
						info.canonical.lengthMeters, info));
			}
			return legs;
		}
	}

	/** Canonical to raw: an open way is only ever mirrored. */
	private static double canonicalMeasureOf(WayInfo info, int rawIndex) {
		if (info.rawMeasures == null || rawIndex < 0 || rawIndex >= info.rawMeasures.length) {
			return Double.NaN;
		}
		return RoadCrewWayCanonical.canonicalMeasure(info.rawMeasures[rawIndex], info.canonical);
	}

	private static boolean rawForward(WayInfo info, boolean canonicalForward) {
		return info.canonical.reversed ? !canonicalForward : canonicalForward;
	}

	private static double rawMeasure(WayInfo info, double canonicalMeasure) {
		return info.canonical.reversed ? info.canonical.lengthMeters - canonicalMeasure : canonicalMeasure;
	}

	/** Diagnostic build only; without one nothing is counted. */
	public void setDiagnostics(RoadCrewDiagnostics diagnostics) {
		this.diagnostics = diagnostics;
		accumulator.setDiagnostics(diagnostics);
	}

	/** Offline replay only; null everywhere else. See FixTrace. */
	public void setFixTrace(RoadCrewDirectPassageAccumulator.FixTrace trace) {
		this.trace = trace;
		accumulator.setFixTrace(trace);
	}

	private static String describe(
			RoadCrewSegmentMatcher.MatchResult.ScoredCandidateView candidate) {
		return "way" + candidate.osmWayId + "/road" + candidate.roadId + "[" + candidate.startPointIndex + ".."
				+ candidate.endPointIndex + "]d" + Math.round(candidate.distanceMeters * 100) / 100.0
				+ "h" + Math.round(candidate.headingDifferenceDegrees * 100) / 100.0
				+ "s" + Math.round(candidate.score * 100) / 100.0
				+ "p" + Math.round(candidate.progressMeters * 100) / 100.0;
	}

	private void note(long fixSequence, String what, String detail) {
		RoadCrewDirectPassageAccumulator.FixTrace sink = trace;
		if (sink != null) {
			sink.note(fixSequence, what, detail);
		}
	}

	/** A fix the strict matcher refused and the relaxed one accepted (ROADMAP 321). */
	public void countRescued(RoadCrewSegmentMatcher.Status strictStatus) {
		count("rescued_" + strictStatus.name().toLowerCase(java.util.Locale.ROOT));
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
			chainBroken = true;
			return;
		}
		if (info.osmWayId != passage.wayId) {
			// Now impossible: both come from the fix that started the passage.
			// Counted rather than thrown - nothing here may disturb the drive.
			count("observations_dropped_geometry_mismatch");
			chainBroken = true;
			return;
		}
		// A passage dropped just before this one leaves nothing to join to: the
		// server would otherwise join across it to the one before (ROADMAP 330).
		java.util.List<RoadCrewDirectObservation> observations =
				RoadCrewDirectObservation.fromPassage(passage, info.canonical, info.region, mapVersion,
						nextPassageIndex, passage.joinsPrevious && !chainBroken);
		if (observations.isEmpty()) {
			count("observations_dropped_no_span");
			chainBroken = true;
			return;
		}
		nextPassageIndex += observations.size();
		chainBroken = false;
		count("observations_created");
		if (passage.bridged) {
			count("observations_bridged");
		}
		sink.accept(observations);
	}

	/** Called when the loader swaps the roads held in memory. */
	public void replaceRoads() {
		cache.clear();
		topology.clear();
	}

	/**
	 * As above, with the roads now in memory and the area they were loaded
	 * for, so the map questions of ROADMAP 330 need no load of their own there.
	 */
	public void replaceRoads(Iterable<RouteDataObject> roads, double latitude, double longitude,
			double radiusMeters) {
		cache.clear();
		topology.replace(roads, latitude, longitude, radiusMeters);
	}

	/**
	 * A GPS position that is not matched at all - no speed or bearing, a truck
	 * standing. It only tells a disappearance of GPS from a stop (ROADMAP 330).
	 */
	public void acceptPosition(double latitude, double longitude, double accuracyMeters,
			long observedAtMillis) {
		if (isPosition(latitude, longitude, accuracyMeters)) {
			accumulator.position(observedAtMillis, latitude, longitude);
		}
	}

	/** The matcher's own bar: a fix it would refuse for accuracy is no GPS here either. */
	private static boolean isPosition(double latitude, double longitude, double accuracyMeters) {
		return Double.isFinite(latitude) && Double.isFinite(longitude)
				&& Double.isFinite(accuracyMeters) && accuracyMeters > 0
				&& accuracyMeters <= RoadCrewSegmentMatcher.MAX_ACCEPTED_ACCURACY_METERS;
	}

	/** A road this pipeline described recently, for the way-shape upload. */
	public RouteDataObject roadForOsmWay(long osmWayId) {
		for (WayInfo info : cache.values()) {
			if (info.osmWayId == osmWayId && info.road != null) {
				return info.road;
			}
		}
		return null;
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
			if (isPosition(fix.getLatitude(), fix.getLongitude(), fix.getAccuracyMeters())) {
				accumulator.position(observedAtMillis, fix.getLatitude(), fix.getLongitude());
			}
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
			// The same total, split by the reason the matcher gave, so a course's
			// diagnostics can tell a crawl from a sharp bend (ROADMAP 320).
			count("no_match_" + (match == null ? "no_result"
					: match.getStatus().name().toLowerCase(java.util.Locale.ROOT)));
			// The matcher decides between seven distinct reasons and this counter
			// collapses every one of them. Recorded for the offline replay, and
			// only there: the status is read from a decision already taken, and
			// nothing here acts on it.
			String detail = match == null ? "status=NO_RESULT"
					: "status=" + match.getStatus()
						+ " nearby=" + match.getNearbyCandidateCount()
						+ " eligible=" + match.getDirectionCandidateCount()
						+ " dist=" + Math.round(match.getDistanceMeters() * 100) / 100.0
						+ " heading=" + Math.round(match.getHeadingDifferenceDegrees() * 100) / 100.0;
			// For an ambiguous refusal the two competitors are what matters: if
			// both resolve to the same road and direction the refusal was
			// needlessly conservative, and if they do not it was right. Neither
			// can be told from the counters.
			RoadCrewSegmentMatcher.MatchResult.ScoredCandidateView first =
					match == null ? null : match.getBestCandidate();
			RoadCrewSegmentMatcher.MatchResult.ScoredCandidateView second =
					match == null ? null : match.getRunnerUp();
			if (first != null && second != null) {
				detail += " best=" + describe(first) + " second=" + describe(second)
						+ " scoreDelta=" + Math.round((second.score - first.score) * 100) / 100.0
						+ " groundApart=" + Math.round(net.osmand.util.MapUtils.getDistance(
							first.projectedLatitude, first.projectedLongitude,
							second.projectedLatitude, second.projectedLongitude) * 100) / 100.0
						+ " sameRoad=" + (first.roadId == second.roadId)
						+ " sameWay=" + (first.osmWayId == second.osmWayId)
						+ " sameSense=" + (Integer.signum(first.endPointIndex - first.startPointIndex)
							== Integer.signum(second.endPointIndex - second.startPointIndex));
			}
			note(fixSequence, "MATCH_REFUSED", detail);
			return null;
		}
		note(fixSequence, "MATCH_ACCEPTED", "status=" + match.getStatus()
				+ " nearby=" + match.getNearbyCandidateCount()
				+ " eligible=" + match.getDirectionCandidateCount());
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
				region, road);
		cache.put(road, info);
		return info;
	}
}
