package net.osmand.router;

/** Requires a continuous, fresh stationary fix before any map-validation prompt. */
public final class RoadCrewValidationStopGate {

	private long stationarySince = -1;
	private long lastUpdate = -1;

	public boolean update(long elapsedMillis, boolean eligible, long fixAgeMillis,
			boolean hasSpeed, float speed, boolean hasAccuracy, float accuracy) {
		if (lastUpdate < 0 || elapsedMillis < lastUpdate || elapsedMillis - lastUpdate > 5000) {
			stationarySince = -1;
		}
		lastUpdate = elapsedMillis;
		if (!eligible || fixAgeMillis < 0 || fixAgeMillis > 10_000 || !hasSpeed
				|| !Float.isFinite(speed) || speed < 0 || speed > 0.5f || !hasAccuracy
				|| !Float.isFinite(accuracy) || accuracy < 0 || accuracy > 30) {
			stationarySince = -1;
			return false;
		}
		if (stationarySince < 0 || elapsedMillis < stationarySince) {
			stationarySince = elapsedMillis;
		}
		return elapsedMillis - stationarySince >= 30_000;
	}
}
