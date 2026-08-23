package net.osmand.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class RoadCrewShadowIndexTest {

	private static final String FORWARD_FINGERPRINT = "0123456789abcdef0123456789abcdef";
	private static final String REVERSE_FINGERPRINT = "fedcba9876543210fedcba9876543210";

	@Test
	public void matchesOnlyTheExactDirectedSegment() {
		RoadCrewSegmentIdentity.SegmentKey forward = key(43.1, 26.2, 43.2, 26.3,
				FORWARD_FINGERPRINT);
		RoadCrewSegmentIdentity.SegmentKey reverse = key(43.2, 26.3, 43.1, 26.2,
				REVERSE_FINGERPRINT);
		RoadCrewShadowIndex.Entry entry = entry(forward, RoadCrewShadowIndex.Level.CANDIDATE);
		RoadCrewShadowIndex index = RoadCrewShadowIndex.create(1, 1_700_000_000_000L,
				RoadCrewShadowIndex.ROUTING_EFFECT_NONE, Collections.singletonList(entry));

		Assert.assertSame(entry, index.findExact(forward));
		Assert.assertNull(index.findExact(reverse));
		Assert.assertEquals(1, index.count(RoadCrewShadowIndex.Level.CANDIDATE));
		Assert.assertEquals(0, index.count(RoadCrewShadowIndex.Level.MATURE_SHADOW));
	}

	@Test
	public void rejectsAnySnapshotThatClaimsToAffectRouting() {
		try {
			RoadCrewShadowIndex.create(1, 1_700_000_000_000L, "APPLY_TO_ROUTING",
					Collections.emptyList());
			Assert.fail("Expected a fail-closed routing-effect check");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	@Test
	public void rejectsDuplicateSegments() {
		RoadCrewSegmentIdentity.SegmentKey key = key(43.1, 26.2, 43.2, 26.3,
				FORWARD_FINGERPRINT);
		RoadCrewShadowIndex.Entry entry = entry(key, RoadCrewShadowIndex.Level.COLLECTING);
		try {
			RoadCrewShadowIndex.create(1, 1_700_000_000_000L,
					RoadCrewShadowIndex.ROUTING_EFFECT_NONE, Arrays.asList(entry, entry));
			Assert.fail("Expected duplicate shadow segments to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static RoadCrewSegmentIdentity.SegmentKey key(double fromLatitude, double fromLongitude,
			double toLatitude, double toLongitude, String fingerprint) {
		return RoadCrewSegmentIdentity.key(1, 12345L, "Bulgaria_europe",
				fromLatitude, fromLongitude, toLatitude, toLongitude, fingerprint, 125.0);
	}

	private static RoadCrewShadowIndex.Entry entry(RoadCrewSegmentIdentity.SegmentKey key,
			RoadCrewShadowIndex.Level level) {
		return new RoadCrewShadowIndex.Entry(RoadCrewShadowIndex.segmentId(key), key.getCanonicalId(),
				key.getGeometryFingerprint(), level, 0.72, 8, 4, 3);
	}
}
