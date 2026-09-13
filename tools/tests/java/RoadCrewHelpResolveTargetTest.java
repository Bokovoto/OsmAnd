package net.osmand.plus.roadcrew;

// Drive test 2026-09-12: "Приключи" on a Help held under its local id found no
// server id and reported success without telling the server; the Help stayed
// open for everyone. The choice must end in a server id, "never sent", or an
// error - never a silent success.
public final class RoadCrewHelpResolveTargetTest {

	public static void main(String[] args) {
		serverIdIsUsedAsIs();
		neverSentIsLocalOnly();
		renamedBySyncUsesTheRecordedServerId();
		olderPhonesRequireExactContent();
		nothingFoundIsAnErrorNotASuccess();
		pendingAfterLostResponseIsUnknown();
		System.out.println("help resolve target scenarios PASS");
	}

	private static void serverIdIsUsedAsIs() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("e8590ddd", false, "", "");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.SERVER && target.serverId.equals("e8590ddd"), "server id");
	}

	private static void neverSentIsLocalOnly() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("local-1", true, "", "");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.LOCAL_ONLY, "known never attempted: local copy only");
	}

	private static void renamedBySyncUsesTheRecordedServerId() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("local-1", false, "srv-1", "srv-2");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.SERVER && target.serverId.equals("srv-1"),
				"the id the sync recorded comes first");
	}

	private static void olderPhonesRequireExactContent() {
		RoadCrewHelpResolveTarget byContent = RoadCrewHelpResolveTarget.choose("local-1", false, "", "srv-2");
		check(byContent.serverId.equals("srv-2"), "exact content match");
		RoadCrewHelpResolveTarget unknown = RoadCrewHelpResolveTarget.choose("local-1", false, "", "");
		check(unknown.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN,
				"no exact identity: never pick a different Help at the same place");
	}

	private static void nothingFoundIsAnErrorNotASuccess() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("local-1", false, "", "");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN, "no server id: an error");
		RoadCrewHelpResolveTarget seed = RoadCrewHelpResolveTarget.choose("seed-1", false, "", "");
		check(seed.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN, "a demo report is never sent");
	}

	private static void pendingAfterLostResponseIsUnknown() {
		RoadCrewHelpResolveTarget lost = RoadCrewHelpResolveTarget.choose("local-1", false, "", "");
		check(lost.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN, "attempted or restored pending is not never sent");
		RoadCrewHelpResolveTarget known = RoadCrewHelpResolveTarget.choose("local-1", true, "srv-1", "");
		check(known.kind == RoadCrewHelpResolveTarget.Kind.SERVER, "recorded identity always wins");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
