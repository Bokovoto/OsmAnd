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

	public interface PassageSink {
		void capture(RoadCrewPassageDetector.PassageEvidence evidence, long observedAtMillis,
				RouteDataObject road, RoadCrewSegmentIdentity.SegmentBinding binding) throws IOException;
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
		return preparedSegments.size();
	}

	public synchronized ProcessingResult accept(RoadCrewSegmentMatcher.GpsFix fix,
			long elapsedRealtimeMillis, long observedAtMillis) throws IOException {
		RoadCrewSegmentMatcher.MatchResult match = preparedSegments.match(fix);
		RoadCrewPassageDetector.DetectionResult detection = detector.accept(match, elapsedRealtimeMillis);
		RoadCrewObservationOutbox.EnqueueResult enqueue = null;
		if (detection.isConfirmed()) {
			if (sink != null) {
				sink.capture(detection.getEvidence(), observedAtMillis,
						roadsById.get(match.getSegment().getRoadId()), match.getSegment());
			} else {
				enqueue = outbox.enqueue(detection.getEvidence(), observedAtMillis);
			}
		}
		return new ProcessingResult(match, detection, enqueue);
	}

	public synchronized void reset() {
		roadsById.clear();
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
			return enqueue != null
					&& enqueue.getStatus() != RoadCrewObservationOutbox.EnqueueStatus.ALREADY_UPLOADED;
		}
	}
}
