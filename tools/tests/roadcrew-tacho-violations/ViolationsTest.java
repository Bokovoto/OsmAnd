package net.osmand.plus.roadcrew.tacho;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Rule;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Severity;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Violation;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ROADMAP 380: violations of Regulation 561/2006 with the categories of Regulation 2016/403,
 * Annex I. Synthetic timelines in UTC minutes; the last case repeats Galin's July record, where
 * Tacho Manager found the weekly rest 124:58 late.
 */
public class ViolationsTest {
	static int cases;

	static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
		cases++;
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

		TL work(int m) {
			return add(Kind.WORK, m);
		}

		TL out(int m) {
			segs.add(new Segment(t, t + m, Kind.REST, false, false));
			t += m;
			return this;
		}

		TL until(long minute, Kind kind) {
			return add(kind, (int) (minute - t));
		}

		/** A legal shift of {@code length}: 4:00 driving and 45-minute breaks up to 9 h, then other work. */
		TL shift(int length) {
			int left = length;
			int driven = 0;
			while (left > 0) {
				int d = Math.min(Math.min(240, 540 - driven), left);
				if (d > 0) {
					drive(d);
					driven += d;
					left -= d;
				}
				if (left <= 0) {
					break;
				}
				if (driven >= 540) {
					work(left);
					break;
				}
				int b = Math.min(45, left);
				rest(b);
				left -= b;
			}
			return this;
		}

		List<Violation> check() {
			return RoadCrewTachoViolations.check(new Card(segs, List.of(), true, true, Map.of()));
		}
	}

	static List<Violation> of(List<Violation> all, Rule rule) {
		List<Violation> out = new ArrayList<>();
		for (Violation v : all) {
			if (v.rule == rule) {
				out.add(v);
			}
		}
		return out;
	}

	/** Monday 03.08.2026 00:00 UTC, after a weekend's weekly rest. */
	static final long MON = at("2026-08-03T00:00");

	/** A weekly rest, then five days of 06:00 shifts with the given driving, a 45-minute break after 4:30 at most. */
	static TL week(int... driving) {
		TL tl = new TL(MON - h(48, 0)).rest(h(54, 0));
		for (int d : driving) {
			long dayStart = tl.t;
			int first = Math.min(d, 270);
			tl.drive(first);
			int left = d - first;
			while (left > 0) {
				tl.rest(45);
				int next = Math.min(left, 270);
				tl.drive(next);
				left -= next;
			}
			tl.until(dayStart + h(24, 0), Kind.REST);
		}
		return tl.rest(h(48, 0)).shift(h(2, 0));
	}

	public static void main(String[] args) {
		// 1. A clean week: nothing.
		check(week(h(8, 0), h(8, 0), h(8, 0), h(8, 0), h(8, 0)).check().isEmpty(), "a legal week has no violation");

		// 2. Article 7, the break after 4:30: MI below 5 h, SI 5-6 h, VSI from 6 h; 4:30 itself is allowed.
		int[][] breakCases = {{270, -1}, {271, 0}, {299, 0}, {300, 1}, {359, 1}, {360, 2}};
		for (int[] c : breakCases) {
			TL tl = new TL(MON - h(48, 0)).rest(h(54, 0)).drive(c[0]).rest(45).drive(60).rest(h(13, 0)).shift(h(2, 0));
			List<Violation> v = of(tl.check(), Rule.BREAK);
			if (c[1] < 0) {
				check(v.isEmpty(), "4:30 then a break is allowed");
			} else {
				check(v.size() == 1 && v.get(0).measured == c[0] && v.get(0).limit == 270
						&& v.get(0).severity == Severity.values()[c[1]], "driving " + c[0] + " min before a break: " + v);
			}
		}
		// The break split 15 + 30 counts, 30 + 15 does not.
		TL split = new TL(MON - h(48, 0)).rest(h(54, 0)).drive(150).rest(15).drive(120).rest(30).drive(100).rest(h(13, 0)).shift(60);
		check(of(split.check(), Rule.BREAK).isEmpty(), "15 + 30 is a break");
		TL wrong = new TL(MON - h(48, 0)).rest(h(54, 0)).drive(150).rest(30).drive(120).rest(15).drive(100).rest(h(13, 0)).shift(60);
		List<Violation> w = of(wrong.check(), Rule.BREAK);
		check(w.size() == 1 && w.get(0).measured == 370, "30 + 15 is not: 6:10 without a break, " + w);

		// 3. Article 6(1), daily driving: two 10 h days a week are allowed, the third is not.
		check(of(week(h(9, 30), h(9, 30), h(8, 0), h(8, 0), h(8, 0)).check(), Rule.DAILY_DRIVING).isEmpty(),
				"two extended days are allowed");
		List<Violation> third = of(week(h(9, 30), h(9, 30), h(9, 30), h(8, 0), h(8, 0)).check(), Rule.DAILY_DRIVING);
		check(third.size() == 1 && third.get(0).measured == 570 && third.get(0).limit == 540
				&& third.get(0).severity == Severity.MINOR, "the third 9:30 is minor against 9 h: " + third);
		List<Violation> tenThird = of(week(h(9, 30), h(9, 30), h(10, 0), h(8, 0), h(8, 0)).check(), Rule.DAILY_DRIVING);
		check(tenThird.size() == 1 && tenThird.get(0).severity == Severity.SERIOUS, "10 h with no extension left is serious");
		List<Violation> over = of(week(h(10, 30), h(8, 0), h(8, 0), h(8, 0), h(8, 0)).check(), Rule.DAILY_DRIVING);
		check(over.size() == 1 && over.get(0).limit == 600 && over.get(0).severity == Severity.MINOR,
				"10:30 with an extension is minor against 10 h: " + over);
		List<Violation> eleven = of(week(h(11, 0), h(8, 0), h(8, 0), h(8, 0), h(8, 0)).check(), Rule.DAILY_DRIVING);
		check(eleven.size() == 1 && eleven.get(0).severity == Severity.SERIOUS, "11 h with an extension is serious");
		List<Violation> twelve = of(week(h(12, 0), h(8, 0), h(8, 0), h(8, 0), h(8, 0)).check(), Rule.DAILY_DRIVING);
		check(twelve.size() == 1 && twelve.get(0).severity == Severity.VERY_SERIOUS, "12 h with an extension is very serious");

		// 4. Article 6(2) and 6(3): the fixed week Monday-Sunday UTC, and two consecutive weeks.
		TL heavy = new TL(MON - h(48, 0)).rest(h(54, 0));
		for (int d = 0; d < 6; d++) {
			long dayStart = heavy.t;
			heavy.drive(270).rest(45).drive(270).rest(45).drive(30);
			heavy.until(dayStart + h(24, 0), Kind.REST);
		}
		// 6 days of 9:30: 57 h in one week.
		List<Violation> weekly = of(heavy.rest(h(30, 0)).shift(60).check(), Rule.WEEKLY_DRIVING);
		check(weekly.size() == 1 && weekly.get(0).measured == h(57, 0) && weekly.get(0).limit == h(56, 0)
				&& weekly.get(0).severity == Severity.MINOR, "57 h in a week is minor: " + weekly);
		TL two = new TL(MON - h(48, 0)).rest(h(54, 0));
		for (int d = 0; d < 12; d++) {
			long dayStart = two.t;
			two.drive(270).rest(45).drive(210);
			two.until(dayStart + h(24, 0), Kind.REST);
			if (d == 5) {
				two.rest(h(24, 0));
			}
		}
		List<Violation> fortnight = of(two.check(), Rule.FORTNIGHT_DRIVING);
		check(fortnight.size() == 1 && fortnight.get(0).measured > h(90, 0) && fortnight.get(0).severity == Severity.MINOR,
				"8 h x 12 days over two weeks is above 90 h: " + fortnight);

		// 5. Article 8(2), daily rest: 9 h is allowed three times between weekly rests, then 11 h again.
		TL reduced = new TL(MON - h(48, 0)).rest(h(54, 0));
		for (int d = 0; d < 4; d++) {
			reduced.shift(h(14, 30)).rest(h(9, 30));
		}
		reduced.shift(h(12, 0)).rest(h(12, 0)).shift(h(2, 0));
		List<Violation> r = of(reduced.check(), Rule.DAILY_REST);
		check(r.size() == 1 && r.get(0).measured == h(9, 30) && r.get(0).limit == h(11, 0)
				&& r.get(0).severity == Severity.SERIOUS, "the fourth 9:30 rest is serious against 11 h: " + r);
		TL shortRest = new TL(MON - h(48, 0)).rest(h(54, 0)).shift(h(16, 0)).rest(h(8, 0)).shift(h(12, 0)).rest(h(12, 0)).shift(60);
		List<Violation> sr = of(shortRest.check(), Rule.DAILY_REST);
		check(sr.size() == 1 && sr.get(0).measured == h(8, 0) && sr.get(0).limit == h(9, 0)
				&& sr.get(0).severity == Severity.MINOR, "8 h with a reduction left is minor against 9 h: " + sr);
		TL splitRest = new TL(MON - h(48, 0)).rest(h(54, 0)).shift(h(6, 0)).rest(h(3, 0)).shift(h(6, 0)).rest(h(9, 0))
				.shift(h(10, 0)).rest(h(12, 0)).shift(60);
		check(of(splitRest.check(), Rule.DAILY_REST).isEmpty(), "3 h + 9 h is a daily rest");
		TL splitShort = new TL(MON - h(48, 0)).rest(h(54, 0)).shift(h(6, 0)).rest(h(3, 0)).shift(h(7, 0)).rest(h(8, 0))
				.shift(h(10, 0)).rest(h(12, 0)).shift(60);
		List<Violation> ss = of(splitShort.check(), Rule.DAILY_REST);
		check(ss.size() == 1 && ss.get(0).measured == h(8, 0) && ss.get(0).severity == Severity.MINOR,
				"3 h + 8 h is a minor split: " + ss);

		// 6. Card out counts as rest: a weekend with the card out is a weekly rest.
		TL home = new TL(MON - h(48, 0)).rest(h(54, 0)).shift(h(10, 0)).rest(h(14, 0)).shift(h(10, 0)).out(h(50, 0)).shift(h(10, 0));
		check(home.check().isEmpty(), "a weekend with the card out is rest");

		// 7. The file's ends: a shift cut by the start or the end is not a violation.
		TL cut = new TL(MON).drive(h(4, 0)).rest(45).drive(h(4, 0)).rest(h(12, 0)).shift(h(10, 0));
		check(cut.check().isEmpty(), "nothing invented at the file's ends");

		// 8. Galin's July: weekly rest ended 07.07 05:44, the card on "other work" 10.07 05:27 - 13.07 08:56,
		// the next weekly rest from 18.07 10:42. Tacho Manager: weekly rest 124:58 late.
		TL july = new TL(at("2026-07-04T20:10")).until(at("2026-07-07T05:44"), Kind.REST);
		july.shift((int) (at("2026-07-07T14:08") - july.t)).until(at("2026-07-08T05:27"), Kind.REST);
		july.shift((int) (at("2026-07-08T12:36") - july.t)).until(at("2026-07-09T05:27"), Kind.REST);
		july.shift((int) (at("2026-07-09T18:44") - july.t)).until(at("2026-07-10T05:27"), Kind.REST);
		july.until(at("2026-07-13T08:56"), Kind.WORK).drive(120).rest(11).until(at("2026-07-13T13:02"), Kind.WORK)
				.until(at("2026-07-14T04:47"), Kind.REST);
		String[][] days = {{"2026-07-14T18:16", "2026-07-15T05:21"}, {"2026-07-15T16:23", "2026-07-16T05:29"},
				{"2026-07-16T18:10", "2026-07-17T05:57"}, {"2026-07-17T15:44", "2026-07-18T05:25"}};
		for (String[] d : days) {
			july.shift((int) (at(d[0]) - july.t)).until(at(d[1]), Kind.REST);
		}
		july.shift((int) (at("2026-07-18T10:42") - july.t)).until(at("2026-07-20T08:04"), Kind.REST)
				.shift((int) (at("2026-07-20T19:53") - july.t));
		List<Violation> jv = july.check();
		List<Violation> late = of(jv, Rule.WEEKLY_REST_LATE);
		check(late.size() == 1 && late.get(0).measured == h(124, 58) && late.get(0).from == at("2026-07-13T05:44")
				&& late.get(0).to == at("2026-07-18T10:42") && late.get(0).severity == Severity.VERY_SERIOUS,
				"weekly rest 124:58 late, as Tacho Manager, very serious by 2016/403: " + late);
		// Tacho Manager groups the 24-hour periods without a daily rest into one, up to the rest that ends them,
		// and measures the longest rest in that gap: 0:11 on Galin's card.
		List<Violation> daily = of(jv, Rule.DAILY_REST);
		check(daily.size() == 1 && daily.get(0).from == at("2026-07-10T05:27") && daily.get(0).to == at("2026-07-13T13:02")
				&& daily.get(0).periods == 3 && daily.get(0).measured == 11 && daily.get(0).limit == h(9, 0)
				&& daily.get(0).severity == Severity.VERY_SERIOUS, "three 24-hour periods without a daily rest, as one: " + daily);
		check(jv.size() == 2, "nothing else on that record: " + jv);

		// 9. June, as Tacho Manager: a 15:04 shift, then a long rest - only 8:56 of it inside the 24 hours.
		// 8 h to 9 h of a reduced rest is below the serious band of 2016/403: minor (Tacho Manager says very serious).
		TL june = new TL(MON - h(48, 0)).rest(h(54, 0)).shift(h(15, 4)).rest(h(391, 56)).shift(h(2, 0));
		List<Violation> jr = of(june.check(), Rule.DAILY_REST);
		check(jr.size() == 1 && jr.get(0).measured == h(8, 56) && jr.get(0).limit == h(9, 0)
				&& jr.get(0).severity == Severity.MINOR, "8:56 of the rest inside the 24 hours: " + jr);

		System.out.println(cases + " violation checks passed");
	}
}
