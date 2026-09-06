package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a recorded drive through the very pipeline the phone runs (ROADMAP
 * section 184).
 *
 * One recording, one map, two builds, and the only variable is the code. For
 * everything below the point where locations enter, this is not weaker evidence
 * than driving again - it is stronger, because it is deterministic and can be
 * run a hundred times over the same input. The fault of 5 September lived below
 * that point and would have reproduced identically from a file.
 *
 * What it does NOT prove, and must never be claimed to: that Android delivers
 * those locations the same way, that the navigation lifecycle behaves, that the
 * uploads work, or that two independent drivers agree about a road. Those need
 * real driving, and simulated locations need never enter the question at all -
 * they never take part in evidence.
 */
public final class RoadCrewReplay {

	/** One recorded location, exactly as the phone received it. */
	public static final class RecordedFix {
		public final long fixSequence;
		public final long wallClockMillis;
		public final long elapsedRealtimeMillis;
		public final double latitude;
		public final double longitude;
		public final double accuracyMeters;
		public final double speedMetersPerSecond;
		public final double bearingDegrees;

		RecordedFix(long fixSequence, long wallClockMillis, long elapsedRealtimeMillis,
				double latitude, double longitude, double accuracyMeters,
				double speedMetersPerSecond, double bearingDegrees) {
			this.fixSequence = fixSequence;
			this.wallClockMillis = wallClockMillis;
			this.elapsedRealtimeMillis = elapsedRealtimeMillis;
			this.latitude = latitude;
			this.longitude = longitude;
			this.accuracyMeters = accuracyMeters;
			this.speedMetersPerSecond = speedMetersPerSecond;
			this.bearingDegrees = bearingDegrees;
		}
	}

	public static final class Result {
		public final RoadCrewDiagnostics diagnostics = new RoadCrewDiagnostics();
		public final List<RoadCrewDirectObservation> directed = new ArrayList<>();
		/**
		 * Every passage the accumulator emitted, in order.
		 *
		 * Kept because the fault of section 183 lived AFTER a passage was
		 * complete: the passage was right, and it was thrown away on the way to
		 * becoming an observation. So an experiment that shows only a difference
		 * in observations has not isolated it. If the segmentation changed too,
		 * something other than that fix is also at work, and the comparison
		 * proves nothing about either.
		 */
		public final List<RoadCrewDirectPassageAccumulator.Passage> passages = new ArrayList<>();
		/** Every decision the accumulator recorded, when the trace was on. */
		public final List<String> fixTrace = new ArrayList<>();
		public int legacyObservations;
		public int fixesReplayed;

		/**
		 * The passages as text, in a form two builds can be compared line by
		 * line. Deliberately excludes anything derived after the passage closed:
		 * this is what the accumulator decided, and nothing downstream of it.
		 */
		public List<String> passageFingerprint() {
			List<String> lines = new ArrayList<>();
			for (RoadCrewDirectPassageAccumulator.Passage passage : passages) {
				StringBuilder line = new StringBuilder();
				line.append(passage.wayId).append(' ').append(passage.forward ? 'F' : 'R')
						.append(" fixes=").append(passage.firstFixSequence)
						.append('-').append(passage.lastFixSequence)
						.append(" count=").append(passage.fixCount)
						.append(" duration=").append(passage.endTimeMillis - passage.startTimeMillis)
						.append(" progress=").append(Math.round(passage.progressMeters * 100))
						.append(" maxDist=").append(Math.round(passage.maximumDistanceMeters * 100))
						.append(" maxHead=").append(Math.round(passage.maximumHeadingDifferenceDegrees * 100))
						.append(" spans=");
				for (RoadCrewDirectPassageAccumulator.Span span : passage.spans) {
					line.append('[').append(Math.round(span.fromMeasureMeters * 100)).append(',')
							.append(Math.round(span.toMeasureMeters * 100)).append(']');
				}
				lines.add(line.toString());
			}
			return lines;
		}

		/** What the report calls pipeline recall, computed the same way. */
		public double directedRecall() {
			int matched = diagnostics.counter("matched_fixes");
			if (matched == 0) {
				return 0;
			}
			long covered = 0;
			long previousEnd = -1;
			List<long[]> ranges = new ArrayList<>();
			for (RoadCrewDirectObservation observation : directed) {
				ranges.add(new long[]{observation.firstFixSequence, observation.lastFixSequence});
			}
			ranges.sort((a, b) -> Long.compare(a[0], b[0]));
			for (long[] range : ranges) {
				long from = Math.max(range[0], previousEnd + 1);
				if (range[1] >= from) {
					covered += range[1] - from + 1;
					previousEnd = range[1];
				}
			}
			return (double) covered / matched;
		}
	}

	private RoadCrewReplay() {
	}

	/** Reads the recording written by {@link RoadCrewLocationRecorder}. */
	public static List<RecordedFix> read(File recording) throws IOException {
		List<RecordedFix> fixes = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(recording), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.contains("\"t\":\"fix\"")) {
					continue;
				}
				fixes.add(new RecordedFix(
						(long) number(line, "seq"), (long) number(line, "wall"),
						(long) number(line, "elapsed"), number(line, "lat"), number(line, "lon"),
						number(line, "acc"), number(line, "spd"), number(line, "brg")));
			}
		}
		return fixes;
	}

	/**
	 * Runs the recording through the loader, the matcher and both branches.
	 *
	 * The reload rule is the phone's own: nine hundred metres of loaded road,
	 * refreshed after three hundred and fifty metres of travel or a minute,
	 * because how often the roads change underneath is part of what is being
	 * tested.
	 */
	public static Result run(List<RecordedFix> fixes, BinaryMapIndexReader[] readers,
			double loadRadiusMeters, double reloadDistanceMeters, long reloadIntervalMillis,
			int maxRouteObjects) throws IOException {
		return run(fixes, readers, loadRadiusMeters, reloadDistanceMeters, reloadIntervalMillis,
				maxRouteObjects, false);
	}

	/**
	 * With `traceDecisions` off this must behave exactly as it did before the
	 * trace existed. That is not an assumption: `diagnostics off against on
	 * leaves every passage identical` runs the same recording both ways and
	 * requires the same fingerprint.
	 */
	public static Result run(List<RecordedFix> fixes, BinaryMapIndexReader[] readers,
			double loadRadiusMeters, double reloadDistanceMeters, long reloadIntervalMillis,
			int maxRouteObjects, boolean traceDecisions) throws IOException {
		Result result = new Result();
		RoadCrewObservationPipeline pipeline = new RoadCrewObservationPipeline(
				(evidence, at, road, binding, firstFix, lastFix) -> result.legacyObservations++);
		pipeline.startSession("00000000-0000-4000-8000-000000000000");
		pipeline.enableDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, result.passages::add);
		pipeline.setDirectObservationSink(result.directed::addAll);
		pipeline.setDirectDiagnostics(result.diagnostics);
		pipeline.setDirectMapVersion("replay");
		if (traceDecisions) {
			pipeline.setDirectFixTrace((fixSequence, what, detail) ->
					result.fixTrace.add(fixSequence + " " + what
							+ (detail == null || detail.isEmpty() ? "" : " " + detail)));
		}

		double loadedLatitude = Double.NaN;
		double loadedLongitude = 0;
		long loadedAtElapsed = 0;
		for (RecordedFix fix : fixes) {
			// Both comparisons are >=, because that is what the phone does:
			//
			//   sample.elapsed - loadedAtElapsed >= RELOAD_INTERVAL_MILLIS
			//   || getDistance(...) >= RELOAD_DISTANCE_METERS
			//
			// With > the replay declines to reload at exactly 350 m or exactly
			// 60000 ms where the phone reloads, so the two can hold different
			// road sets for a fix - which is a difference in the input to the
			// matcher, not in the matcher. Written as > here originally; that
			// was mine, and it is the kind of one-character difference that
			// makes a replay describe a drive nobody took.
			boolean needsReload = Double.isNaN(loadedLatitude)
					|| net.osmand.util.MapUtils.getDistance(loadedLatitude, loadedLongitude,
							fix.latitude, fix.longitude) >= reloadDistanceMeters
					|| fix.elapsedRealtimeMillis - loadedAtElapsed >= reloadIntervalMillis;
			if (needsReload) {
				RoadCrewObfSegmentLoader.LoadResult loaded = RoadCrewObfSegmentLoader.load(
						readers, fix.latitude, fix.longitude, loadRadiusMeters, maxRouteObjects, null);
				if (loaded.isCancelled() || loaded.isTruncated()) {
					result.diagnostics.count(loaded.isTruncated() ? "load_truncated" : "load_cancelled");
					result.diagnostics.event(fix.fixSequence, "LOAD_TRUNCATED",
							"objects=" + loaded.getRouteObjects().size());
					pipeline.reset();
					loadedLatitude = Double.NaN;
					continue;
				}
				result.diagnostics.count("roads_loaded");
				pipeline.replaceRoads(loaded.getRouteObjects());
				loadedLatitude = fix.latitude;
				loadedLongitude = fix.longitude;
				loadedAtElapsed = fix.elapsedRealtimeMillis;
			}
			pipeline.accept(new RoadCrewSegmentMatcher.GpsFix(fix.latitude, fix.longitude,
							fix.accuracyMeters, fix.speedMetersPerSecond, fix.bearingDegrees),
					fix.elapsedRealtimeMillis, fix.wallClockMillis);
			result.fixesReplayed++;
		}
		pipeline.reset();
		return result;
	}

	/** Reads one number out of a line of the recording. */
	private static double number(String line, String field) {
		String needle = "\"" + field + "\":";
		int start = line.indexOf(needle);
		if (start < 0) {
			return 0;
		}
		start += needle.length();
		int end = start;
		while (end < line.length() && "-0123456789.eE".indexOf(line.charAt(end)) >= 0) {
			end++;
		}
		try {
			return Double.parseDouble(line.substring(start, end));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
