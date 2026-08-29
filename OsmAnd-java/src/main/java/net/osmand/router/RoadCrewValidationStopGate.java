package net.osmand.router;

/** Allows validation as soon as navigation has ended; GPS drift must not delay the review. */
public final class RoadCrewValidationStopGate {
	public boolean update(long elapsedMillis, boolean eligible, long fixAgeMillis,
			boolean hasSpeed, float speed, boolean hasAccuracy, float accuracy) {
		return eligible;
	}
}
