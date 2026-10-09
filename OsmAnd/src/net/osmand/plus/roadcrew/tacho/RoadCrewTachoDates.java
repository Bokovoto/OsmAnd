package net.osmand.plus.roadcrew.tacho;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Dates of the driving-time screens. Galin, 09.10.2026: "добави годината при старите дни" - a
 * day of another year than today's shows its year ("Пт, 28.04.2023"), this year's stays short
 * ("Пт, 25.09"). Plain Java - tools/tests/roadcrew-tacho-analysis.test.mjs.
 */
public final class RoadCrewTachoDates {

	private RoadCrewTachoDates() {
	}

	/** "Пт, 25.09", or "Пт, 28.04.2023" for another year. */
	public static String shortDay(LocalDate date, LocalDate today, Locale locale) {
		return weekday(date, locale) + ", " + date.format(DateTimeFormatter.ofPattern(pattern(date, today)));
	}

	/** "Пн, 13.07 06:44", or "Пн, 07.06.2021 03:00" for another year. */
	public static String dayTime(ZonedDateTime time, LocalDate today, Locale locale) {
		LocalDate date = time.toLocalDate();
		return weekday(date, locale) + ", " + time.format(DateTimeFormatter.ofPattern(pattern(date, today) + " HH:mm"));
	}

	private static String pattern(LocalDate date, LocalDate today) {
		return date.getYear() == today.getYear() ? "dd.MM" : "dd.MM.yyyy";
	}

	private static String weekday(LocalDate date, Locale locale) {
		String s = date.format(DateTimeFormatter.ofPattern("EE", locale)).replace(".", "");
		return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
	}
}
