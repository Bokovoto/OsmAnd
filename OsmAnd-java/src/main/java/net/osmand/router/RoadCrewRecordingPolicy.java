package net.osmand.router;

/** Shared collection gates. Truck observations require an active navigation session. */
public final class RoadCrewRecordingPolicy {

	private RoadCrewRecordingPolicy() { }

	public static boolean canCollect(boolean consent, boolean truck,
			boolean simulation, boolean navigationActive,
			boolean mapVisible, boolean foregroundService) {
		return consent && truck && !simulation && navigationActive && (mapVisible || foregroundService);
	}

	public static boolean canStartService(boolean consent, boolean truck,
			boolean simulation, boolean navigationActive,
			boolean mapVisible, boolean locationPermission) {
		return canCollect(consent, truck, simulation, navigationActive, mapVisible, false)
				&& locationPermission;
	}

	public static boolean needsOwnGps(boolean recording, boolean mapVisible, boolean otherGpsService) {
		return recording && !mapVisible && !otherGpsService;
	}
}
