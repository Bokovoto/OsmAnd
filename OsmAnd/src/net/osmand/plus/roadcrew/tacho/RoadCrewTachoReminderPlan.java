package net.osmand.plus.roadcrew.tacho;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * When the driver card reminder fires (Galin, 25.09.2026): at 08:00 every day
 * from the 25th to the 30th day after the last download the phone knows of.
 * The deadline itself is the 28th day - driver card data at least every 28
 * days (Commission Regulation (EU) No 581/2010). Days are calendar days in
 * the phone's time zone; day 0 is the day of the download. Plain Java, tested
 * without a phone (ROADMAP 327).
 */
public final class RoadCrewTachoReminderPlan {

	public static final int FIRST_DAY = 25;
	public static final int LAST_DAY = 30;
	public static final int DEADLINE_DAY = 28;
	public static final int HOUR = 8;

	private static final long DAY_MILLIS = 86_400_000L;

	private RoadCrewTachoReminderPlan() {
	}

	/** The next reminder strictly after {@code nowMillis}, or -1 when none is left (or no download is known). */
	public static long next(long downloadSeconds, long nowMillis, TimeZone zone) {
		if (downloadSeconds <= 0) {
			return -1;
		}
		for (int day = FIRST_DAY; day <= LAST_DAY; day++) {
			Calendar at = startOfDay(downloadSeconds, day, zone);
			at.set(Calendar.HOUR_OF_DAY, HOUR);
			if (at.getTimeInMillis() > nowMillis) {
				return at.getTimeInMillis();
			}
		}
		return -1;
	}

	/** Calendar days from the download to {@code atMillis}. */
	public static int day(long downloadSeconds, long atMillis, TimeZone zone) {
		return (int) (epochDay(atMillis, zone) - epochDay(downloadSeconds * 1000L, zone));
	}

	/** Whether {@code atMillis} falls on one of the reminder days. */
	public static boolean remindsOn(long downloadSeconds, long atMillis, TimeZone zone) {
		int day = day(downloadSeconds, atMillis, zone);
		return downloadSeconds > 0 && day >= FIRST_DAY && day <= LAST_DAY;
	}

	/** Start of the deadline day, the 28th after the download. */
	public static long deadline(long downloadSeconds, TimeZone zone) {
		return startOfDay(downloadSeconds, DEADLINE_DAY, zone).getTimeInMillis();
	}

	/** Days until the deadline day: 0 on it, negative once it has passed. */
	public static int daysLeft(long downloadSeconds, long atMillis, TimeZone zone) {
		return DEADLINE_DAY - day(downloadSeconds, atMillis, zone);
	}

	private static Calendar startOfDay(long downloadSeconds, int days, TimeZone zone) {
		Calendar calendar = Calendar.getInstance(zone);
		calendar.setTimeInMillis(downloadSeconds * 1000L);
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		calendar.add(Calendar.DAY_OF_MONTH, days);
		return calendar;
	}

	/** The local calendar date as a day count, so a clock change never shifts it. */
	private static long epochDay(long millis, TimeZone zone) {
		Calendar local = Calendar.getInstance(zone);
		local.setTimeInMillis(millis);
		Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		utc.clear();
		utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
		return utc.getTimeInMillis() / DAY_MILLIS;
	}
}
