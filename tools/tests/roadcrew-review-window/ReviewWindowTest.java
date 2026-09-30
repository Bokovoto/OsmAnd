import net.osmand.data.QuadRect;
import net.osmand.plus.roadcrew.RoadCrewReviewWindow;

/**
 * Galin, 01.10.2026: the drive offered for approval is put in the window
 * exactly, and a second later the map has moved and the drive is somewhere
 * off screen. It is put there with two blind timers, 700 ms and 1600 ms after
 * the panel appears, and whatever moves the map after that - the app working
 * out anew where the truck belongs on screen, an animation finishing - drags
 * the drive along with it. So the drive is now watched instead of guessed at:
 * while the panel is new, if the drive has left the window it is put back.
 */
public class ReviewWindowTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	/** left = west, right = east, top = north, bottom = south - the map's own order. */
	static QuadRect box(double west, double north, double east, double south) {
		return new QuadRect(west, north, east, south);
	}

	public static void main(String[] args) {
		QuadRect window = box(23.0, 43.0, 24.0, 42.0);
		QuadRect inside = box(23.2, 42.8, 23.8, 42.2);
		QuadRect halfOut = box(23.2, 42.8, 24.6, 42.2);
		QuadRect gone = box(25.0, 44.0, 26.0, 43.5);

		check(!RoadCrewReviewWindow.needsRefit(400, false, inside, window),
				"the drive is in the window: leave the map alone");
		check(RoadCrewReviewWindow.needsRefit(1000, false, gone, window),
				"a second later the drive is off screen: put it back");
		check(RoadCrewReviewWindow.needsRefit(1000, false, halfOut, window),
				"half of the drive off the window is still not the whole drive");
		check(!RoadCrewReviewWindow.needsRefit(1000, true, gone, window),
				"the driver has his finger on the map: it is his map");
		check(!RoadCrewReviewWindow.needsRefit(20_000, false, gone, window),
				"long after the panel appeared the map is the driver's, wherever he took it");
		check(!RoadCrewReviewWindow.needsRefit(1000, false, null, window),
				"no drive, nothing to keep in view");
		check(!RoadCrewReviewWindow.needsRefit(1000, false, gone, null),
				"no window measured yet: nothing to compare");

		System.out.println(passed + " review window checks passed");
	}
}
