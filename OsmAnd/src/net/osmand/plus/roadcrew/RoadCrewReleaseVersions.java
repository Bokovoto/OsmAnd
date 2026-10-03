package net.osmand.plus.roadcrew;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which release tag is newer. Plain Java, no Android -
 * tools/tests/roadcrew-update-version.test.mjs.
 *
 * Galin, 03.10.2026: on test.129, installed but not yet published, the updater
 * offered the published test.128 - it only checked that the tag was different.
 * The phone refused the older version and the offer came back. Only a newer
 * release may be offered.
 */
final class RoadCrewReleaseVersions {

	/** "roadcrew-v0.1.0-test.129", or "roadcrew-v0.1.0" for a release after its tests. */
	private static final Pattern TAG = Pattern.compile("roadcrew-v(\\d+)\\.(\\d+)\\.(\\d+)(?:-test\\.(\\d+))?");

	private RoadCrewReleaseVersions() {
	}

	/** Whether latestTag is newer than currentTag; false when either cannot be read. */
	static boolean isNewer(String latestTag, String currentTag) {
		long[] latest = parse(latestTag);
		long[] current = parse(currentTag);
		if (latest == null || current == null) {
			return false;
		}
		for (int i = 0; i < latest.length; i++) {
			if (latest[i] != current[i]) {
				return latest[i] > current[i];
			}
		}
		return false;
	}

	/** major, minor, patch, test - a release without a test number sorts after all of its tests. */
	private static long[] parse(String tag) {
		if (tag == null) {
			return null;
		}
		Matcher matcher = TAG.matcher(tag);
		if (!matcher.matches()) {
			return null;
		}
		try {
			String test = matcher.group(4);
			return new long[]{
					Long.parseLong(matcher.group(1)),
					Long.parseLong(matcher.group(2)),
					Long.parseLong(matcher.group(3)),
					test == null ? Long.MAX_VALUE : Long.parseLong(test)
			};
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
