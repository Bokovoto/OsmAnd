package net.osmand.plus.roadcrew;

/**
 * How long a report lives. Plain Java: tools/tests/roadcrew-report-lifetime.test.mjs.
 *
 * Galin, 09.10.2026 (ROADMAP 385): "Всички да стоят три часа от поставянето
 * като всяко още е там да рестартира брояча". The server gives every report
 * three hours from posting, and another driver's "още е там" three hours from
 * that vote; the phone takes the server's expiresAt on every sync. Only a report
 * not yet sent - posted with no signal - is timed here, and it lived by its type
 * (police one hour, mobile camera a day) until 11.10.2026.
 */
public final class RoadCrewReportLifetime {

	/** The server's REPORT_LIFETIME_MS, for every type. */
	public static final long MILLIS = 3 * 60 * 60 * 1000L;

	private static final long OLD_REPORT_MIN_AGE_MILLIS = 20 * 60 * 1000;
	private static final long EXPIRING_SOON_MILLIS = 10 * 60 * 1000;
	private static final double OLD_REPORT_LIFETIME_RATIO = 0.7;

	private RoadCrewReportLifetime() {
	}

	/**
	 * Whether the voice warns of an old marked post ("Бъдете внимателни ... стар
	 * маркиран пост"): one nobody has seen for a while.
	 *
	 * It was measured from the posting, against the whole span from posting to
	 * expiry - so a post another driver had just seen was "old" once its history
	 * was long enough. The server sends no time of the last "още е там", but each
	 * one gives the report three hours from that moment: what is left of them
	 * says when it was last seen. Never before it was posted, though - an unsent
	 * report stored by an older app may still carry its type's shorter time.
	 */
	public static boolean isOld(long createdAtMillis, long expiresAtMillis, int confirmedCount, int deniedCount,
			long now) {
		if (deniedCount > 0 && deniedCount >= confirmedCount) {
			return true;
		}
		long remaining = expiresAtMillis - now;
		long sinceSeen = now - lastSeenAt(createdAtMillis, expiresAtMillis);
		return sinceSeen >= OLD_REPORT_MIN_AGE_MILLIS
				&& (sinceSeen >= MILLIS * OLD_REPORT_LIFETIME_RATIO || remaining <= EXPIRING_SOON_MILLIS);
	}

	/**
	 * When a report was last seen: its posting, or the last "още е там" that gave
	 * it its three hours. The map label counts its minutes from here (Galin,
	 * 11.10.2026: "да").
	 */
	public static long lastSeenAt(long createdAtMillis, long expiresAtMillis) {
		return Math.max(createdAtMillis, expiresAtMillis - MILLIS);
	}
}
