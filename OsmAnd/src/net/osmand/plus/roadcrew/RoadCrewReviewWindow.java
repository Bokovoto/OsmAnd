package net.osmand.plus.roadcrew;

import net.osmand.data.QuadRect;

/**
 * Keeps the drive offered for approval inside the map window.
 * Plain Java: tools/tests/roadcrew-review-window.test.mjs.
 *
 * The drive was put in the window by two blind timers, 700 ms and 1600 ms after
 * the panel appeared. Anything that moved the map afterwards - the app working
 * out anew where the truck belongs on the screen, an animation ending - took
 * the drive with it, and the driver was left looking at the wrong place
 * (Galin, 01.10.2026, on the road, before the release).
 *
 * So the drive is watched rather than guessed at. While the panel is new and
 * the driver has not taken the map himself, a drive that has left the window is
 * put back. After that the map is his, wherever he moves it.
 */
public final class RoadCrewReviewWindow {

	/** For how long after the panel appears the drive is kept in the window. */
	public static final long SETTLE_MILLIS = 6000;

	private RoadCrewReviewWindow() {
	}

	/**
	 * @param millisSinceShown since the panel with the drive appeared
	 * @param driverTouchingMap the driver is moving or zooming the map right now
	 * @param drive what is drawn for approval, in latitude and longitude
	 * @param window what the map shows, in latitude and longitude
	 */
	public static boolean needsRefit(long millisSinceShown, boolean driverTouchingMap,
			QuadRect drive, QuadRect window) {
		if (drive == null || window == null || driverTouchingMap || millisSinceShown > SETTLE_MILLIS) {
			return false;
		}
		return !holds(window, drive);
	}

	/** The whole drive is inside what the map shows - not most of it, all of it. */
	static boolean holds(QuadRect window, QuadRect drive) {
		return drive.left >= window.left && drive.right <= window.right
				&& drive.top <= window.top && drive.bottom >= window.bottom;
	}
}
