package net.osmand.plus.roadcrew;

import java.util.List;

/**
 * Which server request "Приключи" closes. On the drive test of 2026-09-12 the
 * button held the Help under its local id (local-...) after the sync had renamed
 * it to the server id, found no exact content match (the server keeps its own
 * created_at) and reported success without telling the server: the Help stayed
 * open for everyone. The answer is now a server id, "never sent" (only a local
 * copy exists), or unknown - and unknown is an error, never a success.
 */
final class RoadCrewHelpResolveTarget {

	enum Kind {
		/** Close it on the server under this id. */
		SERVER,
		/** Never reached the server: removing the local copy is the whole job. */
		LOCAL_ONLY,
		/** No server id found: say so instead of claiming it was closed. */
		UNKNOWN
	}

	/** A synced Help of this phone's own, as candidate for a report held under an old local id. */
	static final class OwnHelp {
		final String id;
		final double lat;
		final double lon;

		OwnHelp(String id, double lat, double lon) {
			this.id = id;
			this.lat = lat;
			this.lon = lon;
		}
	}

	/** Closer than this to where the Help was asked counts as the same Help. */
	static final double SAME_PLACE_METERS = 200;

	final Kind kind;
	final String serverId;

	private RoadCrewHelpResolveTarget(Kind kind, String serverId) {
		this.kind = kind;
		this.serverId = serverId;
	}

	static boolean isServerId(String reportId) {
		return !reportId.startsWith("local-") && !reportId.startsWith("seed-");
	}

	/**
	 * @param stillPendingLocally the local copy still waits for its first send
	 * @param syncedId            the server id recorded when the sync renamed it, or ""
	 * @param contentMatchId      a synced report with exactly the same content, or ""
	 * @param onlyOwnHelpNearbyId the one own synced Help at the same place, or ""
	 */
	static RoadCrewHelpResolveTarget choose(String reportId, boolean stillPendingLocally, String syncedId,
			String contentMatchId, String onlyOwnHelpNearbyId) {
		if (isServerId(reportId)) {
			return new RoadCrewHelpResolveTarget(Kind.SERVER, reportId);
		}
		if (stillPendingLocally) {
			return new RoadCrewHelpResolveTarget(Kind.LOCAL_ONLY, "");
		}
		for (String id : new String[] {syncedId, contentMatchId, onlyOwnHelpNearbyId}) {
			if (!id.isEmpty()) {
				return new RoadCrewHelpResolveTarget(Kind.SERVER, id);
			}
		}
		return new RoadCrewHelpResolveTarget(Kind.UNKNOWN, "");
	}

	/** The id of the single own Help within SAME_PLACE_METERS, or "" when there is none or more than one. */
	static String onlyOwnHelpNear(double lat, double lon, List<OwnHelp> ownHelps) {
		String found = "";
		for (OwnHelp help : ownHelps) {
			if (distanceMeters(lat, lon, help.lat, help.lon) <= SAME_PLACE_METERS) {
				if (!found.isEmpty()) {
					return "";
				}
				found = help.id;
			}
		}
		return found;
	}

	private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
				* Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}
}
