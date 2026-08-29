package net.osmand.router;

/** Recording boundaries; only explicit navigation end makes a course eligible for an automatic review. */
public final class RoadCrewTripLifecycle {

	private static final long FREE_DRIVE_GAP_MILLIS = 20 * 60_000L;
	private boolean navigating;

	public boolean startNavigation() {
		if (navigating) { return false; }
		navigating = true;
		return true;
	}

	public boolean endNavigation() {
		if (!navigating) { return false; }
		navigating = false;
		return true;
	}

	public boolean isNavigating() { return navigating; }

	public boolean shouldCloseForGap(long previousPassage, long passage) {
		return !navigating && (passage < previousPassage || passage - previousPassage > FREE_DRIVE_GAP_MILLIS);
	}

	public void reset() { navigating = false; }
}
