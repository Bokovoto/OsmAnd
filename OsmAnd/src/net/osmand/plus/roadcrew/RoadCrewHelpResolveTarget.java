package net.osmand.plus.roadcrew;

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
	 * @param neverAttempted true only when this process knows no create was attempted;
	 *                       PENDING_CREATE alone cannot establish this after a lost response
	 * @param syncedId            the server id recorded when the sync renamed it, or ""
	 * @param contentMatchId      a synced report with exactly the same content, or ""
	 */
	static RoadCrewHelpResolveTarget choose(String reportId, boolean neverAttempted, String syncedId,
			String contentMatchId) {
		if (isServerId(reportId)) {
			return new RoadCrewHelpResolveTarget(Kind.SERVER, reportId);
		}
		for (String id : new String[] {syncedId, contentMatchId}) {
			if (!id.isEmpty()) {
				return new RoadCrewHelpResolveTarget(Kind.SERVER, id);
			}
		}
		if (neverAttempted) {
			return new RoadCrewHelpResolveTarget(Kind.LOCAL_ONLY, "");
		}
		return new RoadCrewHelpResolveTarget(Kind.UNKNOWN, "");
	}
}
