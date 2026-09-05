package net.osmand.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects a passage by road and direction rather than by map-matched piece, as
 * ROADMAP section 165 describes. Five consecutive pieces of one road are one
 * passage from one measure to another, not five.
 *
 * The rule that governs everything here:
 *
 *   [from_m, to_m] may only be extended on proven continuity of movement along
 *   the same way and direction. Never merely because, after a pause, a fix
 *   matched the same road again.
 *
 * Taking the minimum and maximum of the measures would be actively dangerous
 * without it. A matcher reporting 100 m, then 150 m, then jumping to 950 m
 * would record 850 metres as driven when 800 of them were never touched. Two
 * adjacent honest passages are always better than one that claims road nobody
 * drove.
 *
 * Continuity is tested against two independent upper bounds: what is physically
 * possible in the elapsed time, and what the movement actually measured between
 * the fixes allows. The measure says where along the road the match moved; time
 * and real movement say whether that could have happened.
 */
public final class RoadCrewDirectPassageAccumulator {

	/**
	 * Detection policy. Everything here may be tuned from the server later.
	 * Nothing here is part of the wire contract - canonicalisation, the
	 * algorithms, the meaning of F and R and the way a wrap is represented are
	 * fixed and must never become configurable.
	 */
	public static final class Config {
		/** 180 km/h: an upper bound on what a lorry can possibly have done. */
		public final double hardMaxSpeedMetersPerSecond;
		public final double baseProgressToleranceMeters;
		public final double movementProgressFactor;
		/** Small backward jitter that must not end a passage. */
		public final double backtrackToleranceMeters;
		public final long gapGraceMillis;
		public final int maxMissingFixes;
		public final int newWayConsecutiveMatches;
		public final int newWayWindow;
		public final int newWayMatchesInWindow;

		public Config(double hardMaxSpeedMetersPerSecond, double baseProgressToleranceMeters,
				double movementProgressFactor, double backtrackToleranceMeters,
				long gapGraceMillis, int maxMissingFixes, int newWayConsecutiveMatches,
				int newWayWindow, int newWayMatchesInWindow) {
			this.hardMaxSpeedMetersPerSecond = hardMaxSpeedMetersPerSecond;
			this.baseProgressToleranceMeters = baseProgressToleranceMeters;
			this.movementProgressFactor = movementProgressFactor;
			this.backtrackToleranceMeters = backtrackToleranceMeters;
			this.gapGraceMillis = gapGraceMillis;
			this.maxMissingFixes = maxMissingFixes;
			this.newWayConsecutiveMatches = newWayConsecutiveMatches;
			this.newWayWindow = newWayWindow;
			this.newWayMatchesInWindow = newWayMatchesInWindow;
		}

		public static final Config DEFAULT_V1 =
				new Config(50, 30, 1.5, 20, 8000, 3, 2, 4, 3);
	}

	/** One map-matched fix, already converted into canonical terms. */
	public static final class Fix {
		public final long wayId;
		public final boolean forward;
		public final double measureMeters;
		public final boolean closed;
		public final double wayLengthMeters;
		public final long timeMillis;
		/** Movement actually measured since the previous fix. */
		public final double movementSincePreviousMeters;
		/** Position on the recording session shared timeline. */
		public final long fixSequence;
		/**
		 * How well the shared matcher placed this fix. Both branches are fed by
		 * the one matcher, so carrying its judgement here lets a directed
		 * observation be held to the same quality bar as a legacy one.
		 */
		public final double matchDistanceMeters;
		public final double headingDifferenceDegrees;
		/**
		 * Whatever the caller needs to describe this way, carried through
		 * untouched. It travels onto the passage so that a finished passage is
		 * self-sufficient: nothing about it may depend on where the vehicle
		 * happens to be by the time it closes.
		 */
		public final Object attachment;

		public Fix(long wayId, boolean forward, double measureMeters, boolean closed,
				double wayLengthMeters, long timeMillis, double movementSincePreviousMeters) {
			this(wayId, forward, measureMeters, closed, wayLengthMeters, timeMillis,
					movementSincePreviousMeters, 0);
		}

		public Fix(long wayId, boolean forward, double measureMeters, boolean closed,
				double wayLengthMeters, long timeMillis, double movementSincePreviousMeters,
				long fixSequence) {
			this(wayId, forward, measureMeters, closed, wayLengthMeters, timeMillis,
					movementSincePreviousMeters, fixSequence, 0, 0);
		}

		public Fix(long wayId, boolean forward, double measureMeters, boolean closed,
				double wayLengthMeters, long timeMillis, double movementSincePreviousMeters,
				long fixSequence, double matchDistanceMeters, double headingDifferenceDegrees) {
			this(wayId, forward, measureMeters, closed, wayLengthMeters, timeMillis,
					movementSincePreviousMeters, fixSequence, matchDistanceMeters,
					headingDifferenceDegrees, null);
		}

		public Fix(long wayId, boolean forward, double measureMeters, boolean closed,
				double wayLengthMeters, long timeMillis, double movementSincePreviousMeters,
				long fixSequence, double matchDistanceMeters, double headingDifferenceDegrees,
				Object attachment) {
			this.matchDistanceMeters = matchDistanceMeters;
			this.headingDifferenceDegrees = headingDifferenceDegrees;
			this.attachment = attachment;
			this.wayId = wayId;
			this.forward = forward;
			this.measureMeters = measureMeters;
			this.closed = closed;
			this.wayLengthMeters = wayLengthMeters;
			this.timeMillis = timeMillis;
			this.movementSincePreviousMeters = movementSincePreviousMeters;
			this.fixSequence = fixSequence;
		}
	}

	/** An ascending measure interval, as the wire contract requires. */
	public static final class Span {
		public final double fromMeasureMeters;
		public final double toMeasureMeters;

		Span(double fromMeasureMeters, double toMeasureMeters) {
			this.fromMeasureMeters = fromMeasureMeters;
			this.toMeasureMeters = toMeasureMeters;
		}
	}

	/**
	 * A finished passage. A traversal that wraps past the end of a ring - or
	 * goes round more than once - produces several spans; each becomes its own
	 * observation on the wire, tied together by one group.
	 */
	public static final class Passage {
		public final long wayId;
		public final boolean forward;
		public final List<Span> spans;
		public final long startTimeMillis;
		public final long endTimeMillis;
		public final int fixCount;
		public final double progressMeters;
		/**
		 * Where this passage sits on the session timeline the legacy pipeline
		 * shares. It is what lets four legacy pieces and one directed span be
		 * recognised afterwards as the same stretch of driving.
		 */
		public final long firstFixSequence;
		public final long lastFixSequence;
		/** The worst the shared matcher did anywhere in this passage. */
		public final double maximumDistanceMeters;
		public final double maximumHeadingDifferenceDegrees;
		/**
		 * The caller's description of the way, taken when this passage started.
		 *
		 * A passage closes only once the *next* way has proved itself, so at that
		 * moment the vehicle is already elsewhere. Anything resolved then - a
		 * pointer to the current way, or a lookup by id - describes the wrong
		 * road or relies on an ordering that will be broken again later. Carrying
		 * it makes the passage answer for itself.
		 */
		public final Object attachment;

		Passage(long wayId, boolean forward, List<Span> spans, long startTimeMillis,
				long endTimeMillis, int fixCount, double progressMeters,
				long firstFixSequence, long lastFixSequence) {
			this(wayId, forward, spans, startTimeMillis, endTimeMillis, fixCount, progressMeters,
					firstFixSequence, lastFixSequence, 0, 0, null);
		}

		Passage(long wayId, boolean forward, List<Span> spans, long startTimeMillis,
				long endTimeMillis, int fixCount, double progressMeters,
				long firstFixSequence, long lastFixSequence,
				double maximumDistanceMeters, double maximumHeadingDifferenceDegrees) {
			this(wayId, forward, spans, startTimeMillis, endTimeMillis, fixCount, progressMeters,
					firstFixSequence, lastFixSequence, maximumDistanceMeters,
					maximumHeadingDifferenceDegrees, null);
		}

		Passage(long wayId, boolean forward, List<Span> spans, long startTimeMillis,
				long endTimeMillis, int fixCount, double progressMeters,
				long firstFixSequence, long lastFixSequence,
				double maximumDistanceMeters, double maximumHeadingDifferenceDegrees,
				Object attachment) {
			this.maximumDistanceMeters = maximumDistanceMeters;
			this.maximumHeadingDifferenceDegrees = maximumHeadingDifferenceDegrees;
			this.attachment = attachment;
			this.wayId = wayId;
			this.forward = forward;
			this.spans = spans;
			this.startTimeMillis = startTimeMillis;
			this.endTimeMillis = endTimeMillis;
			this.fixCount = fixCount;
			this.progressMeters = progressMeters;
			this.firstFixSequence = firstFixSequence;
			this.lastFixSequence = lastFixSequence;
		}
	}

	public interface PassageSink {
		void accept(Passage passage);
	}

	private static final double EPSILON = 0.0005;

	private final Config config;
	private final PassageSink sink;

	// The passage being built.
	private boolean active;
	private long wayId;
	private boolean forward;
	private boolean closed;
	private double wayLength;
	private double startMeasure;
	private double progress;
	private double lastMeasure;
	private long startTime;
	private long lastConfirmedTime;
	private int fixCount;
	private long firstFixSequence;
	private RoadCrewDiagnostics diagnostics;
	/** The caller's description of the way this passage is on, taken at its start. */
	private Object attachment;
	private double maximumDistanceMeters;
	private double maximumHeadingDifferenceDegrees;
	private long lastFixSequence;

	// A road that may be taking over, and the fixes it has offered so far. The
	// active passage is not extended through this uncertainty, so that if the
	// candidate turns out to be noise nothing false was recorded, and if it
	// turns out to be real the new road starts where it actually started.
	private final List<Fix> candidate = new ArrayList<>();
	private final List<Long> recentWays = new ArrayList<>();

	private int missingFixes;
	private long lastSeenTime;

	public RoadCrewDirectPassageAccumulator(Config config, PassageSink sink) {
		if (config == null || sink == null) {
			throw new IllegalArgumentException("A passage accumulator needs a config and a sink.");
		}
		this.config = config;
		this.sink = sink;
	}

	/**
	 * Directional progress from one measure to another. On a ring this is
	 * modular, so passing the end of the way is simply a small forward step -
	 * which is why a wrap needs no special case here, and an impossible jump is
	 * still caught by the same continuity test as anywhere else.
	 */
	static double directionalProgress(double previous, double current, boolean forward,
			boolean closed, double length) {
		double progress = forward ? current - previous : previous - current;
		if (closed && progress < 0) {
			progress += length;
		}
		return progress;
	}

	private boolean continuous(double progress, long deltaMillis, double movement) {
		if (progress < -config.backtrackToleranceMeters) {
			return false;
		}
		double seconds = Math.max(0, deltaMillis) / 1000.0;
		double physicalLimit = config.hardMaxSpeedMetersPerSecond * seconds
				+ config.baseProgressToleranceMeters;
		if (progress > physicalLimit) {
			return false;
		}
		// The second witness: the match may have moved along the road further
		// than the vehicle actually moved, which time alone would allow.
		double movementLimit = movement * config.movementProgressFactor
				+ config.baseProgressToleranceMeters;
		return progress <= movementLimit;
	}

	/** Diagnostic build only. */
	public void setDiagnostics(RoadCrewDiagnostics diagnostics) {
		this.diagnostics = diagnostics;
	}

	private void count(String name) {
		if (diagnostics != null) {
			diagnostics.count(name);
		}
	}

	public void accept(Fix fix) {
		if (fix == null) {
			throw new IllegalArgumentException("A fix is required.");
		}
		lastSeenTime = fix.timeMillis;
		missingFixes = 0;
		rememberWay(fix.wayId);

		if (!active) {
			start(fix);
			return;
		}
		if (fix.wayId == wayId && fix.forward == forward) {
			double step = directionalProgress(lastMeasure, fix.measureMeters, forward,
					closed, wayLength);
			long delta = fix.timeMillis - lastConfirmedTime;
			if (continuous(step, delta, movementSince(fix))) {
				candidate.clear();
				extend(fix, step);
			} else {
				count("passages_closed_continuity");
				finish(lastConfirmedTime);
				start(fix);
			}
			return;
		}
		if (fix.wayId == wayId) {
			// The same road the other way round. Never merged: a turn is exactly
			// what the behaviour analysis will want to see.
			count("passages_closed_direction_change");
			finish(lastConfirmedTime);
			start(fix);
			return;
		}
		offerCandidate(fix);
	}

	/** No usable match for this moment. */
	public void acceptNoMatch(long timeMillis) {
		lastSeenTime = timeMillis;
		if (!active) {
			return;
		}
		missingFixes++;
		if (missingFixes > config.maxMissingFixes
				|| timeMillis - lastConfirmedTime > config.gapGraceMillis) {
			count("passages_closed_gap");
			finish(lastConfirmedTime);
		}
	}

	/** Ends whatever is open, at the end of a trip or when recording stops. */
	public void flush() {
		if (active) {
			finish(lastConfirmedTime);
		}
		candidate.clear();
		recentWays.clear();
	}

	private double movementSince(Fix fix) {
		double movement = fix.movementSincePreviousMeters;
		return movement > 0 ? movement : 0;
	}

	private void rememberWay(long id) {
		recentWays.add(id);
		while (recentWays.size() > config.newWayWindow) {
			recentWays.remove(0);
		}
	}

	private void offerCandidate(Fix fix) {
		candidate.add(fix);
		boolean consecutive = candidate.size() >= config.newWayConsecutiveMatches;
		if (consecutive) {
			for (int index = candidate.size() - config.newWayConsecutiveMatches;
					index < candidate.size(); index++) {
				if (candidate.get(index).wayId != fix.wayId) {
					consecutive = false;
					break;
				}
			}
		}
		int inWindow = 0;
		for (Long id : recentWays) {
			if (id == fix.wayId) {
				inWindow++;
			}
		}
		boolean dominant = recentWays.size() >= config.newWayWindow
				&& inWindow >= config.newWayMatchesInWindow;
		if (!consecutive && !dominant) {
			// Still only a suggestion. The active passage stays where it was
			// last certain, so jitter between two parallel roads cannot shatter
			// it into fragments.
			return;
		}
		Fix first = firstCandidateOf(fix.wayId);
		count("passages_closed_way_change");
		finish(lastConfirmedTime);
		start(first);
		for (int index = candidate.indexOf(first) + 1; index < candidate.size(); index++) {
			Fix later = candidate.get(index);
			if (later.wayId == wayId && later.forward == forward) {
				double step = directionalProgress(lastMeasure, later.measureMeters, forward,
						closed, wayLength);
				if (continuous(step, later.timeMillis - lastConfirmedTime, movementSince(later))) {
					extend(later, step);
				}
			}
		}
		candidate.clear();
	}

	private Fix firstCandidateOf(long id) {
		for (Fix fix : candidate) {
			if (fix.wayId == id) {
				return fix;
			}
		}
		return candidate.get(candidate.size() - 1);
	}

	private void start(Fix fix) {
		count("passages_started");
		if (diagnostics != null) {
			diagnostics.event(fix.fixSequence, "RCS2_PASSAGE_START", "way=" + fix.wayId
					+ (fix.forward ? " F" : " R"));
		}
		active = true;
		wayId = fix.wayId;
		forward = fix.forward;
		closed = fix.closed;
		wayLength = fix.wayLengthMeters;
		startMeasure = fix.measureMeters;
		lastMeasure = fix.measureMeters;
		progress = 0;
		startTime = fix.timeMillis;
		lastConfirmedTime = fix.timeMillis;
		fixCount = 1;
		firstFixSequence = fix.fixSequence;
		lastFixSequence = fix.fixSequence;
		attachment = fix.attachment;
		maximumDistanceMeters = Math.max(0, fix.matchDistanceMeters);
		maximumHeadingDifferenceDegrees = Math.max(0, fix.headingDifferenceDegrees);
		missingFixes = 0;
	}

	private void extend(Fix fix, double step) {
		if (step > 0) {
			progress += step;
			lastMeasure = fix.measureMeters;
		}
		lastConfirmedTime = fix.timeMillis;
		lastFixSequence = fix.fixSequence;
		maximumDistanceMeters = Math.max(maximumDistanceMeters, fix.matchDistanceMeters);
		maximumHeadingDifferenceDegrees =
				Math.max(maximumHeadingDifferenceDegrees, fix.headingDifferenceDegrees);
		fixCount++;
	}

	private void finish(long endTime) {
		if (!active) {
			return;
		}
		active = false;
		if (progress <= EPSILON) {
			count("passages_discarded_under_min_progress");
			if (diagnostics != null) {
				diagnostics.event(lastFixSequence, "RCS2_PASSAGE_DISCARDED",
						"way=" + wayId + " progress=0");
			}
		}
		if (progress > EPSILON) {
			count("passages_emitted");
			if (diagnostics != null) {
				diagnostics.event(lastFixSequence, "RCS2_PASSAGE_EMIT", "way=" + wayId
						+ " metres=" + Math.round(progress));
			}
			sink.accept(new Passage(wayId, forward, buildSpans(), startTime, endTime,
					fixCount, progress, firstFixSequence, lastFixSequence,
					maximumDistanceMeters, maximumHeadingDifferenceDegrees, attachment));
		}
		progress = 0;
		fixCount = 0;
	}

	/**
	 * Turns the accumulated directional progress into ascending intervals. A
	 * traversal that passes the end of a ring becomes two, and one that goes
	 * round more than once becomes more than two - there is deliberately no
	 * limit of two parts.
	 */
	private List<Span> buildSpans() {
		List<Span> spans = new ArrayList<>();
		double remaining = progress;
		double position = startMeasure;
		while (remaining > EPSILON) {
			double available = forward ? wayLength - position : position;
			if (available <= EPSILON) {
				if (!closed) {
					break;
				}
				position = forward ? 0 : wayLength;
				available = wayLength;
			}
			double step = Math.min(remaining, available);
			if (forward) {
				spans.add(new Span(position, position + step));
				position += step;
			} else {
				spans.add(new Span(position - step, position));
				position -= step;
			}
			remaining -= step;
			if (!closed && remaining > EPSILON) {
				break;
			}
		}
		return spans;
	}
}
