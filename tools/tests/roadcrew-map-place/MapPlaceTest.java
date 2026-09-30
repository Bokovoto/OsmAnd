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
		check(RoadCrewMapPlacement.centreOnTheTruck(true, false, false),
				"free drive, the app chooses the place: the truck stands in the middle");
		check(!RoadCrewMapPlacement.centreOnTheTruck(true, true, false),
				"navigation: low, so the road ahead is seen");
		check(!RoadCrewMapPlacement.centreOnTheTruck(true, false, true),
				"planning a route: OsmAnd lifts the map itself, we do not touch it");
		check(!RoadCrewMapPlacement.centreOnTheTruck(false, false, false),
				"the driver picked the place in the settings: his choice stands");
		check(!RoadCrewMapPlacement.centreOnTheTruck(false, true, false),
				"his choice stands in navigation too");

		System.out.println(passed + " map place checks passed");
	}
}
