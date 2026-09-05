package net.osmand.router;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the directed pipeline actually did, so a fault can be proven rather than
 * guessed at (ROADMAP section 180).
 *
 * The first real drives showed the new scheme seeing an eighth of the road the
 * old one saw. Totals alone would say "there were seventeen truncated loads"
 * without showing that those seventeen sit exactly on the gaps, so this keeps a
 * bounded trace of transitions as well - never every GPS fix, which would be
 * exactly the trace of a driver we have gone to such lengths not to keep.
 *
 * Everything here is bounded and cheap. It is a diagnostic build's instrument,
 * not a permanent feature, and it must never be able to affect the drive.
 */
public final class RoadCrewDiagnostics {

	/** Enough to cover a course's transitions; far too few to reconstruct a route. */
	public static final int MAX_EVENTS = 200;
	/** Runs of one matched way; a long drive folds into a few hundred. */
	public static final int MAX_BASELINE_RUNS = 400;

	private final Map<String, Integer> counters = new LinkedHashMap<>();
	private final List<String> events = new ArrayList<>();
	/** [firstFix, lastFix, wayId, forward] for every stretch the matcher chose. */
	private final List<long[]> baseline = new ArrayList<>();
	private int droppedEvents;
	private int droppedRuns;
	/**
	 * Which snapshot this is. The counters are cumulative and a copy rides on
	 * every chunk, so an analyser must take the newest one and never add them
	 * together. Left to be inferred from the object listing it would take
	 * whichever happened to be read last, which is not the same thing.
	 */
	private int snapshotSequence;

	public synchronized void count(String name) {
		count(name, 1);
	}

	public synchronized void count(String name, int amount) {
		if (name == null || name.isEmpty()) {
			return;
		}
		Integer previous = counters.get(name);
		counters.put(name, (previous == null ? 0 : previous) + amount);
	}

	/**
	 * One transition worth seeing: a truncated load, a reset, a passage opening
	 * or closing, a course ending. Not a fix.
	 */
	public synchronized void event(long fixSequence, String name, String detail) {
		if (events.size() >= MAX_EVENTS) {
			droppedEvents++;
			return;
		}
		events.add(fixSequence + " " + name + (detail == null || detail.isEmpty() ? "" : " " + detail));
	}

	/**
	 * The one matcher both branches are fed from - the only honest baseline for
	 * either of them. Neither the old scheme nor the new one is a fair judge of
	 * the other, but both can be measured against what the matcher resolved.
	 */
	public synchronized void matched(long fixSequence, long wayId, boolean forward) {
		// Counted separately from the runs, and never bounded. The runs can be
		// truncated on a long drive; the denominator of every recall figure must
		// not be, or a shortened baseline would flatter both branches at once.
		count("matched_fixes");
		long[] last = baseline.isEmpty() ? null : baseline.get(baseline.size() - 1);
		// Contiguous only. A run that jumped over unmatched fixes would cover
		// more positions than were ever matched, and recall computed against the
		// exact count then exceeds 100% - as rcs1 did, at 102.8%.
		if (last != null && last[2] == wayId && last[3] == (forward ? 1 : 0)
				&& fixSequence == last[1] + 1) {
			last[1] = fixSequence;
			return;
		}
		if (baseline.size() >= MAX_BASELINE_RUNS) {
			droppedRuns++;
			return;
		}
		baseline.add(new long[]{fixSequence, fixSequence, wayId, forward ? 1 : 0});
	}

	public synchronized int counter(String name) {
		Integer value = counters.get(name);
		return value == null ? 0 : value;
	}

	public synchronized int eventCount() {
		return events.size();
	}

	public synchronized int baselineRunCount() {
		return baseline.size();
	}

	public synchronized void reset() {
		counters.clear();
		events.clear();
		baseline.clear();
		droppedEvents = 0;
		droppedRuns = 0;
		snapshotSequence = 0;
	}

	/**
	 * Compact JSON, built by hand so this class stays usable from the shared
	 * library where no JSON library is guaranteed.
	 */
	public synchronized String toJson() {
		snapshotSequence++;
		StringBuilder json = new StringBuilder(1024);
		json.append("{\"snapshotSequence\":").append(snapshotSequence).append(",\"counters\":{");
		boolean first = true;
		for (Map.Entry<String, Integer> entry : counters.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
		}
		json.append("},\"events\":[");
		for (int index = 0; index < events.size(); index++) {
			if (index > 0) {
				json.append(',');
			}
			json.append('"').append(escape(events.get(index))).append('"');
		}
		json.append("],\"baseline\":[");
		for (int index = 0; index < baseline.size(); index++) {
			long[] run = baseline.get(index);
			if (index > 0) {
				json.append(',');
			}
			json.append('[').append(run[0]).append(',').append(run[1]).append(',')
					.append(run[2]).append(',').append(run[3]).append(']');
		}
		// Stated outright rather than left to be inferred: an analyser must not
		// compute an exact-looking result from a trace it cannot see the end of.
		json.append("],\"eventTraceTruncated\":").append(droppedEvents > 0)
				.append(",\"eventTraceDroppedCount\":").append(droppedEvents)
				.append(",\"matcherBaselineTruncated\":").append(droppedRuns > 0)
				.append(",\"matcherBaselineDroppedCount\":").append(droppedRuns)
				.append('}');
		return json.toString();
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 8);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '"' || character == '\\') {
				escaped.append('\\').append(character);
			} else if (character < 0x20) {
				escaped.append(' ');
			} else {
				escaped.append(character);
			}
		}
		return escaped.toString();
	}
}
