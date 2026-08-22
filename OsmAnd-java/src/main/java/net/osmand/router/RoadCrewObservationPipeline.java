package net.osmand.router;

import net.osmand.binary.RouteDataObject;

import java.io.IOException;
import java.util.Collections;

/**
 * In-memory matcher/detector pipeline that writes only confirmed aggregate
 * evidence to the durable local outbox.
 */
public final class RoadCrewObservationPipeline {

	private final RoadCrewObservationOutbox outbox;
	private final RoadCrewPassageDetector detector = new RoadCrewPassageDetector();
	private RoadCrewSegmentMatcher.PreparedSegments preparedSegments =
			RoadCrewSegmentMatcher.prepare(Collections.emptyList());

	public RoadCrewObservationPipeline(RoadCrewObservationOutbox outbox) {
		if (outbox == null) {
			throw new IllegalArgumentException("RoadCrew observation outbox is required");
		}
		this.outbox = outbox;
	}

	public synchronized int replaceRoads(Iterable<RouteDataObject> roads) {
		preparedSegments = RoadCrewSegmentMatcher.prepare(roads);
		detector.reset();
		return preparedSegments.size();
	}

	public synchronized ProcessingResult accept(RoadCrewSegmentMatcher.GpsFix fix,
			long elapsedRealtimeMillis, long observedAtMillis) throws IOException {
		RoadCrewSegmentMatcher.MatchResult match = preparedSegments.match(fix);
		RoadCrewPassageDetector.DetectionResult detection = detector.accept(match, elapsedRealtimeMillis);
		RoadCrewObservationOutbox.EnqueueResult enqueue = null;
		if (detection.isConfirmed()) {
			enqueue = outbox.enqueue(detection.getEvidence(), observedAtMillis);
		}
		return new ProcessingResult(match, detection, enqueue);
	}

	public synchronized void reset() {
		preparedSegments = RoadCrewSegmentMatcher.prepare(Collections.emptyList());
		detector.reset();
	}

	public synchronized int getPreparedSegmentCount() {
		return preparedSegments.size();
	}

	public static final class ProcessingResult {
		private final RoadCrewSegmentMatcher.MatchResult match;
		private final RoadCrewPassageDetector.DetectionResult detection;
		private final RoadCrewObservationOutbox.EnqueueResult enqueue;

		private ProcessingResult(RoadCrewSegmentMatcher.MatchResult match,
				RoadCrewPassageDetector.DetectionResult detection,
				RoadCrewObservationOutbox.EnqueueResult enqueue) {
			this.match = match;
			this.detection = detection;
			this.enqueue = enqueue;
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
			return enqueue != null;
		}
	}
}
