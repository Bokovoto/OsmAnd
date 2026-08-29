package net.osmand.router;

/** Shared collection gates; a route is deliberately not required. */
public final class RoadCrewRecordingPolicy {

	private RoadCrewRecordingPolicy() { }

	public static boolean canCollect(boolean consent, boolean truck,
			boolean simulation, boolean mapVisible, boolean foregroundService) {
		return consent && truck && !simulation && (mapVisible || foregroundService);
	}

	public static boolean canStartService(boolean consent, boolean truck,
			boolean simulation, boolean mapVisible, boolean locationPermission) {
		return canCollect(consent, truck, simulation, mapVisible, false) && locationPermission;
	}

	public static boolean needsOwnGps(boolean recording, boolean mapVisible, boolean otherGpsService) {
		return recording && !mapVisible && !otherGpsService;
	}
}
