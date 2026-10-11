package net.osmand.plus.roadcrew.tacho;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoRemaining.Result;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Galin, 11.10.2026: "колко още мога да карам ... и друга такава информация която трудно се
 * помни за всяка седмица". Forward from the card's last minute, by the same reading of
 * 561/2006 as RoadCrewTachoViolations. Synthetic timelines in UTC minutes.
 */
public class RemainingTest {
	static int cases;

	static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		cases++;
	}

	static void equal(long actual, long expected, String what) {
		check(actual == expected, what + ": expected " + expected + ", got " + actual);
	}

	static long at(String utc) {
		return LocalDateTime.parse(utc).toEpochSecond(ZoneOffset.UTC) / 60;
	}

	static int h(int hours, int minutes) {
		return hours * 60 + minutes;
	}

	static final class TL {
		long t;
		final List<Segment> segs = new ArrayList<>();

		TL(long start) {
			t = start;
		}

		TL add(Kind kind, int minutes) {
			segs.add(new Segment(t, t + minutes, kind, true, true));
			t += minutes;
			return this;
		}

		TL drive(int m) {
			return add(Kind.DRIVING, m);
		}

		TL rest(int m) {
			return add(Kind.REST, m);
		}

		Result remaining() {
			return RoadCrewTachoRemaining.of(new Card(segs, List.of(), true, true, Map.of()));
		}
	}

	/** Monday 03.08.2026 00:00 UTC. */
	static final long MON = at("2026-08-03T00:00");

	/** Saturday 00:00 to Monday 06:00: a regular weekly rest of 54 h. */
	static TL afterWeekend() {
		return new TL(MON - h(48, 0)).rest(h(54, 0));
	}

	public static void main(String[] args) {
		// An ordinary Monday: 4:30, the break, then 2:00 more.
		Result a = afterWeekend().drive(270).rest(45).drive(120).remaining();
		equal(a.at, MON + h(13, 15), "the card's last minute is the moment counted from");
		equal(a.drivenSinceBreak, 120, "driven since the 45-minute break");
		equal(a.untilBreak, 150, "left before the next break");
		check(!a.breakPartTaken, "no 15-minute part taken");
		equal(a.drivenToday, 390, "driven today");
		equal(a.todayLeft, 150, "left of the 9 hours");
		equal(a.todayLeftExtended, 210, "left of a 10-hour day");
		equal(a.extensionsLeft, 2, "both 10-hour days left this week");
		equal(a.drivenWeek, 390, "driven this week");
		equal(a.weekLeft, h(56, 0) - 390, "left of 56 h");
		equal(a.twoWeeksLeft, h(90, 0) - 390, "left of 90 h over two weeks");
		equal(a.reducedRestsLeft, 3, "three reduced daily rests after a weekly rest");
		equal(a.dailyRestBy, MON + h(19, 0), "an 11-hour rest must start 13 h after the last one ended");
		equal(a.reducedDailyRestBy, MON + h(21, 0), "a 9-hour rest 15 h after");
		equal(a.weeklyRestBy, MON + h(6, 0) + 6 * 1440, "the weekly rest at most six 24-hour periods after");
		equal(a.restingSince, -1, "driving, not resting");

		// Both 10-hour days used, one reduced rest, and 15 minutes of a split break taken.
		Result b = afterWeekend()
				.drive(270).rest(45).drive(270).rest(45).drive(60).rest(540)
				.drive(270).rest(45).drive(270).rest(45).drive(60).rest(660)
				.drive(180).rest(15).drive(60).remaining();
		equal(b.extensionsLeft, 0, "Monday and Tuesday took the two 10-hour days");
		equal(b.drivenToday, 240, "driven today");
		equal(b.todayLeft, 300, "left of 9 hours");
		equal(b.todayLeftExtended, -1, "no 10-hour day left");
		equal(b.drivenSinceBreak, 240, "a 15-minute part does not end the 4:30");
		equal(b.untilBreak, 30, "left before the break");
		check(b.breakPartTaken, "the 15 minutes are the first part: 30 more complete the break");
		equal(b.drivenWeek, 1440, "driven this week");
		equal(b.reducedRestsLeft, 2, "Monday night's 9 hours were one reduced rest");
		equal(b.dailyRestBy, MON + h(48 + 14, 0), "11 h from Wednesday 01:00");
		equal(b.reducedDailyRestBy, MON + h(48 + 16, 0), "9 h from Wednesday 01:00");

		// Resting at the end: the day is over, the next one has not started.
		Result c = afterWeekend().drive(270).rest(45).drive(200).rest(480).remaining();
		equal(c.restingSince, MON + h(14, 35), "resting since the last drive");
		equal(c.drivenToday, 0, "a new day after the rest");
		equal(c.untilBreak, 270, "a full 4:30 after the rest");
		equal(c.dailyRestBy, MON + h(19, 0), "the rest being taken belongs to the day that started Monday 06:00");

		// Two weeks: 50 h last week, 38 h this one - the 90 h binds before the 56 h.
		TL d = new TL(MON - 7 * 1440).rest(h(6, 0));
		for (int day = 0; day < 5; day++) {
			d.drive(600).rest(840);
		}
		d.rest((int) (MON + h(6, 0) - d.t));
		d.drive(600).rest(840).drive(600).rest(840).drive(540).rest(900).drive(540);
		Result dr = d.remaining();
		equal(dr.drivenWeek, h(38, 0), "driven this week");
		equal(dr.weekLeft, h(18, 0), "left of 56 h");
		equal(dr.drivenTwoWeeks, h(88, 0), "driven over two weeks");
		equal(dr.twoWeeksLeft, h(2, 0), "left of 90 h");

		// A card with no weekly or daily rest on it: unknown, not invented.
		Result e = new TL(MON + h(6, 0)).drive(240).remaining();
		equal(e.weeklyRestBy, -1, "no weekly rest on the card");
		equal(e.reducedRestsLeft, -1, "reduced rests counted only after a weekly rest");
		equal(e.dailyRestBy, -1, "no daily rest on the card");

		// Past 9 h with a 10-hour day left: today is that day.
		Result f = afterWeekend().drive(270).rest(45).drive(270).rest(45).drive(30).remaining();
		equal(f.extensionsLeft, 1, "today takes one of the two");
		equal(f.todayLeft, 30, "left of today's 10 hours");
		equal(f.todayLeftExtended, 30, "the same");

		System.out.println(cases + " remaining-time checks passed");
	}
}
