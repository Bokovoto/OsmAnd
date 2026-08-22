package net.osmand.router;

/**
 * Builds minimal passage evidence from a short sequence of consistent directed
 * segment matches. Raw GPS coordinates are neither retained nor returned.
 */
public final class RoadCrewPassageDetector {

	public static final int MIN_MATCHED_FIX_COUNT = 3;
	public static final long MIN_PASSAGE_DURATION_MILLIS = 1_000;
	public static final long MAX_SAMPLE_GAP_MILLIS = 10_000;
	public static final long MAX_PASSAGE_DURATION_MILLIS = 90_000;
	public static final double MAX_BACKTRACK_METERS = 8.0;
	public static final double MAX_PROGRESS_SPEED_METERS_PER_SECOND = 55.0;
	private static final double PROGRESS_JUMP_ALLOWANCE_METERS = 15.0;
	private static final double MIN_MOVEMENT_METERS = 5.0;
	private static final double MAX_REQUIRED_MOVEMENT_METERS = 20.0;
	private static final double REQUIRED_SEGMENT_FRACTION = 0.20;

	private Tracking tracking;

	public DetectionResult accept(RoadCrewSegmentMatcher.MatchResult match, long elapsedRealtimeMillis) {
		if (elapsedRealtimeMillis < 0) {
			reset();
			return DetectionResult.withoutEvidence(Status.INVALID_TIME, 0);
		}
		if (!isUsableMatch(match)) {
			reset();
			return DetectionResult.withoutEvidence(Status.NO_MATCH, 0);
		}
		if (tracking == null) {
			tracking = Tracking.start(match, elapsedRealtimeMillis);
			return DetectionResult.withoutEvidence(Status.TRACKING, tracking.fixCount);
		}
		if (!tracking.isSameSegment(match)) {
			tracking = Tracking.start(match, elapsedRealtimeMillis);
			return DetectionResult.withoutEvidence(Status.RESET_SEGMENT_CHANGED, tracking.fixCount);
		}

		long gapMillis = elapsedRealtimeMillis - tracking.lastElapsedRealtimeMillis;
		long durationMillis = elapsedRealtimeMillis - tracking.firstElapsedRealtimeMillis;
		if (gapMillis <= 0 || gapMillis > MAX_SAMPLE_GAP_MILLIS
				|| durationMillis > MAX_PASSAGE_DURATION_MILLIS) {
			tracking = Tracking.start(match, elapsedRealtimeMillis);
			return DetectionResult.withoutEvidence(Status.RESET_TIME_GAP, tracking.fixCount);
		}
		if (match.getProgressMeters() + MAX_BACKTRACK_METERS < tracking.lastProgressMeters) {
			tracking = Tracking.start(match, elapsedRealtimeMillis);
			return DetectionResult.withoutEvidence(Status.RESET_BACKTRACK, tracking.fixCount);
		}
		double forwardDelta = match.getProgressMeters() - tracking.lastProgressMeters;
		double maximumPlausibleDelta = MAX_PROGRESS_SPEED_METERS_PER_SECOND * gapMillis / 1_000.0
				+ PROGRESS_JUMP_ALLOWANCE_METERS;
		if (forwardDelta > maximumPlausibleDelta) {
			tracking = Tracking.start(match, elapsedRealtimeMillis);
			return DetectionResult.withoutEvidence(Status.RESET_IMPLAUSIBLE_JUMP, tracking.fixCount);
		}

		tracking.add(match, elapsedRealtimeMillis);
		if (tracking.confirmed) {
			return DetectionResult.withoutEvidence(Status.ALREADY_CONFIRMED, tracking.fixCount);
		}
		double requiredMovement = requiredMovement(match.getSegmentLengthMeters());
		if (tracking.fixCount >= MIN_MATCHED_FIX_COUNT
				&& tracking.durationMillis() >= MIN_PASSAGE_DURATION_MILLIS
				&& tracking.forwardMovementMeters() >= requiredMovement) {
			tracking.confirmed = true;
			return DetectionResult.confirmed(tracking.toEvidence());
		}
		return DetectionResult.withoutEvidence(Status.TRACKING, tracking.fixCount);
	}

	public void reset() {
		tracking = null;
	}

	public boolean isTracking() {
		return tracking != null;
	}

	private static boolean isUsableMatch(RoadCrewSegmentMatcher.MatchResult match) {
		return match != null && match.isMatched() && match.getSegment() != null
				&& Double.isFinite(match.getProgressMeters()) && match.getProgressMeters() >= 0
				&& Double.isFinite(match.getSegmentLengthMeters()) && match.getSegmentLengthMeters() > 0
				&& Double.isFinite(match.getDistanceMeters()) && match.getDistanceMeters() >= 0
				&& Double.isFinite(match.getHeadingDifferenceDegrees())
				&& match.getHeadingDifferenceDegrees() >= 0;
	}

	private static double requiredMovement(double segmentLengthMeters) {
		return Math.min(MAX_REQUIRED_MOVEMENT_METERS,
				Math.max(MIN_MOVEMENT_METERS, segmentLengthMeters * REQUIRED_SEGMENT_FRACTION));
	}

	public enum Status {
		TRACKING,
		CONFIRMED,
		ALREADY_CONFIRMED,
		NO_MATCH,
		INVALID_TIME,
		RESET_SEGMENT_CHANGED,
		RESET_TIME_GAP,
		RESET_BACKTRACK,
		RESET_IMPLAUSIBLE_JUMP
	}

	public static final class DetectionResult {
		private final Status status;
		private final PassageEvidence evidence;
		private final int trackingFixCount;

		private DetectionResult(Status status, PassageEvidence evidence, int trackingFixCount) {
			this.status = status;
			this.evidence = evidence;
			this.trackingFixCount = trackingFixCount;
		}

		private static DetectionResult confirmed(PassageEvidence evidence) {
			return new DetectionResult(Status.CONFIRMED, evidence, evidence.fixCount);
		}

		private static DetectionResult withoutEvidence(Status status, int trackingFixCount) {
			return new DetectionResult(status, null, trackingFixCount);
		}

		public boolean isConfirmed() {
			return status == Status.CONFIRMED;
		}

		public Status getStatus() {
			return status;
		}

		public PassageEvidence getEvidence() {
			return evidence;
		}

		public int getTrackingFixCount() {
			return trackingFixCount;
		}
	}

	public static final class PassageEvidence {
		private final RoadCrewSegmentIdentity.SegmentKey segmentKey;
		private final int fixCount;
		private final long durationMillis;
		private final double forwardMovementMeters;
		private final double maximumDistanceMeters;
		private final double maximumHeadingDifferenceDegrees;

		private PassageEvidence(RoadCrewSegmentIdentity.SegmentKey segmentKey, int fixCount,
				long durationMillis, double forwardMovementMeters, double maximumDistanceMeters,
				double maximumHeadingDifferenceDegrees) {
			this.segmentKey = segmentKey;
			this.fixCount = fixCount;
			this.durationMillis = durationMillis;
			this.forwardMovementMeters = forwardMovementMeters;
			this.maximumDistanceMeters = maximumDistanceMeters;
			this.maximumHeadingDifferenceDegrees = maximumHeadingDifferenceDegrees;
		}

		public RoadCrewSegmentIdentity.SegmentKey getSegmentKey() {
			return segmentKey;
		}

		public int getFixCount() {
			return fixCount;
		}

		public long getDurationMillis() {
			return durationMillis;
		}

		public double getForwardMovementMeters() {
			return forwardMovementMeters;
		}

		public double getMaximumDistanceMeters() {
			return maximumDistanceMeters;
		}

		public double getMaximumHeadingDifferenceDegrees() {
			return maximumHeadingDifferenceDegrees;
		}
	}

	private static final class Tracking {
		private final RoadCrewSegmentIdentity.SegmentKey segmentKey;
		private final double firstProgressMeters;
		private final long firstElapsedRealtimeMillis;
		private int fixCount;
		private double lastProgressMeters;
		private double maximumProgressMeters;
		private long lastElapsedRealtimeMillis;
		private double maximumDistanceMeters;
		private double maximumHeadingDifferenceDegrees;
		private boolean confirmed;

		private Tracking(RoadCrewSegmentMatcher.MatchResult match, long elapsedRealtimeMillis) {
			segmentKey = match.getSegment().getKey();
			firstProgressMeters = match.getProgressMeters();
			lastProgressMeters = firstProgressMeters;
			maximumProgressMeters = firstProgressMeters;
			firstElapsedRealtimeMillis = elapsedRealtimeMillis;
			lastElapsedRealtimeMillis = elapsedRealtimeMillis;
			maximumDistanceMeters = match.getDistanceMeters();
			maximumHeadingDifferenceDegrees = match.getHeadingDifferenceDegrees();
			fixCount = 1;
		}

		private static Tracking start(RoadCrewSegmentMatcher.MatchResult match, long elapsedRealtimeMillis) {
			return new Tracking(match, elapsedRealtimeMillis);
		}

		private boolean isSameSegment(RoadCrewSegmentMatcher.MatchResult match) {
			RoadCrewSegmentIdentity.SegmentKey other = match.getSegment().getKey();
			return segmentKey.getCanonicalId().equals(other.getCanonicalId())
					&& segmentKey.getGeometryFingerprint().equals(other.getGeometryFingerprint());
		}

		private void add(RoadCrewSegmentMatcher.MatchResult match, long elapsedRealtimeMillis) {
			fixCount++;
			lastProgressMeters = match.getProgressMeters();
			maximumProgressMeters = Math.max(maximumProgressMeters, match.getProgressMeters());
			lastElapsedRealtimeMillis = elapsedRealtimeMillis;
			maximumDistanceMeters = Math.max(maximumDistanceMeters, match.getDistanceMeters());
			maximumHeadingDifferenceDegrees = Math.max(maximumHeadingDifferenceDegrees,
					match.getHeadingDifferenceDegrees());
		}

		private long durationMillis() {
			return lastElapsedRealtimeMillis - firstElapsedRealtimeMillis;
		}

		private double forwardMovementMeters() {
			return Math.max(0, maximumProgressMeters - firstProgressMeters);
		}

		private PassageEvidence toEvidence() {
			return new PassageEvidence(segmentKey, fixCount, durationMillis(), forwardMovementMeters(),
					maximumDistanceMeters, maximumHeadingDifferenceDegrees);
		}
	}
}
