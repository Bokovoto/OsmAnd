import net.osmand.plus.roadcrew.RoadCrewReportLifetime;

/**
 * Galin, 09.10.2026 (ROADMAP 385): each "още е там" starts a report's three
 * hours again. The voice still measured "old" from the posting, against the
 * whole span from posting to expiry: a post another driver had just seen was
 * announced as "стар маркиран пост" once its history was long enough. "Old"
 * now means not seen for a while - posting, or the last "още е там".
 */
public class ReportLifetimeTest {

	static final long MINUTE = 60 * 1000L;
	static final long HOUR = 60 * MINUTE;
	static final long NOW = 1_791_700_000_000L;

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	/** Posted that long ago and last given its three hours that long ago. */
	static boolean old(long postedAgo, long seenAgo, int confirmed, int denied) {
		return RoadCrewReportLifetime.isOld(NOW - postedAgo, NOW - seenAgo + RoadCrewReportLifetime.MILLIS,
				confirmed, denied, NOW);
	}

	public static void main(String[] args) {
		check(RoadCrewReportLifetime.MILLIS == 3 * HOUR, "three hours, the server's REPORT_LIFETIME_MS");

		check(!old(30 * MINUTE, 30 * MINUTE, 0, 0), "posted half an hour ago, nobody since: fresh");
		check(old(2 * HOUR + 10 * MINUTE, 2 * HOUR + 10 * MINUTE, 0, 0),
				"posted 2 h 10 min ago, nobody since: old");

		check(!old(3 * HOUR + 40 * MINUTE, HOUR + 40 * MINUTE, 1, 0),
				"posted 3 h 40 min ago, another driver saw it 1 h 40 min ago: not old");
		check(!old(8 * HOUR, HOUR + 30 * MINUTE, 4, 0),
				"a post seen all day, last 1 h 30 min ago: not old");
		check(!old(6 * HOUR, 0, 3, 0), "seen just now: not old, however long ago it was posted");
		check(old(5 * HOUR, 2 * HOUR + 10 * MINUTE, 2, 0), "last seen 2 h 10 min ago: old");

		check(old(30 * MINUTE, 30 * MINUTE, 1, 2), "more 'няма го' than 'още е там': old");
		check(!old(30 * MINUTE, 30 * MINUTE, 2, 1), "more 'още е там' than 'няма го': not old");

		long posted = NOW - 5 * HOUR;
		check(RoadCrewReportLifetime.lastSeenAt(posted, posted + RoadCrewReportLifetime.MILLIS) == posted,
				"nobody since: last seen at the posting");
		check(RoadCrewReportLifetime.lastSeenAt(posted, NOW - 40 * MINUTE + RoadCrewReportLifetime.MILLIS)
				== NOW - 40 * MINUTE, "another driver's 'още е там' 40 minutes ago: last seen then");
		check(RoadCrewReportLifetime.lastSeenAt(NOW - 5 * MINUTE, NOW + 55 * MINUTE) == NOW - 5 * MINUTE,
				"a shorter time than three hours: never seen before it was posted");

		// Galin, 11.10.2026: a report that could not go out within 5-10 minutes would only mislead.
		check(RoadCrewReportLifetime.SEND_WINDOW_MILLIS == 10 * MINUTE, "ten minutes to go out");
		check(RoadCrewReportLifetime.maySend(NOW - 9 * MINUTE, NOW, false), "posted nine minutes ago: send it");
		check(!RoadCrewReportLifetime.maySend(NOW - 10 * MINUTE, NOW, false), "ten minutes: too late");
		check(!RoadCrewReportLifetime.maySend(NOW - 2 * HOUR, NOW, false), "two hours without signal: never");
		check(RoadCrewReportLifetime.maySend(NOW - 2 * HOUR, NOW, true), "a call for help, two hours late: still send it");
		check(!RoadCrewReportLifetime.maySend(NOW - 3 * HOUR, NOW, true), "a call for help after its three hours: no");
		check(RoadCrewReportLifetime.unsentExpiry(NOW, false) == NOW + 10 * MINUTE,
				"an unsent report leaves the map when it can no longer go out");
		check(RoadCrewReportLifetime.unsentExpiry(NOW, true) == NOW + 3 * HOUR, "an unsent call for help: three hours");

		// An unsent report stored by an older app, timed by its type - police, one hour.
		check(!RoadCrewReportLifetime.isOld(NOW - 5 * MINUTE, NOW + 55 * MINUTE, 0, 0, NOW),
				"posted five minutes ago with an hour: not seen before it was posted - fresh");
		check(RoadCrewReportLifetime.isOld(NOW - 55 * MINUTE, NOW + 5 * MINUTE, 0, 0, NOW),
				"posted 55 minutes ago, five minutes left: old");

		System.out.println(passed + " report lifetime checks passed");
	}
}
