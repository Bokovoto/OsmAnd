import net.osmand.plus.roadcrew.RoadCrewMapPlacement;

/**
 * Galin, 01.10.2026, from his own drive: without navigation the truck sat at
 * the very bottom of the map, under the street name and the RoadCrew buttons.
 * OsmAnd places it low whenever the map turns with the driving direction -
 * made for navigation, so the road ahead is seen. Galin chose: without
 * navigation the truck stands in the middle of the map, with navigation it
 * stays low.
 */
public class MapPlaceTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	public static void main(String[] args) {
		check(RoadCrewMapPlacement.centreOnTheTruck(true, false, false, false),
				"free drive, the app chooses the place: the truck stands in the middle");
		check(!RoadCrewMapPlacement.centreOnTheTruck(true, true, false, false),
				"navigation: low, so the road ahead is seen");
		check(!RoadCrewMapPlacement.centreOnTheTruck(true, false, true, false),
				"planning a route: OsmAnd lifts the map itself, we do not touch it");
		check(!RoadCrewMapPlacement.centreOnTheTruck(false, false, false, false),
				"the driver picked the place in the settings: his choice stands");
		check(!RoadCrewMapPlacement.centreOnTheTruck(false, true, false, false),
				"his choice stands in navigation too");

		// A drive up for approval is read: the place must hold still under it.
		check(RoadCrewMapPlacement.centreOnTheTruck(true, true, false, true),
				"approving a drive during navigation: the map holds still, centred");
		check(RoadCrewMapPlacement.centreOnTheTruck(false, false, false, true),
				"and whatever the setting says, for as long as the drive is drawn");

		System.out.println(passed + " map place checks passed");
	}
}
