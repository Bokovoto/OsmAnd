package net.osmand.plus.roadcrew.tacho;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Piece;

import java.util.List;

import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.DRIVE;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.FIRST_MONDAY;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.REST;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.WEEK;

/**
 * How much a driver may still drive, counted forward from the card's last minute (the
 * download) by the same reading of Regulation (EC) 561/2006 as RoadCrewTachoViolations:
 * card-out time is rest, a rest of at least 7 h ends a day, the week starts Monday 00:00 UTC.
 * Plain Java - tools/tests/roadcrew-tacho-remaining.test.mjs.
 * <p>
 * Galin, 11.10.2026: "колко още мога да карам като дни колко мога да карат за деня и друга
 * такава информация която трудно се помни за всяка седмица". Nothing here guesses what
 * happened after the download; what the card cannot tell is -1.
 */
public final class RoadCrewTachoRemaining {

	private static final int BREAK_AFTER = 270;
	private static final int DAY = 540;
	private static final int LONG_DAY = 600;
	private static final int DAY_ENDS = 420;
	private static final int REGULAR_DAILY_REST = 660;
	private static final int REDUCED_DAILY_REST = 540;
	private static final int SPLIT_FIRST_PART = 180;
	private static final int WEEKLY_REST = 1440;

	public static final class Result {
		/** Epoch minutes, UTC: the card's last minute. */
		public final long at;
		public final int drivenSinceBreak;
		public final int untilBreak;
		/** A first part of 15 minutes is taken: 30 more complete the break. */
		public final boolean breakPartTaken;
		public final int drivenToday;
		/** Left under today's limit: 9 h, or 10 h when today is already past 9 h on one of the two. */
		public final int todayLeft;
		/** Left on a 10-hour day; -1 when none is left this week. */
		public final int todayLeftExtended;
		public final int extensionsLeft;
		public final int drivenWeek;
		public final int weekLeft;
		public final int drivenTwoWeeks;
		public final int twoWeeksLeft;
		/** Of three between two weekly rests; -1 when the card shows no weekly rest. */
		public final int reducedRestsLeft;
		/** Latest start of an 11-hour daily rest; -1 when the card shows no daily rest. */
		public final long dailyRestBy;
		/** Latest start of a 9-hour one; -1 as above, or when none is left. */
		public final long reducedDailyRestBy;
		/** Latest start of the weekly rest; -1 when the card shows none. */
		public final long weeklyRestBy;
		/** Start of a rest of at least 7 h the card ends in; -1 when not in one. */
		public final long restingSince;

		Result(long at, int drivenSinceBreak, boolean breakPartTaken, int drivenToday, int todayLeft,
				int todayLeftExtended, int extensionsLeft, int drivenWeek, int drivenTwoWeeks, int reducedRestsLeft,
				long dailyRestBy, long reducedDailyRestBy, long weeklyRestBy, long restingSince) {
			this.at = at;
			this.drivenSinceBreak = drivenSinceBreak;
			this.untilBreak = Math.max(0, BREAK_AFTER - drivenSinceBreak);
			this.breakPartTaken = breakPartTaken;
			this.drivenToday = drivenToday;
			this.todayLeft = todayLeft;
			this.todayLeftExtended = todayLeftExtended;
			this.extensionsLeft = extensionsLeft;
			this.drivenWeek = drivenWeek;
			this.weekLeft = Math.max(0, 56 * 60 - drivenWeek);
			this.drivenTwoWeeks = drivenTwoWeeks;
			this.twoWeeksLeft = Math.max(0, 90 * 60 - drivenTwoWeeks);
			this.reducedRestsLeft = reducedRestsLeft;
			this.dailyRestBy = dailyRestBy;
			this.reducedDailyRestBy = reducedDailyRestBy;
			this.weeklyRestBy = weeklyRestBy;
			this.restingSince = restingSince;
		}
	}

	private RoadCrewTachoRemaining() {
	}

	/** Null when the card has no activity at all. */
	public static Result of(Card card) {
		List<Piece> pieces = RoadCrewTachoViolations.pieces(card.segments);
		if (pieces.isEmpty()) {
			return null;
		}
		Piece last = pieces.get(pieces.size() - 1);
		long at = last.to;
		// A rest the card ends in is still running: it may grow, so it closes nothing yet
		// except the day it began after.
		boolean resting = last.type == REST && last.length() >= DAY_ENDS;
		int closed = last.type == REST ? pieces.size() - 1 : pieces.size();

		// Article 7: driving since the last break of 45 minutes, or of 15 + 30.
		int sinceBreak = 0;
		boolean firstPart = false;
		for (Piece p : pieces) {
			if (p.type == DRIVE) {
				sinceBreak += p.length();
			} else if (p.type == REST) {
				if (p.length() >= 45 || (firstPart && p.length() >= 30)) {
					sinceBreak = 0;
					firstPart = false;
				} else if (p.length() >= 15) {
					firstPart = true;
				}
			}
		}

		// Article 6(1): days end at a rest of at least 7 h; two of a week may reach 10 h.
		int dayStart = 0;
		for (int i = 0; i < closed; i++) {
			if (pieces.get(i).type == REST && pieces.get(i).length() >= DAY_ENDS) {
				dayStart = i + 1;
			}
		}
		int drivenToday = 0;
		for (int i = dayStart; i < closed; i++) {
			if (pieces.get(i).type == DRIVE) {
				drivenToday += pieces.get(i).length();
			}
		}
		if (resting) {
			drivenToday = 0;
		}
		long todayStartsAt = resting || dayStart >= pieces.size() ? at : pieces.get(dayStart).from;
		long todayWeek = Math.floorDiv(todayStartsAt - FIRST_MONDAY, WEEK);
		int extendedBefore = 0;
		int driven = 0;
		long periodStart = pieces.get(0).from;
		int todayFirst = resting ? pieces.size() : dayStart;
		for (int i = 0; i < todayFirst; i++) {
			Piece p = pieces.get(i);
			if (p.type == REST && p.length() >= DAY_ENDS) {
				if (driven > DAY && Math.floorDiv(periodStart - FIRST_MONDAY, WEEK) == todayWeek) {
					extendedBefore++;
				}
				driven = 0;
				periodStart = p.to;
			} else if (p.type == DRIVE) {
				driven += p.length();
			}
		}
		int todayLeft;
		int todayLeftExtended;
		int extensionsLeft;
		if (drivenToday > DAY) {
			boolean allowed = extendedBefore < 2;
			extensionsLeft = Math.max(0, 2 - extendedBefore - 1);
			todayLeft = allowed ? Math.max(0, LONG_DAY - drivenToday) : 0;
			todayLeftExtended = allowed ? todayLeft : -1;
		} else {
			extensionsLeft = Math.max(0, 2 - extendedBefore);
			todayLeft = DAY - drivenToday;
			todayLeftExtended = extensionsLeft > 0 ? LONG_DAY - drivenToday : -1;
		}

		// Article 6(2) and 6(3): the fixed week and the one before it.
		long weekStart = FIRST_MONDAY + Math.floorDiv(at - FIRST_MONDAY, WEEK) * WEEK;
		int drivenWeek = drivenBetween(pieces, weekStart, at);
		int drivenTwoWeeks = drivenWeek + drivenBetween(pieces, weekStart - WEEK, weekStart);

		// Article 8: the last finished weekly rest, and the daily rests since it.
		int weekly = -1;
		for (int i = 0; i < closed; i++) {
			if (pieces.get(i).type == REST && pieces.get(i).length() >= WEEKLY_REST) {
				weekly = i;
			}
		}
		int reducedRestsLeft = -1;
		long weeklyRestBy = -1;
		if (weekly >= 0) {
			weeklyRestBy = pieces.get(weekly).to + 6 * 1440;
			int reduced = 0;
			boolean splitPart = false;
			for (int i = weekly + 1; i < closed; i++) {
				Piece p = pieces.get(i);
				if (p.type != REST) {
					continue;
				}
				if (p.length() >= DAY_ENDS) {
					if (p.length() >= REDUCED_DAILY_REST && p.length() < REGULAR_DAILY_REST && !splitPart) {
						reduced++;
					}
					splitPart = false;
				} else if (p.length() >= SPLIT_FIRST_PART) {
					splitPart = true;
				}
			}
			reducedRestsLeft = Math.max(0, 3 - reduced);
		}
		long dailyRestBy = -1;
		long reducedDailyRestBy = -1;
		long t0 = -1;
		for (int i = 0; i < closed; i++) {
			if (pieces.get(i).type == REST && pieces.get(i).length() >= DAY_ENDS) {
				t0 = pieces.get(i).to;
			}
		}
		if (t0 >= 0) {
			dailyRestBy = t0 + 1440 - REGULAR_DAILY_REST;
			if (reducedRestsLeft != 0) {
				reducedDailyRestBy = t0 + 1440 - REDUCED_DAILY_REST;
			}
		}
		return new Result(at, resting ? 0 : sinceBreak, !resting && firstPart, drivenToday, todayLeft, todayLeftExtended,
				extensionsLeft, drivenWeek, drivenTwoWeeks, reducedRestsLeft, dailyRestBy, reducedDailyRestBy,
				weeklyRestBy, resting ? last.from : -1);
	}

	private static int drivenBetween(List<Piece> pieces, long from, long to) {
		int driven = 0;
		for (Piece p : pieces) {
			if (p.type == DRIVE && p.to > from && p.from < to) {
				driven += (int) (Math.min(p.to, to) - Math.max(p.from, from));
			}
		}
		return driven;
	}
}
