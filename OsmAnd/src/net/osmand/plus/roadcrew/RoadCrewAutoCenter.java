package net.osmand.plus.roadcrew;

/**
 * When the map comes back to the truck after the driver moved or zoomed it.
 * Plain Java: tools/tests/roadcrew-auto-center.test.mjs.
 *
 * OsmAnd does it after AUTO_FOLLOW_ROUTE seconds, and only in navigation. A
 * driver watching the map for reports without navigation zoomed with + and -
 * and was left looking at the wrong place (Galin, 01.10.2026, from a driver).
 * Galin chose: the same without navigation, while the truck is moving. The
 * seconds stay the driver's own setting - off means never.
 */
public final class RoadCrewAutoCenter {

	/** 10 km/h: a parked truck's GPS drifts below it, a driver reading the map is not driving. */
	static final float MOVING_SPEED_MPS = 10f / 3.6f;

	private RoadCrewAutoCenter() {
	}

	/** Whether moving the map sets the timer at all. */
	public static boolean armed(int autoFollowSeconds, boolean routePlanningMode) {
		return autoFollowSeconds > 0 && !routePlanningMode;
	}

	/**
	 * Whether the map goes back when the timer fires. Not while a course review
	 * is drawn on the map: that drive is what the driver is looking at.
	 */
	public static boolean returnNow(boolean followingMode, boolean hasSpeed, float speedMps,
			boolean tripReviewShown) {
		if (tripReviewShown) {
			return false;
		}
		return followingMode || (hasSpeed && speedMps >= MOVING_SPEED_MPS - 1e-4f);
	}
}
