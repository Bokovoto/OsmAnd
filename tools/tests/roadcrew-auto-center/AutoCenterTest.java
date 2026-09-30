import net.osmand.plus.roadcrew.RoadCrewAutoCenter;

/**
 * A driver's request, passed on by Galin on 01.10.2026: watching the map for
 * reports without navigation, after zooming with + and - the map stayed where
 * he left it instead of following the truck. OsmAnd brings the map back after
 * AUTO_FOLLOW_ROUTE seconds only in navigation. Galin chose: the same without
 * navigation, while the truck is moving.
 */
public class AutoCenterTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	static float kmh(double value) {
		return (float) (value / 3.6);
	}

	public static void main(String[] args) {
		// When the timer is set: the driver's own setting decides, as in navigation.
		check(RoadCrewAutoCenter.armed(15, false), "the setting at 15 s: armed, with or without navigation");
		check(!RoadCrewAutoCenter.armed(0, false), "the setting off: never, the driver said so");
		check(!RoadCrewAutoCenter.armed(15, true), "planning a route: the map is his to look at");

		// When it fires: navigation as before; without it, only a moving truck.
		check(RoadCrewAutoCenter.returnNow(true, false, 0f, false), "navigation: as OsmAnd always did");
		check(RoadCrewAutoCenter.returnNow(false, true, kmh(20), false), "free drive at 20 km/h: back to the truck");
		check(RoadCrewAutoCenter.returnNow(false, true, kmh(10), false), "10 km/h is moving");
		check(!RoadCrewAutoCenter.returnNow(false, true, kmh(5), false),
				"5 km/h: a parked truck's GPS drift, or a crawl - the driver may be reading the map");
		check(!RoadCrewAutoCenter.returnNow(false, false, 0f, false), "no speed known: do not move his map");
		check(!RoadCrewAutoCenter.returnNow(false, true, kmh(50), true),
				"a course review on the map: never pulled away from it");
		check(!RoadCrewAutoCenter.returnNow(true, true, kmh(50), true), "not in navigation either");

		System.out.println(passed + " auto-centre checks passed");
	}
}
