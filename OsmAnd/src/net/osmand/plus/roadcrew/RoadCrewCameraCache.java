package net.osmand.plus.roadcrew;

/** Single-flight map snapshots. A replaced map invalidates in-flight reads too. */
final class RoadCrewCameraCache<T> {

	private T value;
	private long generation;
	private boolean reading;
	private long retryAfter;

	synchronized T get() {
		return value;
	}

	synchronized long begin(long now) {
		if (reading || now < retryAfter) {
			return -1;
		}
		reading = true;
		return generation;
	}

	synchronized void complete(long ticket, T result, long now) {
		reading = false;
		if (ticket != generation) {
			return;
		}
		if (result == null) {
			retryAfter = now + 5000;
		} else {
			value = result;
			retryAfter = 0;
		}
	}

	synchronized void invalidate() {
		generation++;
		value = null;
		retryAfter = 0;
	}
}
