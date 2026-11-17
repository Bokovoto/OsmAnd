package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
public class RoadCrewObfSegmentLoaderTest {
	@Test public void metadataFilterRetainsNearbyBorderAndBaseRoadsWithoutReadingTheFile() throws Exception {
		File file = File.createTempFile("roadcrew-coverage", ".obf");
		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			BinaryMapIndexReader reader = new BinaryMapIndexReader(raf, file, false);
			RouteRegion region = new RouteRegion();
			reader.getRoutingIndexes().add(region);
			RouteSubregion subregion = new RouteSubregion(region);
			subregion.left = MapUtils.get31TileNumberX(27.9);
			subregion.right = MapUtils.get31TileNumberX(28);
			subregion.top = MapUtils.get31TileNumberY(43.3);
			subregion.bottom = MapUtils.get31TileNumberY(43.2);
			region.getSubregions().add(subregion);
			Assert.assertTrue(RoadCrewObfSegmentLoader.containsRouteDataNear(reader, 43.25, 27.95, 900));
			Assert.assertFalse(RoadCrewObfSegmentLoader.containsRouteDataNear(reader, 42.7, 23.3, 900));
			Assert.assertTrue(RoadCrewObfSegmentLoader.containsRouteDataInBounds(reader,
					subregion.right, subregion.top, subregion.right + 1, subregion.bottom));
			region.getSubregions().clear();
			region.getBaseSubregions().add(subregion);
			Assert.assertTrue(RoadCrewObfSegmentLoader.containsRouteDataNear(reader, 43.25, 27.95, 900));
			Assert.assertTrue(RoadCrewObfSegmentLoader.containsRouteDataInBounds(reader, 200, 0, 100, 100));
			Assert.assertFalse(RoadCrewObfSegmentLoader.containsRouteDataNear(null, 43.25, 27.95, 900));
			Assert.assertEquals("metadata must not seek or read the OBF", 0, raf.getFilePointer());
		} finally {
			Assert.assertTrue(file.delete());
		}
	}

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
