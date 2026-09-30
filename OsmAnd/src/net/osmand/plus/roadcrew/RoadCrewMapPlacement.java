package net.osmand.plus.roadcrew;

/**
 * Where the truck stands on the screen. Plain Java: tools/tests/roadcrew-map-place.test.mjs.
 *
 * OsmAnd places it low - 85% down the map - whenever the map turns with the
 * driving direction, so the road ahead is seen. That is made for navigation.
 * Galin, driving the A2 on 01.10.2026, watched the map for reports without
 * navigation and the truck sat under the road name and the RoadCrew buttons.
 * His choice: without navigation the truck stands in the middle of the map;
 * with navigation it stays low, as before.
 *
 * Only where the app is left to choose. A driver who picked the place himself
 * in the settings - always centred, always at the bottom - keeps what he picked.
 */
public final class RoadCrewMapPlacement {

	private RoadCrewMapPlacement() {
	}

	/**
	 * @param appChoosesThePlace the driver left the placement setting on automatic
	 * @param followingMode navigation is running
	 * @param routePlanningMode a route is being planned, when OsmAnd lifts the map itself
	 * @param tripReviewShown a drive is drawn on the map to be approved
	 */
	public static boolean centreOnTheTruck(boolean appChoosesThePlace, boolean followingMode,
			boolean routePlanningMode, boolean tripReviewShown) {
		if (routePlanningMode) {
			return false;
		}
		// A drive put up for approval is read, not driven by. The place must not
		// change under it either: the drive is fitted to the screen from where
		// the truck stands, so moving that afterwards takes the drive with it
		// (Galin, 01.10.2026).
		if (tripReviewShown) {
			return true;
		}
		return appChoosesThePlace && !followingMode;
	}
}
