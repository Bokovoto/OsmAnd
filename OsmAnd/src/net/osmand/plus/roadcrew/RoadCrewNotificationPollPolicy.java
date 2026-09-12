package net.osmand.plus.roadcrew;

/**
 * How often the map asks the server for notifications (GET /v1/notifications).
 * Every 20 s for a phone taking part in a Help - its author, a driver who joined
 * it, or one with a Help or driver chat open; every 2 minutes for everyone else.
 * Help nearby, chat messages and the author's notice also arrive as push, so for
 * a bystander the poll is only a fallback. The staging cost test of 2026-09-12
 * counted this poll as 180 of about 330 requests per phone-hour.
 */
final class RoadCrewNotificationPollPolicy {

	static final long PARTICIPANT_INTERVAL_MILLIS = 20_000L;
	static final long BYSTANDER_INTERVAL_MILLIS = 120_000L;

	private RoadCrewNotificationPollPolicy() {
	}

	/** Whether to check now. Never checked yet, or a clock that went back: check at once. */
	static boolean shouldCheck(long nowMillis, long lastCheckMillis, boolean takingPartInHelp) {
		long elapsed = nowMillis - lastCheckMillis;
		if (lastCheckMillis == 0 || elapsed < 0) {
			return true;
		}
		return elapsed >= (takingPartInHelp ? PARTICIPANT_INTERVAL_MILLIS : BYSTANDER_INTERVAL_MILLIS);
	}
}
