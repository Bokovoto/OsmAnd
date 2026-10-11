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

	private RoadCrewReportLifetime() {
	}
}
