package net.osmand.plus.roadcrew;

/**
 * Galin, 03.10.2026, on test.129 (installed, not yet published): the updater
 * offered the published test.128 as an update, the phone refused the older
 * version, and the offer came back again and again. Only a newer release may
 * be offered.
 */
public class ReleaseVersionsTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	static boolean newer(String latest, String current) {
		return RoadCrewReleaseVersions.isNewer(latest, current);
	}

	public static void main(String[] args) {
		check(newer("roadcrew-v0.1.0-test.129", "roadcrew-v0.1.0-test.128"), "129 over 128: offered");
		check(!newer("roadcrew-v0.1.0-test.128", "roadcrew-v0.1.0-test.129"), "128 over 129: never - the phone refuses it");
		check(!newer("roadcrew-v0.1.0-test.128", "roadcrew-v0.1.0-test.128"), "the same: nothing");
		check(newer("roadcrew-v0.1.0-test.130", "roadcrew-v0.1.0-test.99"), "by number, not by letters: 130 over 99");
		check(!newer("roadcrew-v0.1.0-test.99", "roadcrew-v0.1.0-test.130"), "99 is not over 130");
		check(newer("roadcrew-v0.1.0", "roadcrew-v0.1.0-test.200"), "the release comes after its tests");
		check(!newer("roadcrew-v0.1.0-test.5", "roadcrew-v0.1.0"), "a test is not over its release");
		check(newer("roadcrew-v0.2.0-test.1", "roadcrew-v0.1.0-test.300"), "a higher version over any test of a lower");
		check(newer("roadcrew-v1.0.0", "roadcrew-v0.9.9"), "major");
		check(!newer("roadcrew-v0.1.0-test.abc", "roadcrew-v0.1.0-test.128"), "a tag it cannot read: not offered");
		check(!newer("roadcrew-v0.1.0-test.129", "roadcrew-v0.1.0-dev"), "a build it cannot read: not offered");
		check(!newer("", "roadcrew-v0.1.0-test.128"), "no tag: nothing");
		System.out.println(passed + " release version checks passed");
	}
}
