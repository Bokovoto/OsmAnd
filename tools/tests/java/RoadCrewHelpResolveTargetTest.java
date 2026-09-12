package net.osmand.plus.roadcrew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// Drive test 2026-09-12: "Приключи" on a Help held under its local id found no
// server id and reported success without telling the server; the Help stayed
// open for everyone. The choice must end in a server id, "never sent", or an
// error - never a silent success.
public final class RoadCrewHelpResolveTargetTest {

	public static void main(String[] args) {
		serverIdIsUsedAsIs();
		neverSentIsLocalOnly();
		renamedBySyncUsesTheRecordedServerId();
		olderPhonesFallBackToContentThenToTheOwnHelpAtThatPlace();
		nothingFoundIsAnErrorNotASuccess();
		ownHelpNearby();
		System.out.println("help resolve target scenarios PASS");
	}

	private static void serverIdIsUsedAsIs() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("e8590ddd", false, "", "", "");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.SERVER && target.serverId.equals("e8590ddd"), "server id");
	}

	private static void neverSentIsLocalOnly() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("local-1", true, "", "", "");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.LOCAL_ONLY, "still pending: only the local copy");
	}

	private static void renamedBySyncUsesTheRecordedServerId() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("local-1", false, "srv-1", "srv-2", "srv-3");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.SERVER && target.serverId.equals("srv-1"),
				"the id the sync recorded comes first");
	}

	private static void olderPhonesFallBackToContentThenToTheOwnHelpAtThatPlace() {
		RoadCrewHelpResolveTarget byContent = RoadCrewHelpResolveTarget.choose("local-1", false, "", "srv-2", "srv-3");
		check(byContent.serverId.equals("srv-2"), "exact content match");
		RoadCrewHelpResolveTarget byPlace = RoadCrewHelpResolveTarget.choose("local-1", false, "", "", "srv-3");
		check(byPlace.kind == RoadCrewHelpResolveTarget.Kind.SERVER && byPlace.serverId.equals("srv-3"),
				"the drive-test case: the server copy replaced the content, the own Help at the place remains");
	}

	private static void nothingFoundIsAnErrorNotASuccess() {
		RoadCrewHelpResolveTarget target = RoadCrewHelpResolveTarget.choose("local-1", false, "", "", "");
		check(target.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN, "no server id: an error");
		RoadCrewHelpResolveTarget seed = RoadCrewHelpResolveTarget.choose("seed-1", false, "", "", "");
		check(seed.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN, "a demo report is never sent");
	}

	private static void ownHelpNearby() {
		RoadCrewHelpResolveTarget.OwnHelp here = new RoadCrewHelpResolveTarget.OwnHelp("srv-a", 43.2, 27.8);
		RoadCrewHelpResolveTarget.OwnHelp alsoHere = new RoadCrewHelpResolveTarget.OwnHelp("srv-b", 43.2008, 27.8);
		RoadCrewHelpResolveTarget.OwnHelp farAway = new RoadCrewHelpResolveTarget.OwnHelp("srv-c", 43.21, 27.8);
		check(RoadCrewHelpResolveTarget.onlyOwnHelpNear(43.2005, 27.8, Collections.singletonList(here)).equals("srv-a"),
				"55 m away: the same Help");
		check(RoadCrewHelpResolveTarget.onlyOwnHelpNear(43.2005, 27.8, Arrays.asList(here, farAway)).equals("srv-a"),
				"one near, one 1 km away");
		check(RoadCrewHelpResolveTarget.onlyOwnHelpNear(43.2004, 27.8, Arrays.asList(here, alsoHere)).isEmpty(),
				"two at the place: do not guess");
		check(RoadCrewHelpResolveTarget.onlyOwnHelpNear(43.2, 27.8, Collections.singletonList(farAway)).isEmpty(),
				"only one 1.1 km away: not the same Help");
		check(RoadCrewHelpResolveTarget.onlyOwnHelpNear(43.2, 27.8, new ArrayList<>()).isEmpty(), "none");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
