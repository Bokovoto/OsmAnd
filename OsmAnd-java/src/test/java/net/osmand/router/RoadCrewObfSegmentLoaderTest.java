package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
public class RoadCrewObfSegmentLoaderTest {

	@Test
	public void returnsEmptyShadowSnapshotWithoutMapReaders() throws Exception {
		RoadCrewObfSegmentLoader.ShadowSnapshot snapshot = RoadCrewObfSegmentLoader.analyze(
				new BinaryMapIndexReader[0], 43.2141, 27.9147, 250, 1000, null);

		Assert.assertEquals(0, snapshot.getReaderCount());
		Assert.assertEquals(0, snapshot.getRouteObjectCount());
		Assert.assertEquals(0, snapshot.getSegments().size());
		Assert.assertFalse(snapshot.isTruncated());
		Assert.assertFalse(snapshot.isCancelled());
	}

	@Test
	public void marksCancelledShadowSnapshotAsIncomplete() throws Exception {
		RoadCrewObfSegmentLoader.ShadowSnapshot snapshot = RoadCrewObfSegmentLoader.analyze(
				new BinaryMapIndexReader[0], 43.2141, 27.9147, 250, 1000, () -> true);

		Assert.assertTrue(snapshot.isCancelled());
		Assert.assertFalse(snapshot.isTruncated());
	}

	@Test
	public void rejectsUnboundedShadowLoad() throws Exception {
		try {
			RoadCrewObfSegmentLoader.analyze(new BinaryMapIndexReader[0], 43.2141, 27.9147,
					RoadCrewObfSegmentLoader.MAX_RADIUS_METERS + 1, 1000, null);
			Assert.fail("Expected an invalid shadow-load request");
		} catch (IllegalArgumentException expected) {
			// Expected: shadow analysis must remain spatially bounded.
		}
	}

	@Test
	public void analyzesSegmentsFromRealBulgariaObfWhenProvided() throws Exception {
		String path = System.getenv("ROADCREW_TEST_OBF");
		File map = path == null ? null : new File(path);
		Assume.assumeTrue("Set ROADCREW_TEST_OBF to run the real-map integration test",
				map != null && map.isFile());

		try (RandomAccessFile raf = new RandomAccessFile(map, "r")) {
			BinaryMapIndexReader reader = new BinaryMapIndexReader(raf, map);
			try {
				RoadCrewObfSegmentLoader.ShadowSnapshot snapshot = RoadCrewObfSegmentLoader.analyze(
						new BinaryMapIndexReader[]{reader}, 43.2141, 27.9147, 250, 20_000, null);

				Assert.assertEquals(1, snapshot.getReaderCount());
				Assert.assertFalse(snapshot.isTruncated());
				Assert.assertFalse(snapshot.isCancelled());
				Assert.assertTrue(snapshot.getRouteObjectCount() > 0);
				Assert.assertTrue(snapshot.getOsmWayCount() > 0);
				Assert.assertTrue(snapshot.getSegments().size() > 0);
				System.out.printf("RoadCrew Bulgaria OBF shadow: objects=%d, osmWays=%d, segments=%d, "
							+ "loadMs=%d, analysisMs=%d%n",
						snapshot.getRouteObjectCount(), snapshot.getOsmWayCount(), snapshot.getSegments().size(),
						snapshot.getLoadMillis(), snapshot.getAnalysisMillis());
			} finally {
				reader.close();
			}
		}
	}
}
