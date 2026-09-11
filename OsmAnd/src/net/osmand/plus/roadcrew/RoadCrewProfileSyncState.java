package net.osmand.plus.roadcrew;

/** Process-local ACK cache; used only by the serial reports-sync executor. */
final class RoadCrewProfileSyncState {

	private static final long REPAIR_INTERVAL_MILLIS = 60 * 60_000L;
	private String acknowledgedDeviceId;
	private String acknowledgedPayload;
	private long acknowledgedAtMillis;
	private boolean acknowledged;

	boolean beginSync(String deviceId, String payload, long elapsedMillis) {
		if (acknowledged && deviceId.equals(acknowledgedDeviceId)
				&& payload.equals(acknowledgedPayload)
				&& elapsedMillis >= acknowledgedAtMillis
				&& elapsedMillis - acknowledgedAtMillis < REPAIR_INTERVAL_MILLIS) {
			return false;
		}
		// A failed response can hide a successful write. Even reverting to the old
		// profile must retry after this attempt, rather than trusting its old ACK.
		acknowledged = false;
		return true;
	}

	void acknowledge(String deviceId, String payload, long elapsedMillis) {
		acknowledgedDeviceId = deviceId;
		acknowledgedPayload = payload;
		acknowledgedAtMillis = elapsedMillis;
		acknowledged = true;
	}
}
