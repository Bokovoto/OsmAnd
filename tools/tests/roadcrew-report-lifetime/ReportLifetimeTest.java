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

		// An unsent report stored by an older app, timed by its type - police, one hour.
		check(!RoadCrewReportLifetime.isOld(NOW - 5 * MINUTE, NOW + 55 * MINUTE, 0, 0, NOW),
				"posted five minutes ago with an hour: not seen before it was posted - fresh");
		check(RoadCrewReportLifetime.isOld(NOW - 55 * MINUTE, NOW + 5 * MINUTE, 0, 0, NOW),
				"posted 55 minutes ago, five minutes left: old");

		System.out.println(passed + " report lifetime checks passed");
	}
}
