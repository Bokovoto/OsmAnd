package net.osmand.router;

import net.osmand.binary.RouteDataObject;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory matcher/detector pipeline that passes aggregate passage evidence
 * to a local review sink or the legacy durable outbox.
 */
public final class RoadCrewObservationPipeline {

	private final RoadCrewObservationOutbox outbox;
	private final PassageSink sink;
	private final Map<Long, RouteDataObject> roadsById = new LinkedHashMap<>();
	private final RoadCrewPassageDetector detector = new RoadCrewPassageDetector();
	private RoadCrewSegmentMatcher.PreparedSegments preparedSegments =
			RoadCrewSegmentMatcher.prepare(Collections.emptyList());
	// The directed identity of ROADMAP section 159, fed from the same match the
	// legacy path uses. Absent unless someone asks for it, so the production
	// behaviour is untouched until the shadow comparison is switched on.
	private RoadCrewDirectPipeline directPipeline;
	/**
	 * The timeline both branches share. It advances once per accepted fix, so a
	 * legacy piece and a directed span can be lined up afterwards by the range of
	 * fixes each was built from - no timestamps, no trace.
	 */
	private long fixSequence;
	/** The fix after the last confirmed legacy passage: where the next one began. */
	private long legacyPassageFirstFix = 1;
	/**
	 * One recording session. Deliberately not cleared by reset(), which also runs
	 * when the loader swaps roads: the group has to live from the moment
	 * recording starts until it stops, not from one road reload to the next.
	 */
	private String comparisonGroupId;

	public RoadCrewObservationPipeline(RoadCrewObservationOutbox outbox) {
		if (outbox == null) {
			throw new IllegalArgumentException("RoadCrew observation outbox is required");
		}
		this.outbox = outbox;
		this.sink = null;
	}

	public RoadCrewObservationPipeline(PassageSink sink) {
		if (sink == null) { throw new IllegalArgumentException("Missing passage sink"); }
		this.outbox = null;
		this.sink = sink;
	}

	/** Starts a new recording session: a new timeline and a new comparison group. */
	public synchronized void startSession(String newComparisonGroupId) {
		fixSequence = 0;
		legacyPassageFirstFix = 1;
		comparisonGroupId = newComparisonGroupId;
	}

	public synchronized String getComparisonGroupId() {
		return comparisonGroupId;
	}

	public synchronized long getFixSequence() {
		return fixSequence;
	}

	/**
	 * Turns on the parallel directed pipeline. The legacy segmentation carries
	 * on exactly as before; this only observes the same matches a second time.
	 */
	public synchronized void enableDirectPipeline(RoadCrewDirectPassageAccumulator.Config config,
			RoadCrewDirectPassageAccumulator.PassageSink sink) {
		directPipeline = sink == null ? null : new RoadCrewDirectPipeline(config, sink);
	}

	/** Where finished directed passages go once shaped for the wire. */
	public synchronized void setDirectObservationSink(RoadCrewDirectPipeline.ObservationSink sink) {
		if (directPipeline != null) {
			directPipeline.setObservationSink(sink);
		}
	}

	/** Diagnostic build only; forwarded to the directed branch. */
	public synchronized void setDirectDiagnostics(RoadCrewDiagnostics diagnostics) {
		if (directPipeline != null) {
			directPipeline.setDiagnostics(diagnostics);
		}
	}

	public synchronized void setDirectMapVersion(String mapVersion) {
		if (directPipeline != null) {
			directPipeline.setMapVersion(mapVersion);
		}
	}

	public interface PassageSink {
		/**
		 * @param firstFixSequence the first fix this passage was built from
		 * @param lastFixSequence  the last one; never before the first
		 */
		void capture(RoadCrewPassageDetector.PassageEvidence evidence, long observedAtMillis,
				RouteDataObject road, RoadCrewSegmentIdentity.SegmentBinding binding,
				long firstFixSequence, long lastFixSequence) throws IOException;
	}

	public synchronized int replaceRoads(Iterable<RouteDataObject> roads) {
		roadsById.clear();
		for (RouteDataObject road : roads == null ? Collections.<RouteDataObject>emptyList() : roads) {
			if (road != null && road.pointsX != null && road.pointsY != null && road.getPointsLength() >= 2) {
				roadsById.putIfAbsent(road.getId(), road);
			}
		}
		preparedSegments = RoadCrewSegmentMatcher.prepare(roadsById.values());
		detector.reset();
		if (directPipeline != null) {
			directPipeline.replaceRoads();
		}
		return preparedSegments.size();
	}

	public synchronized ProcessingResult accept(RoadCrewSegmentMatcher.GpsFix fix,
			long elapsedRealtimeMillis, long observedAtMillis) throws IOException {
		fixSequence++;
		RoadCrewSegmentMatcher.MatchResult match = preparedSegments.match(fix);
		if (directPipeline != null) {
			// Deliberately before the legacy result is cut into pieces, and
			// never allowed to disturb it: a fault in the new path must not
			// cost a passage on the old one.
			try {
				directPipeline.accept(fix, match,
						match.getSegment() == null
								? null : roadsById.get(match.getSegment().getRoadId()),
						observedAtMillis, fixSequence);
			} catch (RuntimeException ignored) {
			}
		}
		RoadCrewPassageDetector.DetectionResult detection = detector.accept(match, elapsedRealtimeMillis);
		RoadCrewObservationOutbox.EnqueueResult enqueue = null;
		// The window of fixes that produced this legacy passage: everything since
		// the previous confirmation. The detector does not expose its own notion
		// of a start, and reaching into it would mean changing production code
		// for the sake of an experiment.
		long firstFix = legacyPassageFirstFix;
		long lastFix = fixSequence;
		if (detection.isConfirmed()) {
			legacyPassageFirstFix = fixSequence + 1;
			if (sink != null) {
				// The range is handed over, not left for the sink to read back
				// from a field: this line advanced it a moment ago, and a reader
				// arriving afterwards would see the next passage's start as this
				// passage's - a reversed range, which is silently unusable.
				sink.capture(detection.getEvidence(), observedAtMillis,
						roadsById.get(match.getSegment().getRoadId()), match.getSegment(),
						firstFix, lastFix);
			} else {
				enqueue = outbox.enqueue(detection.getEvidence(), observedAtMillis);
			}
		}
		return new ProcessingResult(match, detection, enqueue, fixSequence, firstFix, lastFix);
	}

	public synchronized void reset() {
		roadsById.clear();
		preparedSegments = RoadCrewSegmentMatcher.prepare(Collections.emptyList());
		detector.reset();
		if (directPipeline != null) {
			// Closes whatever passage was open rather than leaving it to be
			// silently extended across the break.
			directPipeline.reset();
		}
	}

	public synchronized int getPreparedSegmentCount() {
		return preparedSegments.size();
	}

	public static final class ProcessingResult {
		private final RoadCrewSegmentMatcher.MatchResult match;
		private final RoadCrewPassageDetector.DetectionResult detection;
		private final RoadCrewObservationOutbox.EnqueueResult enqueue;
		private final long fixSequence;
		private final long firstFixSequence;
		private final long lastFixSequence;

		private ProcessingResult(RoadCrewSegmentMatcher.MatchResult match,
				RoadCrewPassageDetector.DetectionResult detection,
				RoadCrewObservationOutbox.EnqueueResult enqueue,
				long fixSequence, long firstFixSequence, long lastFixSequence) {
			this.match = match;
			this.detection = detection;
			this.enqueue = enqueue;
			this.fixSequence = fixSequence;
			this.firstFixSequence = firstFixSequence;
			this.lastFixSequence = lastFixSequence;
		}

		public long getFixSequence() {
			return fixSequence;
		}

		/** The fixes this legacy passage was built from, when one was confirmed. */
		public long getFirstFixSequence() {
			return firstFixSequence;
		}

		public long getLastFixSequence() {
			return lastFixSequence;
		}

		public RoadCrewSegmentMatcher.MatchResult getMatch() {
			return match;
		}

		public RoadCrewPassageDetector.DetectionResult getDetection() {
			return detection;
		}

		public RoadCrewObservationOutbox.EnqueueResult getEnqueue() {
			return enqueue;
		}

		public boolean wasQueued() {
			return enqueue != null
					&& enqueue.getStatus() != RoadCrewObservationOutbox.EnqueueStatus.ALREADY_UPLOADED;
		}
	}
}
