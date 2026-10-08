package net.osmand.plus.roadcrew.tacho;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Violations of Regulation (EC) 561/2006 in a card file's activities, each with its category
 * from Regulation (EU) 2016/403 Annex I (read from EUR-Lex, ROADMAP 380). Plain Java -
 * tools/tests/roadcrew-tacho-violations.test.mjs.
 * <p>
 * Galin, 09.10.2026: checked against Tacho Manager on his card. Like Tacho Manager, card-out
 * time counts as rest and 24-hour periods without a daily rest in a row are one violation.
 * Unlike it, the category is the regulation's: Tacho Manager calls 8:56 of a reduced rest
 * "very serious", 2016/403 puts 8 h-9 h below its serious band - minor here.
 * <p>
 * Not checked yet: multi-manning (8.5), the two-consecutive-weeks count of weekly rests and
 * their compensation (8.6, 8.7), ferry and train (9). Nothing is flagged for a period that the
 * file cuts off at either end; all rules run in UTC minutes, the week is Monday 00:00 UTC.
 */
public final class RoadCrewTachoViolations {

	public enum Rule {
		/** Article 6(1): 9 h a day, 10 h twice a week. */
		DAILY_DRIVING,
		/** Article 6(2): 56 h a week. */
		WEEKLY_DRIVING,
		/** Article 6(3): 90 h in two consecutive weeks. */
		FORTNIGHT_DRIVING,
		/** Article 7: a break of 45 min (or 15 + 30) after 4:30 of driving. */
		BREAK,
		/** Article 8(2): a daily rest within each 24 hours after the previous rest. */
		DAILY_REST,
		/** Article 8(6): a weekly rest no later than six 24-hour periods after the previous one. */
		WEEKLY_REST_LATE
	}

	/** Regulation 2016/403 Annex I: SI, VSI, MSI; below the serious bands a minor infringement. */
	public enum Severity { MINOR, SERIOUS, VERY_SERIOUS, MOST_SERIOUS }

	public static final class Violation {
		public final Rule rule;
		public final Severity severity;
		/** Epoch minutes, UTC. */
		public final long from;
		public final long to;
		/** Minutes: driving, rest obtained, or for a late weekly rest how late. */
		public final int measured;
		public final int limit;
		/** Daily rest: how many 24-hour periods in a row; 1 elsewhere. */
		public final int periods;
		/** Daily rest: a split 3 h + 9 h was attempted. */
		public final boolean split;

		Violation(Rule rule, Severity severity, long from, long to, int measured, int limit, int periods, boolean split) {
			this.rule = rule;
			this.severity = severity;
			this.from = from;
			this.to = to;
			this.measured = measured;
			this.limit = limit;
			this.periods = periods;
			this.split = split;
		}

		@Override
		public String toString() {
			return rule + " " + severity + " " + from + ".." + to + " measured " + measured + " limit " + limit
					+ (periods > 1 ? " x" + periods : "") + (split ? " split" : "");
		}
	}

	private static final int DRIVE = 0;
	private static final int WORK = 1;
	private static final int REST = 2;
	private static final long FIRST_MONDAY = 4 * 1440;  // 05.01.1970 00:00 UTC, in epoch minutes
	private static final int WEEK = 7 * 1440;

	private static final class Piece {
		final long from;
		final long to;
		final int type;

		Piece(long from, long to, int type) {
			this.from = from;
			this.to = to;
			this.type = type;
		}

		int length() {
			return (int) (to - from);
		}
	}

	private RoadCrewTachoViolations() {
	}

	public static List<Violation> check(Card card) {
		List<Piece> pieces = pieces(card.segments);
		List<Violation> out = new ArrayList<>();
		if (pieces.isEmpty()) {
			return out;
		}
		long dataEnd = pieces.get(pieces.size() - 1).to;
		breaks(pieces, out);
		dailyDriving(pieces, out);
		weeklyDriving(pieces, out);
		dailyRest(pieces, dataEnd, out);
		weeklyRestLate(pieces, dataEnd, out);
		out.sort((a, b) -> Long.compare(a.from, b.from));
		return Collections.unmodifiableList(out);
	}

	/** Driving, other work (work, availability) and rest (rest, card out unknown), merged. */
	private static List<Piece> pieces(List<Segment> segments) {
		List<Piece> out = new ArrayList<>();
		for (Segment s : segments) {
			int type = !s.known || s.kind == Kind.REST ? REST : s.kind == Kind.DRIVING ? DRIVE : WORK;
			if (!out.isEmpty() && out.get(out.size() - 1).to < s.from) {
				add(out, new Piece(out.get(out.size() - 1).to, s.from, REST));
			}
			add(out, new Piece(s.from, s.to, type));
		}
		return out;
	}

	private static void add(List<Piece> out, Piece p) {
		if (!out.isEmpty()) {
			Piece last = out.get(out.size() - 1);
			if (last.type == p.type && last.to == p.from) {
				out.set(out.size() - 1, new Piece(last.from, p.to, p.type));
				return;
			}
		}
		out.add(p);
	}

	// ---- Article 7: the break after 4:30 of driving --------------------------------------------

	private static void breaks(List<Piece> pieces, List<Violation> out) {
		int driven = 0;
		long start = -1;
		long lastDrive = -1;
		boolean firstPart = false;
		for (Piece p : pieces) {
			if (p.type == DRIVE) {
				if (driven == 0) {
					start = p.from;
				}
				driven += p.length();
				lastDrive = p.to;
			} else if (p.type == REST) {
				boolean qualifies = p.length() >= 45 || (firstPart && p.length() >= 30);
				if (qualifies) {
					if (driven > 270) {
						out.add(new Violation(Rule.BREAK, breakSeverity(driven), start, p.from, driven, 270, 1, false));
					}
					driven = 0;
					firstPart = false;
				} else if (p.length() >= 15) {
					firstPart = true;
				}
			}
		}
		if (driven > 270) {
			out.add(new Violation(Rule.BREAK, breakSeverity(driven), start, lastDrive, driven, 270, 1, false));
		}
	}

	private static Severity breakSeverity(int driven) {
		return driven < 300 ? Severity.MINOR : driven < 360 ? Severity.SERIOUS : Severity.VERY_SERIOUS;
	}

	// ---- Article 6(1): daily driving, between rests of at least 7 h ------------------------------

	private static void dailyDriving(List<Piece> pieces, List<Violation> out) {
		Map<Long, Integer> extensions = new HashMap<>();
		long start = -1;
		long end = -1;
		int driven = 0;
		boolean hadBreak = false;
		for (int i = 0; i <= pieces.size(); i++) {
			Piece p = i < pieces.size() ? pieces.get(i) : null;
			boolean dailyRest = p == null || (p.type == REST && p.length() >= 420);
			if (dailyRest) {
				if (driven > 540) {
					long week = Math.floorDiv(start - FIRST_MONDAY, WEEK);
					int used = extensions.getOrDefault(week, 0);
					boolean extension = used < 2;
					if (extension) {
						extensions.put(week, used + 1);
					}
					if (!extension || driven > 600) {
						out.add(new Violation(Rule.DAILY_DRIVING, dailySeverity(driven, extension, hadBreak), start, end,
								driven, extension ? 600 : 540, 1, false));
					}
				}
				start = -1;
				driven = 0;
				hadBreak = false;
				continue;
			}
			if (start < 0) {
				start = p.from;
			}
			end = p.to;
			if (p.type == DRIVE) {
				driven += p.length();
			} else if (p.type == REST && p.length() >= 45) {
				hadBreak = true;
			}
		}
	}

	private static Severity dailySeverity(int driven, boolean extension, boolean hadBreak) {
		int limit = extension ? 600 : 540;
		if (driven >= limit * 3 / 2 && !hadBreak) {
			return Severity.MOST_SERIOUS;
		}
		return driven < limit + 60 ? Severity.MINOR : driven < limit + 120 ? Severity.SERIOUS : Severity.VERY_SERIOUS;
	}

	// ---- Article 6(2) and 6(3): the fixed week and two consecutive weeks ---------------------------

	private static void weeklyDriving(List<Piece> pieces, List<Violation> out) {
		TreeMap<Long, Integer> weeks = new TreeMap<>();
		for (Piece p : pieces) {
			if (p.type != DRIVE) {
				continue;
			}
			long from = p.from;
			while (from < p.to) {
				long week = Math.floorDiv(from - FIRST_MONDAY, WEEK);
				long weekEnd = FIRST_MONDAY + (week + 1) * WEEK;
				long to = Math.min(p.to, weekEnd);
				weeks.merge(week, (int) (to - from), Integer::sum);
				from = to;
			}
		}
		for (Map.Entry<Long, Integer> e : weeks.entrySet()) {
			long week = e.getKey();
			int driven = e.getValue();
			long weekStart = FIRST_MONDAY + week * WEEK;
			if (driven > 56 * 60) {
				Severity s = driven < 60 * 60 ? Severity.MINOR : driven < 65 * 60 ? Severity.SERIOUS
						: driven < 70 * 60 ? Severity.VERY_SERIOUS : Severity.MOST_SERIOUS;
				out.add(new Violation(Rule.WEEKLY_DRIVING, s, weekStart, weekStart + WEEK, driven, 56 * 60, 1, false));
			}
			int two = driven + weeks.getOrDefault(week + 1, 0);
			if (two > 90 * 60) {
				Severity s = two < 100 * 60 ? Severity.MINOR : two < 105 * 60 ? Severity.SERIOUS
						: two < 112 * 60 + 30 ? Severity.VERY_SERIOUS : Severity.MOST_SERIOUS;
				out.add(new Violation(Rule.FORTNIGHT_DRIVING, s, weekStart, weekStart + 2 * WEEK, two, 90 * 60, 1, false));
			}
		}
	}

	// ---- Article 8(2): a daily rest within each 24 hours ---------------------------------------------

	private static void dailyRest(List<Piece> pieces, long dataEnd, List<Violation> out) {
		List<Piece> rests = new ArrayList<>();
		for (Piece p : pieces) {
			if (p.type == REST) {
				rests.add(p);
			}
		}
		long t0 = -1;
		int reducedLeft = 3;
		for (Piece r : rests) {
			if (r.length() >= 420 && r.to < dataEnd) {
				t0 = r.to;
				break;
			}
		}
		long gapFrom = -1;
		int gapMeasured = 0;
		int gapLimit = 0;
		int gapPeriods = 0;
		boolean gapSplit = false;
		while (t0 >= 0 && t0 + 1440 <= dataEnd) {
			long w1 = t0 + 1440;
			Piece best = null;
			int portion = 0;
			for (Piece r : rests) {
				if (r.to > t0 && r.from < w1) {
					int inside = (int) (Math.min(r.to, w1) - Math.max(r.from, t0));
					if (inside > portion) {
						portion = inside;
						best = r;
					}
				}
			}
			boolean splitFirst = false;
			if (best != null) {
				for (Piece r : rests) {
					if (r != best && r.from >= t0 && r.to <= best.from && r.length() >= 180) {
						splitFirst = true;
						break;
					}
				}
			}
			boolean ok;
			if (portion >= 660) {
				ok = true;
			} else if (splitFirst && portion >= 540) {
				ok = true;
			} else if (portion >= 540 && reducedLeft > 0) {
				ok = true;
				reducedLeft--;
			} else {
				ok = false;
			}
			if (ok) {
				if (gapFrom >= 0) {
					int longest = Math.max(gapMeasured, longestRest(rests, gapFrom, best.from));
					out.add(new Violation(Rule.DAILY_REST, restSeverity(longest, gapLimit, gapSplit), gapFrom, best.from,
							longest, gapLimit, gapPeriods, gapSplit));
					gapFrom = -1;
				}
				t0 = best.to;
				if (best.length() >= 1440) {
					reducedLeft = 3;
				}
				continue;
			}
			if (gapFrom < 0) {
				gapFrom = t0;
				gapMeasured = portion;
				gapLimit = splitFirst || reducedLeft > 0 ? 540 : 660;
				gapSplit = splitFirst;
				gapPeriods = 1;
			} else {
				gapMeasured = Math.max(gapMeasured, portion);
				gapPeriods++;
			}
			if (best != null && best.length() >= 420) {
				int longest = Math.max(gapMeasured, longestRest(rests, gapFrom, best.from));
				out.add(new Violation(Rule.DAILY_REST, restSeverity(longest, gapLimit, gapSplit), gapFrom, best.from,
						longest, gapLimit, gapPeriods, gapSplit));
				gapFrom = -1;
				t0 = best.to;
				if (best.length() >= 1440) {
					reducedLeft = 3;
				}
			} else {
				t0 = w1;
			}
		}
		if (gapFrom >= 0) {
			out.add(new Violation(Rule.DAILY_REST, restSeverity(gapMeasured, gapLimit, gapSplit), gapFrom, t0,
					gapMeasured, gapLimit, gapPeriods, gapSplit));
		}
	}

	/** The longest rest between two instants - Tacho Manager's "longest rest in gap". */
	private static int longestRest(List<Piece> rests, long from, long to) {
		int longest = 0;
		for (Piece r : rests) {
			if (r.to > from && r.from < to) {
				longest = Math.max(longest, (int) (Math.min(r.to, to) - Math.max(r.from, from)));
			}
		}
		return longest;
	}

	/** 9 h (reduced or split): SI 7 h-8 h, VSI below 7 h; 11 h: SI 8:30-10 h, VSI below 8:30. */
	private static Severity restSeverity(int rest, int limit, boolean split) {
		if (limit == 540) {
			return rest >= 480 ? Severity.MINOR : rest >= 420 ? Severity.SERIOUS : Severity.VERY_SERIOUS;
		}
		return rest >= 600 ? Severity.MINOR : rest >= 510 ? Severity.SERIOUS : Severity.VERY_SERIOUS;
	}

	// ---- Article 8(6): the weekly rest no later than six 24-hour periods after the previous one ------

	private static void weeklyRestLate(List<Piece> pieces, long dataEnd, List<Violation> out) {
		Piece previous = null;
		for (Piece p : pieces) {
			if (p.type != REST || p.length() < 1440) {
				continue;
			}
			if (previous != null) {
				late(previous.to + 6 * 1440, p.from, out);
			}
			previous = p;
		}
		if (previous != null && previous.to < dataEnd) {
			late(previous.to + 6 * 1440, dataEnd, out);
		}
	}

	private static void late(long deadline, long started, List<Violation> out) {
		if (started <= deadline) {
			return;
		}
		int late = (int) (started - deadline);
		Severity s = late < 180 ? Severity.MINOR : late < 720 ? Severity.SERIOUS : Severity.VERY_SERIOUS;
		out.add(new Violation(Rule.WEEKLY_REST_LATE, s, deadline, started, late, 0, 1, false));
	}
}
