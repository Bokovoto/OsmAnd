package net.osmand.plus.roadcrew.tacho;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Rule;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Severity;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Violation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ROADMAP 381: what the "Анализ" screen shows of one card file - the last 28 days by the
 * phone's clock, which violations fall in them, when the next download is due - and the
 * countries of the card's places, from the JRC's official NationNumeric table.
 */
public class AnalysisTest {
	static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
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

	public static void main(String[] args) {
		// Three working days in the window, one long before it; downloaded 26.09.2026 05:23 UTC (08:23 Sofia).
		List<Segment> segs = new ArrayList<>();
		long t = at("2026-08-01T00:00");
		segs.add(new Segment(t, at("2026-08-01T06:00"), Kind.REST, true, true));
		segs.add(new Segment(at("2026-08-01T06:00"), at("2026-08-01T10:00"), Kind.DRIVING, true, true));
		segs.add(new Segment(at("2026-08-01T10:00"), at("2026-09-24T06:00"), Kind.REST, false, false));
		segs.add(new Segment(at("2026-09-24T06:00"), at("2026-09-24T14:00"), Kind.DRIVING, true, true));
		segs.add(new Segment(at("2026-09-24T14:00"), at("2026-09-24T14:30"), Kind.WORK, true, true));
		segs.add(new Segment(at("2026-09-24T14:30"), at("2026-09-25T06:00"), Kind.REST, true, true));
		segs.add(new Segment(at("2026-09-25T06:00"), at("2026-09-25T13:00"), Kind.DRIVING, true, true));
		segs.add(new Segment(at("2026-09-25T13:00"), at("2026-09-26T05:00"), Kind.REST, true, true));
		segs.add(new Segment(at("2026-09-26T05:00"), at("2026-09-26T05:23"), Kind.DRIVING, true, true));
		Map<LocalDate, Integer> km = new TreeMap<>();
		km.put(LocalDate.of(2026, 8, 1), 300);
		km.put(LocalDate.of(2026, 9, 24), 650);
		km.put(LocalDate.of(2026, 9, 25), 540);
		km.put(LocalDate.of(2026, 9, 26), 25);
		Card card = new Card(segs, List.of(), true, true, km);
		List<Violation> violations = List.of(
				new Violation(Rule.WEEKLY_REST_LATE, Severity.VERY_SERIOUS, at("2026-07-13T05:44"), at("2026-07-18T10:42"),
						7498, 0, 1, false),
				new Violation(Rule.BREAK, Severity.MINOR, at("2026-09-24T06:00"), at("2026-09-24T10:40"), 280, 270, 1, false));
		long download = at("2026-09-26T05:23");
		RoadCrewTachoAnalysis.Summary s = RoadCrewTachoAnalysis.summarize(card, violations, SOFIA, download);
		check(s.downloadDate.equals(LocalDate.of(2026, 9, 26)), "downloaded on 26.09 by the phone's clock");
		check(s.windowFrom.equals(LocalDate.of(2026, 8, 30)), "28 days: 30.08 - 26.09, was " + s.windowFrom);
		check(s.workingDays == 3, "three working days in the window, was " + s.workingDays);
		check(s.driving == 8 * 60 + 7 * 60 + 23, "driving in the window, was " + s.driving);
		check(s.work == 30, "work in the window");
		check(s.km == 650 + 540 + 25, "km in the window, was " + s.km);
		check(s.nextDownloadDue.equals(LocalDate.of(2026, 10, 24)), "the next download within 28 days: 24.10");
		check(s.violationsInWindow.size() == 1 && s.violationsInWindow.get(0).rule == Rule.BREAK,
				"only the September violation is in the window");
		check(s.allViolations.size() == 2, "the whole card keeps both");
		check(s.days.get(0).date.equals(LocalDate.of(2026, 9, 26)), "the days newest first");
		check(s.workingDayList().size() == 4, "days with work, newest first: 26.09, 25.09, 24.09, 01.08");

		// A download time unknown: the newest activity's day stands for it.
		RoadCrewTachoAnalysis.Summary unknown = RoadCrewTachoAnalysis.summarize(card, violations, SOFIA, Long.MAX_VALUE);
		check(unknown.downloadDate.equals(LocalDate.of(2026, 9, 26)), "no download time: the last day of data");

		// The official NationNumeric table (JRC, dtc_nation_codes).
		check(RoadCrewTachoNations.alpha(0x07).equals("BG") && RoadCrewTachoNations.bulgarian(0x07).equals("България"), "07 Bulgaria");
		check(RoadCrewTachoNations.alpha(0x0D).equals("D") && RoadCrewTachoNations.bulgarian(0x0D).equals("Германия"), "0D Germany");
		check(RoadCrewTachoNations.alpha(0x18).equals("H") && RoadCrewTachoNations.bulgarian(0x18).equals("Унгария"), "18 Hungary");
		check(RoadCrewTachoNations.alpha(0x29).equals("RO") && RoadCrewTachoNations.bulgarian(0x29).equals("Румъния"), "29 Romania");
		check(RoadCrewTachoNations.alpha(0x35).equals("SRB") && RoadCrewTachoNations.english(0x35).equals("Serbia"), "35 Serbia");
		check(RoadCrewTachoNations.alpha(0x17).equals("GR") && RoadCrewTachoNations.bulgarian(0x17).equals("Гърция"), "17 Greece");
		check(RoadCrewTachoNations.alpha(0x30).equals("TR"), "30 Turkey");
		check(RoadCrewTachoNations.alpha(0xFF).equals("WLD") && RoadCrewTachoNations.alpha(0xFD).equals("EC"), "FD, FF");
		check(RoadCrewTachoNations.alpha(0x50).equals("?"), "reserved: unknown, not invented");

		// Galin, 09.10.2026: "добави годината при старите дни" - a day of another year shows its year.
		java.util.Locale bg = new java.util.Locale("bg");
		LocalDate today = LocalDate.of(2026, 10, 9);
		check(RoadCrewTachoDates.shortDay(LocalDate.of(2023, 4, 28), today, bg).equals("Пт, 28.04.2023"),
				"an old day with its year: " + RoadCrewTachoDates.shortDay(LocalDate.of(2023, 4, 28), today, bg));
		check(RoadCrewTachoDates.shortDay(LocalDate.of(2026, 9, 25), today, bg).equals("Пт, 25.09"),
				"this year's day stays short: " + RoadCrewTachoDates.shortDay(LocalDate.of(2026, 9, 25), today, bg));
		java.time.ZonedDateTime old = LocalDateTime.parse("2021-06-07T03:00").atZone(SOFIA);
		check(RoadCrewTachoDates.dayTime(old, today, bg).equals("Пн, 07.06.2021 03:00"),
				"an old violation with its year: " + RoadCrewTachoDates.dayTime(old, today, bg));
		java.time.ZonedDateTime recent = LocalDateTime.parse("2026-07-13T06:44").atZone(SOFIA);
		check(RoadCrewTachoDates.dayTime(recent, today, bg).equals("Пн, 13.07 06:44"),
				"this year's stays short: " + RoadCrewTachoDates.dayTime(recent, today, bg));

		System.out.println(cases + " analysis checks passed");
	}
}
