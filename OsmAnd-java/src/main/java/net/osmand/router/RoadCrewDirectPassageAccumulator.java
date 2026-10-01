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
		/**
		 * ROADMAP 330: the same way is joined across a disappearance of GPS only
		 * when the map shows no branch in between. The historic configurations
		 * leave it off, so the offline replays keep reproducing what they recorded.
		 */
		public final boolean proofAcrossSilence;
		/** ROADMAP 348: no map-only inference across missing GPS or unseen roads. */
		public final boolean requireObservedGps;

		public Config(double hardMaxSpeedMetersPerSecond, double baseProgressToleranceMeters,
				double movementProgressFactor, double backtrackToleranceMeters,
				long gapGraceMillis, int maxMissingFixes, int newWayConsecutiveMatches,
				int newWayWindow, int newWayMatchesInWindow) {
			this(hardMaxSpeedMetersPerSecond, baseProgressToleranceMeters, movementProgressFactor,
					backtrackToleranceMeters, gapGraceMillis, maxMissingFixes,
					newWayConsecutiveMatches, newWayWindow, newWayMatchesInWindow, false);
		}

		public Config(double hardMaxSpeedMetersPerSecond, double baseProgressToleranceMeters,
				double movementProgressFactor, double backtrackToleranceMeters,
				long gapGraceMillis, int maxMissingFixes, int newWayConsecutiveMatches,
				int newWayWindow, int newWayMatchesInWindow, boolean proofAcrossSilence) {
			this(hardMaxSpeedMetersPerSecond, baseProgressToleranceMeters, movementProgressFactor,
					backtrackToleranceMeters, gapGraceMillis, maxMissingFixes,
					newWayConsecutiveMatches, newWayWindow, newWayMatchesInWindow, proofAcrossSilence, false);
		}

		private Config(double hardMaxSpeedMetersPerSecond, double baseProgressToleranceMeters,
				double movementProgressFactor, double backtrackToleranceMeters,
				long gapGraceMillis, int maxMissingFixes, int newWayConsecutiveMatches,
				int newWayWindow, int newWayMatchesInWindow, boolean proofAcrossSilence,
				boolean requireObservedGps) {
			this.proofAcrossSilence = proofAcrossSilence;
			this.requireObservedGps = requireObservedGps;
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
		/**
		 * Galin's experiment (ROADMAP 321, 322): neither the number of refused
		 * fixes nor the time without one ends a passage; only the progress
		 * checks and a change of way or direction do. Otherwise DEFAULT_V1.
		 */
		public static final Config EXPERIMENT_321 =
				new Config(50, 30, 1.5, 20, Long.MAX_VALUE, Integer.MAX_VALUE, 2, 4, 3);
		/**
		 * Galin's rule, ROADMAP 330: EXPERIMENT_321, except that a disappearance of
		 * GPS on one way is joined only where the map proves there was nowhere
		 * else to go.
		 */
		public static final Config PROVEN_330 =
				new Config(50, 30, 1.5, 20, Long.MAX_VALUE, Integer.MAX_VALUE, 2, 4, 3, true);
		/** Keep measured stretches; another driver's GPS may fill the shared map later. */
		public static final Config GPS_OBSERVED_348 =
				new Config(50, 30, 1.5, 20, Long.MAX_VALUE, Integer.MAX_VALUE, 2, 4, 3, false, true);
	}

	/**
	 * ROADMAP 330, Galin, 26.09: "if a truck cannot prove it drove a road, better
	 * leave it as never driven". GPS has disappeared when two consecutive
	 * positions are further apart than this; unmatched fixes are GPS present.
	 */
	public static final long SILENCE_MILLIS = 10_000;
	/** R1: every position of a turn seen by GPS lies this close to the shared node. */
	public static final double JUNCTION_RADIUS_METERS = 60;
	/**
	 * How far along a way the shared node may lie from the fix next to it and
	 * still be taken as this turn's node. Every position of the turn is within
	 * JUNCTION_RADIUS_METERS of the node in a straight line; along a bending
	 * road it can be somewhat more, never this much. Two ways can meet twice,
	 * and the node found first may be the other meeting, far along.
	 */
	static final double MAX_JUNCTION_GAP_METERS = 2 * JUNCTION_RADIUS_METERS;
	/** Twenty minutes at one fix a second; older positions are forgotten first. */
	static final int MAX_GAP_POSITIONS = 1_200;

	/**
	 * What the map proves between two points of one drive. Ways are the callers'
	 * attachments, measures are canonical, exactly as on the fixes.
	 */
	public interface Topology {
		/**
		 * R1: the two ways share a node that every position lies within
		 * {@code radiusMeters} of. Each position is {latitude, longitude}.
		 */
		boolean meetAt(Object fromWay, Object toWay, List<double[]> positions, double radiusMeters);

		/**
		 * R1, and where: null when the ways do not meet as meetAt asks. A map
		 * that can only say yes or no answers {@link Junction#UNPLACED}.
		 */
		default Junction junction(Object fromWay, Object toWay, List<double[]> positions,
				double radiusMeters) {
			return meetAt(fromWay, toWay, positions, radiusMeters) ? Junction.UNPLACED : null;
		}

		/**
		 * R2: the only path from one point to the other, when no road branches
		 * off it: the ways strictly between the two, in travel order - empty when
		 * the two points are on the same way or on ways that meet. Null when the
		 * path has a branch, is not found, or cannot be checked.
		 */
		List<Leg> withoutBranch(Object fromWay, boolean fromForward, double fromMeasure,
				Object toWay, boolean toForward, double toMeasure);
	}

	/**
	 * Where two ways meet, in canonical measures: each node they share near the
	 * turn, on the way being left and on the way being entered, in pairs. None
	 * when the map cannot say; more than one when the ways meet more than once
	 * there (ROADMAP 347).
	 */
	public static final class Junction {
		public static final Junction UNPLACED = new Junction(new double[0], new double[0]);

		final double[] fromWayMeasures;
		final double[] toWayMeasures;

		public Junction(double fromWayMeasureMeters, double toWayMeasureMeters) {
			this(new double[] {fromWayMeasureMeters}, new double[] {toWayMeasureMeters});
		}

		public Junction(double[] fromWayMeasures, double[] toWayMeasures) {
			if (fromWayMeasures == null || toWayMeasures == null
					|| fromWayMeasures.length != toWayMeasures.length) {
				throw new IllegalArgumentException("A node is a pair: one measure on each way.");
			}
			this.fromWayMeasures = fromWayMeasures.clone();
			this.toWayMeasures = toWayMeasures.clone();
		}
	}

	/** A way crossed from end to end without a single fix on it. */
	public static final class Leg {
		public final long wayId;
		public final boolean forward;
		public final double lengthMeters;
		public final Object attachment;

		public Leg(long wayId, boolean forward, double lengthMeters, Object attachment) {
			this.wayId = wayId;
			this.forward = forward;
			this.lengthMeters = lengthMeters;
			this.attachment = attachment;
		}
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
		/**
		 * ROADMAP 330: this passage continues the one handed over just before it,
		 * proven by R1 or R2. False for the first of a drive and for anything not
		 * proven - the server then records no turn and no finished exit cell.
		 */
		public final boolean joinsPrevious;
		/** A way crossed without a fix (R2): 0 fixes, recorded because nothing branched. */
		public final boolean bridged;

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
			this(wayId, forward, spans, startTimeMillis, endTimeMillis, fixCount, progressMeters,
					firstFixSequence, lastFixSequence, maximumDistanceMeters,
					maximumHeadingDifferenceDegrees, attachment, false, false);
		}

		Passage(long wayId, boolean forward, List<Span> spans, long startTimeMillis,
				long endTimeMillis, int fixCount, double progressMeters,
				long firstFixSequence, long lastFixSequence,
				double maximumDistanceMeters, double maximumHeadingDifferenceDegrees,
				Object attachment, boolean joinsPrevious, boolean bridged) {
			this.joinsPrevious = joinsPrevious;
			this.bridged = bridged;
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
	private FixTrace trace;

	/**
	 * A passive record of decisions this class has ALREADY taken, for the
	 * diagnostic replay of ROADMAP section 199.
	 *
	 * Deliberately not routed through RoadCrewDiagnostics. That has a bounded
	 * event trace - two hundred entries - because on a phone a longer one starts
	 * to resemble the trace of a driver, which the whole system exists not to
	 * keep. This is set only by an offline replay, is null everywhere else, and
	 * so costs a null check per decision and nothing more.
	 *
	 * It may not influence anything. No condition, threshold, state transition
	 * or call order changes for it; there are no new early returns; it reports
	 * what has been decided and never takes part in deciding.
	 */
	public interface FixTrace {
		void note(long fixSequence, String what, String detail);
	}
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

	private Topology topology;
	/** Whether the active passage was proven to continue the one before it. */
	private boolean joinsPrevious;
	/**
	 * GPS positions since the active passage's last confirmed fix, each
	 * {time, latitude, longitude}. Only position() adds to it: a fix too
	 * inaccurate to be a position is, for this purpose, no GPS at all.
	 */
	private final List<double[]> gap = new ArrayList<>();

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

	/** Without one nothing is ever joined, and with PROVEN_330 a silence always splits. */
	public void setTopology(Topology topology) {
		this.topology = topology;
	}

	/**
	 * A GPS position good enough to be one - matched, unmatched, or without
	 * speed and bearing. Called before accept() or acceptNoMatch() for the same
	 * moment. It is how a disappearance is told from a truck merely unmatched.
	 */
	public void position(long timeMillis, double latitude, double longitude) {
		if (!gap.isEmpty() && gap.get(gap.size() - 1)[0] == timeMillis) {
			return;
		}
		if (gap.size() >= MAX_GAP_POSITIONS) {
			gap.remove(0);
		}
		gap.add(new double[] {timeMillis, latitude, longitude});
	}

	/** Diagnostic build only. */
	public void setFixTrace(FixTrace trace) {
		this.trace = trace;
	}

	private void note(long fixSequence, String what, String detail) {
		FixTrace sink = trace;
		if (sink != null) {
			sink.note(fixSequence, what, detail);
		}
	}

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
			start(fix, false);
			note(fix.fixSequence, "STARTED_IDLE", "way=" + fix.wayId
					+ " " + (fix.forward ? "F" : "R"));
			return;
		}
		if (config.requireObservedGps && silent(lastConfirmedTime, fix.timeMillis)) {
			// Check before candidate confirmation too: its first fix may precede the hole.
			count("passages_split_unobserved_gps");
			finish(lastConfirmedTime);
			candidate.clear();
			recentWays.clear();
			rememberWay(fix.wayId);
			start(fix, false);
			note(fix.fixSequence, "GPS_GAP_SPLIT", "way=" + fix.wayId);
			return;
		}
		if (fix.wayId == wayId && fix.forward == forward) {
			double step = directionalProgress(lastMeasure, fix.measureMeters, forward,
					closed, wayLength);
			long delta = fix.timeMillis - lastConfirmedTime;
			if (continuous(step, delta, movementSince(fix))) {
				if (config.proofAcrossSilence && silent(lastConfirmedTime, fix.timeMillis)
						&& !provenAlongTheSameWay(fix)) {
					// ROADMAP 330: GPS disappeared and the map cannot rule out that the
					// truck left and came back. Each side keeps what its fixes proved.
					count("passages_split_unproven_silence");
					finish(lastConfirmedTime);
					start(fix, false);
					note(fix.fixSequence, "SILENCE_NOT_PROVEN", "way=" + fix.wayId
							+ " " + (fix.forward ? "F" : "R") + " deltaMs=" + delta);
					return;
				}
				if (delta > 60_000) {
					// Measured, never refused (ROADMAP 322).
					count("bridged_gap_over_60s");
				}
				candidate.clear();
				extend(fix, step);
				note(fix.fixSequence, "EXTENDED", "way=" + fix.wayId
						+ " " + (fix.forward ? "F" : "R"));
			} else {
				count("passages_closed_continuity");
				finish(lastConfirmedTime);
				start(fix, false);
				note(fix.fixSequence, "CONTINUITY_REFUSED", "way=" + fix.wayId
						+ " " + (fix.forward ? "F" : "R") + " step=" + Math.round(step)
						+ " deltaMs=" + delta + " moved=" + Math.round(movementSince(fix)));
			}
			return;
		}
		if (fix.wayId == wayId) {
			// The same road the other way round. Never merged: a turn is exactly
			// what the behaviour analysis will want to see.
			count("passages_closed_direction_change");
			finish(lastConfirmedTime);
			start(fix, false);
			note(fix.fixSequence, "DIRECTION_CHANGE", "way=" + fix.wayId
					+ " now " + (fix.forward ? "F" : "R"));
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
		if (diagnostics != null) {
			diagnostics.finishCoverage();
		}
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
			note(fix.fixSequence, "CANDIDATE_PENDING", "way=" + fix.wayId
					+ " " + (fix.forward ? "F" : "R") + " candidates=" + candidate.size()
					+ " inWindow=" + inWindow + " activeWay=" + wayId);
			return;
		}
		Fix first = firstCandidateOf(fix.wayId);
		note(fix.fixSequence, "CANDIDATE_CONFIRMED", "way=" + fix.wayId
				+ " " + (fix.forward ? "F" : "R") + " startsAtFix=" + first.fixSequence
				+ (consecutive ? " consecutive" : " dominant"));
		count("passages_closed_way_change");
		Proof proof = prove(first);
		if (proof.legs != null) {
			// R2: nothing branched, so the truck drove the rest of this way.
			reachTheEnd();
		}
		// R1: GPS saw the turn, so the truck drove up to the node it turned at -
		// on both ways, the one node both agree on, or nothing is added.
		double[] turn = proof.junction != null ? turnNode(proof.junction, first) : null;
		if (turn != null) {
			reachTheJunction(turn[0]);
		}
		boolean chained = finish(lastConfirmedTime);
		if (proof.legs != null) {
			chained = bridge(proof.legs, chained);
		}
		start(first, proof.proven && chained);
		if (proof.legs != null) {
			fromTheStart(first);
		}
		if (turn != null) {
			fromTheJunction(first, turn[1]);
		}
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
		// Whatever else was in the candidate list belonged to a way that was not
		// confirmed. Named here because that is the difference between a fix
		// refused on purpose and a fix that could have been recovered, and no
		// figure computed afterwards can tell the two apart.
		for (Fix pending : candidate) {
			if (pending.wayId != wayId) {
				note(pending.fixSequence, "CANDIDATE_DISCARDED", "way=" + pending.wayId
						+ " " + (pending.forward ? "F" : "R") + " confirmedWay=" + wayId);
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

	private static final class Proof {
		final boolean proven;
		/** R2 only: the ways crossed in between, possibly none. */
		final List<Leg> legs;
		/** R1 only: where the two ways meet. */
		final Junction junction;

		Proof(boolean proven, List<Leg> legs, Junction junction) {
			this.proven = proven;
			this.legs = legs;
			this.junction = junction;
		}
	}

	private static final Proof NOT_PROVEN = new Proof(false, null, null);

	/** Whether the passage about to start on {@code next} continues the active one. */
	private Proof prove(Fix next) {
		if (topology == null || next.wayId == wayId) {
			return NOT_PROVEN;
		}
		List<double[]> seen = positionsBetween(lastConfirmedTime, next.timeMillis);
		boolean gpsLost = seen == null || hasSilence(seen);
		Junction junction = gpsLost ? null : topology.junction(attachment, next.attachment,
				latLon(seen), JUNCTION_RADIUS_METERS);
		if (junction != null) {
			count("joined_r1_junction");
			return new Proof(true, null, junction);
		}
		if (config.requireObservedGps) {
			count(gpsLost ? "join_refused_after_silence" : "join_refused_unproven");
			return NOT_PROVEN;
		}
		List<Leg> legs = topology.withoutBranch(attachment, forward, lastMeasure,
				next.attachment, next.forward, next.measureMeters);
		if (legs != null) {
			count("joined_r2_no_branch");
			return new Proof(true, legs, null);
		}
		count(gpsLost ? "join_refused_after_silence" : "join_refused_unproven");
		return NOT_PROVEN;
	}

	private boolean provenAlongTheSameWay(Fix fix) {
		if (topology == null) {
			return false;
		}
		List<Leg> legs = topology.withoutBranch(attachment, forward, lastMeasure,
				fix.attachment, fix.forward, fix.measureMeters);
		if (legs != null && legs.isEmpty()) {
			count("joined_r2_same_way");
			return true;
		}
		return false;
	}

	/** Did GPS disappear between these two moments? Unknown counts as yes. */
	private boolean silent(long fromTime, long toTime) {
		List<double[]> seen = positionsBetween(fromTime, toTime);
		return seen == null || hasSilence(seen);
	}

	private static boolean hasSilence(List<double[]> seen) {
		for (int index = 1; index < seen.size(); index++) {
			if (seen.get(index)[0] - seen.get(index - 1)[0] > SILENCE_MILLIS) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every position from one moment to the other, both ends included - or null
	 * when either end is missing, which means part of the record is gone.
	 */
	private List<double[]> positionsBetween(long fromTime, long toTime) {
		List<double[]> seen = new ArrayList<>();
		for (double[] entry : gap) {
			if (entry[0] >= fromTime && entry[0] <= toTime) {
				seen.add(entry);
			}
		}
		if (seen.isEmpty() || seen.get(0)[0] != fromTime
				|| seen.get(seen.size() - 1)[0] != toTime) {
			return null;
		}
		return seen;
	}

	private static List<double[]> latLon(List<double[]> seen) {
		List<double[]> positions = new ArrayList<>(seen.size());
		for (double[] entry : seen) {
			positions.add(new double[] {entry[1], entry[2]});
		}
		return positions;
	}

	private void forgetPositionsBefore(long timeMillis) {
		while (!gap.isEmpty() && gap.get(0)[0] < timeMillis) {
			gap.remove(0);
		}
	}

	/** R2 across a change of way: the active way was driven to its end. */
	private void reachTheEnd() {
		double rest = forward ? wayLength - lastMeasure : lastMeasure;
		if (rest > 0) {
			progress += rest;
			lastMeasure = forward ? wayLength : 0;
		}
	}

	/**
	 * Which shared node the truck turned at, as {on the way being left, on the
	 * way being entered} - or null when that is not known.
	 *
	 * The turn's node lies at or ahead of the last fix on the way left and at
	 * or behind the first fix on the way entered, and within
	 * MAX_JUNCTION_GAP_METERS of each. A node the truck had already passed, or
	 * had not yet reached, is not where it turned. The two ways decide together:
	 * each side choosing alone once filled b from a node a showed was behind the
	 * truck - 50 m that were never driven (ROADMAP 347, Codex's review of
	 * 01.10.2026). And if more than one node fits, nothing here tells which one
	 * the truck took, so neither is used. Never on a ring, and never for a way
	 * left with no measured progress of its own: one fix is not a drive along a
	 * way, and the turn must not be what makes it one.
	 */
	private double[] turnNode(Junction junction, Fix first) {
		if (closed || first.closed) {
			return null;
		}
		if (progress <= EPSILON) {
			count("junction_on_unmeasured_way");
			return null;
		}
		double[] found = null;
		for (int index = 0; index < junction.fromWayMeasures.length; index++) {
			double left = junction.fromWayMeasures[index];
			double entered = junction.toWayMeasures[index];
			if (!Double.isFinite(left) || !Double.isFinite(entered)) {
				continue;
			}
			double ahead = forward ? left - lastMeasure : lastMeasure - left;
			double behind = first.forward ? first.measureMeters - entered : entered - first.measureMeters;
			if (ahead < 0 || behind < 0
					|| ahead > MAX_JUNCTION_GAP_METERS || behind > MAX_JUNCTION_GAP_METERS) {
				continue;
			}
			if (found != null) {
				count("junction_ambiguous");
				return null;
			}
			found = new double[] {left, entered};
		}
		if (found == null && junction.fromWayMeasures.length > 0) {
			count("junction_not_placed");
		}
		return found;
	}

	/**
	 * R1 across a change of way: the active way was driven up to the node the
	 * truck turned at - which can be anywhere along it, not only its end (Codex's
	 * review, 30.09: the server used to close the stretch to the way's end on a
	 * junction it could not place). The node comes from turnNode.
	 */
	private void reachTheJunction(double node) {
		double gap = forward ? node - lastMeasure : lastMeasure - node;
		if (gap > EPSILON) {
			progress += gap;
			lastMeasure = node;
			count("junction_reached");
		}
	}

	/** R1 across a change of way: the new way was driven from the node it was entered at. */
	private void fromTheJunction(Fix first, double node) {
		double gap = first.forward ? first.measureMeters - node : node - first.measureMeters;
		if (gap > EPSILON) {
			startMeasure = node;
			progress += gap;
			count("junction_entered");
		}
	}

	/** R2 across a change of way: the new way was driven from its start. */
	private void fromTheStart(Fix first) {
		double head = first.forward ? first.measureMeters : first.wayLengthMeters - first.measureMeters;
		if (head > 0) {
			startMeasure = first.forward ? 0 : first.wayLengthMeters;
			progress = head;
		}
	}

	/**
	 * Hands over the ways crossed without a fix. Their time is the moment GPS
	 * disappeared; nothing about how long each took was measured.
	 *
	 * @return whether the last thing handed over can be joined to
	 */
	private boolean bridge(List<Leg> legs, boolean chained) {
		for (Leg leg : legs) {
			if (leg == null || !(leg.lengthMeters > EPSILON)) {
				chained = false;
				continue;
			}
			List<Span> spans = new ArrayList<>(1);
			spans.add(new Span(0, leg.lengthMeters));
			count("passages_bridged");
			if (diagnostics != null) {
				diagnostics.event(lastFixSequence, "RCS2_PASSAGE_BRIDGED", "way=" + leg.wayId
						+ (leg.forward ? " F" : " R") + " metres=" + Math.round(leg.lengthMeters));
			}
			note(lastFixSequence, "BRIDGED", "way=" + leg.wayId + " " + (leg.forward ? "F" : "R")
					+ " metres=" + Math.round(leg.lengthMeters));
			sink.accept(new Passage(leg.wayId, leg.forward, spans, lastConfirmedTime,
					lastConfirmedTime, 0, leg.lengthMeters, lastFixSequence, lastFixSequence,
					0, 0, leg.attachment, chained, true));
			chained = true;
		}
		return chained;
	}

	private void start(Fix fix, boolean joins) {
		joinsPrevious = joins;
		forgetPositionsBefore(fix.timeMillis);
		count("passages_started");
		if (diagnostics != null) {
			diagnostics.event(fix.fixSequence, "RCS2_PASSAGE_START", "way=" + fix.wayId
					+ (fix.forward ? " F" : " R"));
			diagnostics.passageStarted(fix.fixSequence);
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
		forgetPositionsBefore(fix.timeMillis);
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
		if (progress > EPSILON && diagnostics != null) {
			diagnostics.passageCovered(firstFixSequence, lastFixSequence);
		}
	}

	/** @return whether a passage was handed over - only then can the next one join it */
	private boolean finish(long endTime) {
		if (!active) {
			return false;
		}
		active = false;
		if (progress <= EPSILON) {
			count("passages_discarded_under_min_progress");
			if (diagnostics != null) {
				diagnostics.event(lastFixSequence, "RCS2_PASSAGE_DISCARDED",
						"way=" + wayId + " progress=0");
				diagnostics.passageDiscarded(firstFixSequence, lastFixSequence);
			}
		}
		if (progress > EPSILON) {
			count("passages_emitted");
			if (diagnostics != null) {
				diagnostics.event(lastFixSequence, "RCS2_PASSAGE_EMIT", "way=" + wayId
						+ " metres=" + Math.round(progress));
				diagnostics.passageCovered(firstFixSequence, lastFixSequence);
			}
			sink.accept(new Passage(wayId, forward, buildSpans(), startTime, endTime,
					fixCount, progress, firstFixSequence, lastFixSequence,
					maximumDistanceMeters, maximumHeadingDifferenceDegrees, attachment,
					joinsPrevious, false));
		}
		boolean emitted = progress > EPSILON;
		progress = 0;
		fixCount = 0;
		return emitted;
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
