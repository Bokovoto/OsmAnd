package net.osmand.plus.roadcrew;

// The notifications poll (GET /v1/notifications) ran every 20 s on every phone
// with the map open. The staging cost test of 2026-09-12 counted it as 180 of
// about 330 requests per phone-hour. Galin approved: 20 s only for a phone that
// takes part in a Help (its author, a driver who joined it, an open Help or
// driver chat); every other phone every 2 minutes - Help nearby, chat messages
// and the author's notice also arrive as push, so the poll is a fallback there.
public final class RoadCrewNotificationPollPolicyTest {

	private static final long SECOND = 1_000L;

	public static void main(String[] args) {
		firstCheckIsImmediate();
		participantChecksEveryTwentySeconds();
		bystanderChecksEveryTwoMinutes();
		clockRollbackChecks();
		measureOneHour();
		System.out.println("notification poll policy scenarios PASS");
	}

	private static void firstCheckIsImmediate() {
		check(RoadCrewNotificationPollPolicy.shouldCheck(5 * SECOND, 0, false), "bystander: first check at start");
		check(RoadCrewNotificationPollPolicy.shouldCheck(5 * SECOND, 0, true), "participant: first check at start");
	}

	private static void participantChecksEveryTwentySeconds() {
		long last = 1_000_000L;
		check(!RoadCrewNotificationPollPolicy.shouldCheck(last + 20 * SECOND - 1, last, true), "participant: before 20 s");
		check(RoadCrewNotificationPollPolicy.shouldCheck(last + 20 * SECOND, last, true), "participant: at 20 s");
	}

	private static void bystanderChecksEveryTwoMinutes() {
		long last = 1_000_000L;
		check(!RoadCrewNotificationPollPolicy.shouldCheck(last + 20 * SECOND, last, false), "bystander: not at 20 s");
		check(!RoadCrewNotificationPollPolicy.shouldCheck(last + 120 * SECOND - 1, last, false), "bystander: before 2 min");
		check(RoadCrewNotificationPollPolicy.shouldCheck(last + 120 * SECOND, last, false), "bystander: at 2 min");
	}

	private static void clockRollbackChecks() {
		check(RoadCrewNotificationPollPolicy.shouldCheck(10 * SECOND, 50 * SECOND, false), "clock rollback checks");
	}

	private static void measureOneHour() {
		int participant = count(true);
		int bystander = count(false);
		check(participant == 180, "participant checks in an hour: " + participant);
		check(bystander == 30, "bystander checks in an hour: " + bystander);
		System.out.println("one_hour participant_checks=" + participant + " bystander_checks=" + bystander);
	}

	/** Checks in one hour when the map redraws every second, as in onDraw. */
	private static int count(boolean takingPart) {
		long start = 10 * 60 * SECOND;
		long last = 0;
		int checks = 0;
		for (long now = start; now < start + 3_600 * SECOND; now += SECOND) {
			if (RoadCrewNotificationPollPolicy.shouldCheck(now, last, takingPart)) {
				checks++;
				last = now;
			}
		}
		return checks;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
