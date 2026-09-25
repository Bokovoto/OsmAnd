import net.osmand.plus.roadcrew.tacho.RoadCrewTachoReminderPlan;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The driver card reminder (Galin, 25.09.2026): 08:00 every day from the 25th
 * to the 30th day after the last download, the deadline at 28 days
 * (Regulation 581/2010). ROADMAP 327.
 */
public class ReminderPlanTest {

	static int passed = 0;
	static final TimeZone SOFIA = TimeZone.getTimeZone("Europe/Sofia");

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	static long at(int year, int month, int day, int hour, int minute) {
		Calendar c = Calendar.getInstance(SOFIA);
		c.clear();
		c.set(year, month - 1, day, hour, minute, 0);
		return c.getTimeInMillis();
	}

	static String show(long millis) {
		Calendar c = Calendar.getInstance(SOFIA);
		c.setTimeInMillis(millis);
		return String.format("%04d-%02d-%02d %02d:%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
				c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
	}

	public static void main(String[] args) {
		// Galin's Gen2 card was marked 25.09.2026 18:08 Sofia time.
		long download = at(2026, 9, 25, 18, 8) / 1000L;

		long first = RoadCrewTachoReminderPlan.next(download, at(2026, 9, 26, 10, 0), SOFIA);
		check(first == at(2026, 10, 20, 8, 0), "first reminder on day 25 at 08:00, got " + show(first));
		check(RoadCrewTachoReminderPlan.next(download, at(2026, 10, 20, 7, 59), SOFIA) == at(2026, 10, 20, 8, 0),
				"a minute before: that same morning");
		long afterFirst = RoadCrewTachoReminderPlan.next(download, at(2026, 10, 20, 8, 0), SOFIA);
		check(afterFirst == at(2026, 10, 21, 8, 0), "fired at 08:00: the next is the day after, got " + show(afterFirst));

		// Day 30 is 25.10.2026, the night the clocks go back: still 08:00 on the wall.
		long last = RoadCrewTachoReminderPlan.next(download, at(2026, 10, 24, 9, 0), SOFIA);
		check(last == at(2026, 10, 25, 8, 0), "day 30 across the clock change, got " + show(last));
		check(RoadCrewTachoReminderPlan.next(download, at(2026, 10, 25, 8, 0), SOFIA) < 0, "nothing after day 30");
		check(RoadCrewTachoReminderPlan.next(0, at(2026, 10, 1, 8, 0), SOFIA) < 0, "no known download, no reminder");

		check(RoadCrewTachoReminderPlan.day(download, at(2026, 10, 20, 8, 0), SOFIA) == 25, "day 25");
		check(RoadCrewTachoReminderPlan.day(download, at(2026, 9, 25, 23, 59), SOFIA) == 0, "download day is day 0");
		check(RoadCrewTachoReminderPlan.day(download, at(2026, 10, 25, 8, 0), SOFIA) == 30, "day 30 across DST");

		long deadline = RoadCrewTachoReminderPlan.deadline(download, SOFIA);
		check(deadline == at(2026, 10, 23, 0, 0), "deadline date 23.10.2026, got " + show(deadline));
		check(RoadCrewTachoReminderPlan.daysLeft(download, at(2026, 10, 20, 8, 0), SOFIA) == 3, "day 25: 3 days left");
		check(RoadCrewTachoReminderPlan.daysLeft(download, at(2026, 10, 23, 8, 0), SOFIA) == 0, "day 28: due today");
		check(RoadCrewTachoReminderPlan.daysLeft(download, at(2026, 10, 24, 8, 0), SOFIA) == -1, "day 29: overdue");

		check(!RoadCrewTachoReminderPlan.remindsOn(download, at(2026, 10, 19, 8, 0), SOFIA), "day 24: quiet");
		check(RoadCrewTachoReminderPlan.remindsOn(download, at(2026, 10, 20, 8, 1), SOFIA), "day 25: remind");
		check(RoadCrewTachoReminderPlan.remindsOn(download, at(2026, 10, 25, 8, 0), SOFIA), "day 30: remind");
		check(!RoadCrewTachoReminderPlan.remindsOn(download, at(2026, 10, 26, 8, 0), SOFIA), "day 31: quiet");

		// A newer download moves everything: downloaded again on day 26.
		long again = at(2026, 10, 21, 12, 0) / 1000L;
		long moved = RoadCrewTachoReminderPlan.next(again, at(2026, 10, 21, 12, 5), SOFIA);
		check(moved == at(2026, 11, 15, 8, 0), "after a new download the next reminder is 25 days on, got " + show(moved));

		System.out.println(passed + " reminder checks passed");
	}
}
