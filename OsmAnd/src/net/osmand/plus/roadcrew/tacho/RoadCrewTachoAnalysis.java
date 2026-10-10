package net.osmand.plus.roadcrew.tacho;

import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Day;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Violation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What the "Анализ" screen shows of one card file (ROADMAP 381): the last 28 days by the
 * phone's clock up to the download, which violations fall in them, and when the next download
 * is due (28 days, Regulation 165/2014 with 2016/799). Plain Java -
 * tools/tests/roadcrew-tacho-analysis.test.mjs.
 */
public final class RoadCrewTachoAnalysis {

	public static final int WINDOW_DAYS = 28;

	public static final class Summary {
		public final LocalDate downloadDate;
		public final LocalDate windowFrom;
		public final int workingDays;
		public final int driving;
		public final int work;
		public final int km;
		public final LocalDate nextDownloadDue;
		public final List<Violation> allViolations;
		public final List<Violation> violationsInWindow;
		/** Every day of the file, newest first. */
		public final List<Day> days;
		/** Not one day could be read from the card: nothing to say about violations (ROADMAP 394). */
		public final boolean noDays;

		Summary(LocalDate downloadDate, LocalDate windowFrom, int workingDays, int driving, int work, int km,
				List<Violation> allViolations, List<Violation> violationsInWindow, List<Day> days) {
			this.downloadDate = downloadDate;
			this.windowFrom = windowFrom;
			this.workingDays = workingDays;
			this.driving = driving;
			this.work = work;
			this.km = km;
			this.nextDownloadDue = downloadDate.plusDays(WINDOW_DAYS);
			this.allViolations = allViolations;
			this.violationsInWindow = violationsInWindow;
			this.days = days;
			this.noDays = days.isEmpty();
		}

		/** The days with any driving, work or availability, newest first. */
		public List<Day> workingDayList() {
			List<Day> out = new ArrayList<>();
			for (Day d : days) {
				if (worked(d)) {
					out.add(d);
				}
			}
			return out;
		}
	}

	private RoadCrewTachoAnalysis() {
	}

	static boolean worked(Day d) {
		return d.driving + d.work + d.available > 0;
	}

	/** {@code downloadMinute}: the download in epoch minutes, {@link Long#MAX_VALUE} when unknown. */
	public static Summary summarize(Card card, List<Violation> violations, ZoneId zone, long downloadMinute) {
		List<Day> days = new ArrayList<>(RoadCrewTachoActivities.days(card, zone));
		Collections.reverse(days);
		long end = downloadMinute;
		if (end == Long.MAX_VALUE) {
			end = card.segments.isEmpty() ? 0 : card.segments.get(card.segments.size() - 1).to - 1;
		}
		LocalDate downloadDate = Instant.ofEpochSecond(end * 60).atZone(zone).toLocalDate();
		LocalDate from = downloadDate.minusDays(WINDOW_DAYS - 1);
		int working = 0;
		int driving = 0;
		int work = 0;
		for (Day d : days) {
			if (d.date.isBefore(from) || d.date.isAfter(downloadDate)) {
				continue;
			}
			if (worked(d)) {
				working++;
			}
			driving += d.driving;
			work += d.work;
		}
		int km = 0;
		for (Map.Entry<LocalDate, Integer> e : card.distanceKm.entrySet()) {
			if (!e.getKey().isBefore(from) && !e.getKey().isAfter(downloadDate)) {
				km += e.getValue();
			}
		}
		long windowStart = from.atStartOfDay(zone).toEpochSecond() / 60;
		List<Violation> inWindow = new ArrayList<>();
		for (Violation v : violations) {
			if (v.to > windowStart) {
				inWindow.add(v);
			}
		}
		return new Summary(downloadDate, from, working, driving, work, km, Collections.unmodifiableList(violations),
				Collections.unmodifiableList(inWindow), Collections.unmodifiableList(days));
	}
}
