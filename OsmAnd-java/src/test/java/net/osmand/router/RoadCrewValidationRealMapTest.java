package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

import static org.junit.Assert.*;

public class RoadCrewValidationRealMapTest {

	@Test
	public void resolvesValidationSectionAndDirectionInOfflineBulgariaMap() throws Exception {
		String path = System.getenv("ROADCREW_TEST_OBF");
		File map = path == null ? null : new File(path);
		Assume.assumeTrue("Set ROADCREW_TEST_OBF for the offline-map integration test", map != null && map.isFile());
		try (RandomAccessFile file = new RandomAccessFile(map, "r")) {
			BinaryMapIndexReader reader = new BinaryMapIndexReader(file, map);
			try {
				RoadCrewObfSegmentLoader.LoadResult local = RoadCrewObfSegmentLoader.load(
						new BinaryMapIndexReader[]{reader}, 43.35, 26.225, 600, 6000, null);
				assertFalse(local.isTruncated());
				assertFalse(local.isCancelled());
				for (RouteDataObject road : local.getRouteObjects()) {
					if (road.getPointsLength() < 2) { continue; }
					int last = road.getPointsLength() - 1;
					RoadCrewSegmentIdentity.SegmentKey forward = RoadCrewSegmentIdentity.create(road, 0, last);
					if (forward.getLengthMeters() < 30 || forward.getLengthMeters() > 3000) { continue; }
					RoadCrewObfSegmentLoader.LoadResult preview = RoadCrewObfSegmentLoader.load(
							new BinaryMapIndexReader[]{reader},
							(forward.getFromLatitude() + forward.getToLatitude()) / 2,
							(forward.getFromLongitude() + forward.getToLongitude()) / 2,
							Math.min(4000, Math.max(600, forward.getLengthMeters() + 200)), 6000, null);
					assertFalse(preview.isTruncated());
					RoadCrewSegmentIdentity.Resolution found = RoadCrewSegmentIdentity.resolve(forward, preview.getRouteObjects());
					if (found.getStatus() != RoadCrewSegmentIdentity.Status.EXACT) { continue; }
					assertEquals(0, found.getStartPointIndex());
					assertEquals(last, found.getEndPointIndex());
					RoadCrewSegmentIdentity.Resolution reversed = RoadCrewSegmentIdentity.resolve(
							RoadCrewSegmentIdentity.create(road, last, 0), preview.getRouteObjects());
					assertEquals(RoadCrewSegmentIdentity.Status.EXACT, reversed.getStatus());
					assertEquals(last, reversed.getStartPointIndex());
					assertEquals(0, reversed.getEndPointIndex());
					System.out.printf("Validation map: Popovo OSM way=%d, length=%.0fm, roads=%d, both directions exact%n",
							forward.getOsmWayId(), forward.getLengthMeters(), preview.getRouteObjects().size());
					return;
				}
				fail("No eligible exact validation section found in the real Popovo map");
			} finally { reader.close(); }
		}
	}
}
