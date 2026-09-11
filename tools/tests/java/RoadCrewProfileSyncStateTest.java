package net.osmand.plus.roadcrew;

public final class RoadCrewProfileSyncStateTest {

	private static final long HOUR = 3_600_000L;
	private static final String DEVICE = "test-device";
	private static final String A = "profile A";
	private static final String B = "profile B: changed name or plates";

	public static void main(String[] args) {
		firstSyncAndRepairBoundary();
		failedAttemptsAndRevertedEdits();
		changedIdentityAndSnapshot();
		clockRollbackAndRestart();
		measureSamePopulation();
		System.out.println("profile sync runtime scenarios PASS");
	}

	private static void firstSyncAndRepairBoundary() {
		RoadCrewProfileSyncState state = new RoadCrewProfileSyncState();
		check(state.beginSync(DEVICE, A, 0), "first profile must send");
		state.acknowledge(DEVICE, A, 0);
		check(!state.beginSync(DEVICE, A, 0), "duplicate after ACK");
		check(!state.beginSync(DEVICE, A, HOUR - 1), "before repair deadline");
		check(state.beginSync(DEVICE, A, HOUR), "exact repair deadline");
		state.acknowledge(DEVICE, A, HOUR);
		check(!state.beginSync(DEVICE, A, HOUR + 1), "repair ACK starts next window");
	}

	private static void failedAttemptsAndRevertedEdits() {
		RoadCrewProfileSyncState state = new RoadCrewProfileSyncState();
		check(state.beginSync(DEVICE, A, 0), "first failed request");
		check(state.beginSync(DEVICE, A, 1), "no ACK means retry");
		state.acknowledge(DEVICE, A, 1);
		check(state.beginSync(DEVICE, B, 2), "changed profile must send");
		// B might have reached the server even though its response was lost.
		check(state.beginSync(DEVICE, A, 3), "reverting to old A must not trust its old ACK");
		state.acknowledge(DEVICE, A, 3);
		check(state.beginSync(DEVICE, A, HOUR + 3), "failed repair starts");
		check(state.beginSync(DEVICE, A, HOUR + 4), "failed repair retries immediately next sync");
	}

	private static void changedIdentityAndSnapshot() {
		RoadCrewProfileSyncState state = new RoadCrewProfileSyncState();
		check(state.beginSync(DEVICE, A, 0), "snapshot A sent");
		state.acknowledge(DEVICE, A, 0);
		check(state.beginSync(DEVICE, B, 1), "edit during A request is not acknowledged by A");
		state.acknowledge(DEVICE, B, 1);
		check(state.beginSync(DEVICE, "alerts disabled; empty plates", 2), "opt-out sends");
		state.acknowledge(DEVICE, "alerts disabled; empty plates", 2);
		check(state.beginSync(DEVICE, B, 3), "opt-in sends");
		state.acknowledge(DEVICE, B, 3);
		check(state.beginSync("other-device", B, 4), "ACK is device-scoped");
	}

	private static void clockRollbackAndRestart() {
		RoadCrewProfileSyncState state = new RoadCrewProfileSyncState();
		check(state.beginSync(DEVICE, A, 100), "first send");
		state.acknowledge(DEVICE, A, 100);
		check(state.beginSync(DEVICE, A, 99), "clock rollback refreshes");
		check(new RoadCrewProfileSyncState().beginSync(DEVICE, A, 101), "restart has no ACK");
	}

	private static void measureSamePopulation() {
		RoadCrewProfileSyncState state = new RoadCrewProfileSyncState();
		int control = 0;
		int treatment = 0;
		int skipped = 0;
		for (int minute = 0; minute < 600; minute++) {
			long now = minute * 60_000L;
			control++;
			if (state.beginSync(DEVICE, A, now)) {
				treatment++;
				state.acknowledge(DEVICE, A, now);
			} else {
				skipped++;
			}
		}
		check(control == 600 && treatment == 10 && skipped == 590, "request comparison");
		check(treatment + skipped == control, "same population accounting");
		System.out.println("opportunities=600 control_posts=" + control
				+ " treatment_posts=" + treatment + " skipped=" + skipped);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
