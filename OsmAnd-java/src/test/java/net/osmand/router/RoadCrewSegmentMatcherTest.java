package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RoadCrewSegmentMatcherTest {

	@Test
	public void matchesCorrectDirectionOnTwoWayRoad() {
		RouteDataObject road = road(1001,
				point(43.0000, 27.0000), point(43.0000, 27.0100));
		RoadCrewSegmentMatcher.PreparedSegments prepared =
				RoadCrewSegmentMatcher.prepare(Collections.singletonList(road));

		RoadCrewSegmentMatcher.MatchResult eastbound = prepared.match(
				fix(43.0000, 27.0020, 5, 15, 90));
		RoadCrewSegmentMatcher.MatchResult westbound = prepared.match(
				fix(43.0000, 27.0020, 5, 15, 270));

		Assert.assertTrue(eastbound.isMatched());
		Assert.assertEquals(0, eastbound.getSegment().getStartPointIndex());
		Assert.assertEquals(1, eastbound.getSegment().getEndPointIndex());
		Assert.assertTrue(westbound.isMatched());
		Assert.assertEquals(1, westbound.getSegment().getStartPointIndex());
		Assert.assertEquals(0, westbound.getSegment().getEndPointIndex());
		Assert.assertTrue(eastbound.getProgressMeters() < westbound.getProgressMeters());
		Assert.assertEquals(eastbound.getSegmentLengthMeters(), westbound.getSegmentLengthMeters(), 0.5);
	}

	@Test
	public void rejectsFixesThatCannotProveARealPassage() {
		RouteDataObject road = road(1002,
				point(43.0000, 27.0000), point(43.0000, 27.0100));
		RoadCrewSegmentMatcher.PreparedSegments prepared =
				RoadCrewSegmentMatcher.prepare(Collections.singletonList(road));

		Assert.assertEquals(RoadCrewSegmentMatcher.Status.LOW_SPEED,
				prepared.match(fix(43.0000, 27.0050, 5, 1, 90)).getStatus());
		Assert.assertEquals(RoadCrewSegmentMatcher.Status.POOR_ACCURACY,
				prepared.match(fix(43.0000, 27.0050, 50, 15, 90)).getStatus());
		Assert.assertEquals(RoadCrewSegmentMatcher.Status.NO_NEARBY_SEGMENT,
				prepared.match(fix(43.0010, 27.0050, 5, 15, 90)).getStatus());
		Assert.assertEquals(RoadCrewSegmentMatcher.Status.DIRECTION_MISMATCH,
				prepared.match(fix(43.0000, 27.0050, 5, 15, 0)).getStatus());
	}

	@Test
	public void failsClosedBetweenParallelRoads() {
		RouteDataObject north = road(1003,
				point(43.00005, 27.0000), point(43.00005, 27.0100));
		RouteDataObject south = road(1004,
				point(42.99995, 27.0000), point(42.99995, 27.0100));
		RoadCrewSegmentMatcher.PreparedSegments prepared =
				RoadCrewSegmentMatcher.prepare(Arrays.asList(north, south));

		RoadCrewSegmentMatcher.MatchResult result = prepared.match(
				fix(43.0000, 27.0050, 5, 15, 90));

		Assert.assertEquals(RoadCrewSegmentMatcher.Status.AMBIGUOUS, result.getStatus());
		Assert.assertFalse(result.isMatched());
		Assert.assertNull(result.getSegment());
		Assert.assertEquals(2, result.getDirectionCandidateCount());
	}

	@Test
	public void preparesNoSegmentsFromMissingRoads() {
		RoadCrewSegmentMatcher.PreparedSegments prepared = RoadCrewSegmentMatcher.prepare(null);

		Assert.assertEquals(0, prepared.size());
		Assert.assertEquals(RoadCrewSegmentMatcher.Status.NO_SEGMENTS,
				prepared.match(fix(43.0000, 27.0050, 5, 15, 90)).getStatus());
		Assert.assertEquals(RoadCrewSegmentMatcher.Status.INVALID_FIX,
				prepared.match(fix(Double.NaN, 27.0050, 5, 15, 90)).getStatus());
	}

	@Test
	public void matchesAtLeastOneDirectedSegmentFromRealBulgariaObfWhenProvided() throws Exception {
		String path = System.getenv("ROADCREW_TEST_OBF");
		File map = path == null ? null : new File(path);
		Assume.assumeTrue("Set ROADCREW_TEST_OBF to run the real-map integration test",
				map != null && map.isFile());

		try (RandomAccessFile raf = new RandomAccessFile(map, "r")) {
			BinaryMapIndexReader reader = new BinaryMapIndexReader(raf, map);
			try {
				RoadCrewObfSegmentLoader.LoadResult loaded = RoadCrewObfSegmentLoader.load(
						new BinaryMapIndexReader[]{reader}, 43.2141, 27.9147, 250, 20_000, null);
				RoadCrewSegmentMatcher.PreparedSegments prepared =
						RoadCrewSegmentMatcher.prepare(loaded.getRouteObjects());
				List<RoadCrewSegmentIdentity.SegmentBinding> bindings =
						RoadCrewSegmentIdentity.buildLogicalSegments(loaded.getRouteObjects());
				RoadCrewSegmentMatcher.MatchResult matched = null;
				for (RoadCrewSegmentIdentity.SegmentBinding binding : bindings) {
					RoadCrewSegmentIdentity.SegmentKey key = binding.getKey();
					double latitude = (key.getFromLatitude() + key.getToLatitude()) / 2.0;
					double longitude = (key.getFromLongitude() + key.getToLongitude()) / 2.0;
					double bearing = bearing(key.getFromLatitude(), key.getFromLongitude(),
							key.getToLatitude(), key.getToLongitude());
					RoadCrewSegmentMatcher.MatchResult result = prepared.match(
							fix(latitude, longitude, 3, 15, bearing));
					if (result.isMatched() && result.getSegment().getKey().getCanonicalId()
							.equals(key.getCanonicalId())) {
						matched = result;
						break;
					}
				}

				Assert.assertNotNull("Expected a matchable directed segment in the Varna test area", matched);
				System.out.printf("RoadCrew real-OBF segment match: segment=%s, distance=%.2fm, "
							+ "headingDiff=%.2f, prepared=%d%n",
						matched.getSegment().getKey().getCanonicalId(), matched.getDistanceMeters(),
						matched.getHeadingDifferenceDegrees(), prepared.size());
			} finally {
				reader.close();
			}
		}
	}

	private static RoadCrewSegmentMatcher.GpsFix fix(double latitude, double longitude,
			double accuracy, double speed, double bearing) {
		return new RoadCrewSegmentMatcher.GpsFix(latitude, longitude, accuracy, speed, bearing);
	}

	private static RouteDataObject road(long osmWayId, double[]... points) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[0];
		road.pointsX = new int[points.length];
		road.pointsY = new int[points.length];
		for (int i = 0; i < points.length; i++) {
			road.pointsX[i] = MapUtils.get31TileNumberX(points[i][1]);
			road.pointsY[i] = MapUtils.get31TileNumberY(points[i][0]);
		}
		return road;
	}

	private static double[] point(double latitude, double longitude) {
		return new double[]{latitude, longitude};
	}

	private static double bearing(double fromLatitude, double fromLongitude,
			double toLatitude, double toLongitude) {
		double fromLatRadians = Math.toRadians(fromLatitude);
		double toLatRadians = Math.toRadians(toLatitude);
		double longitudeDelta = Math.toRadians(toLongitude - fromLongitude);
		double y = Math.sin(longitudeDelta) * Math.cos(toLatRadians);
		double x = Math.cos(fromLatRadians) * Math.sin(toLatRadians)
				- Math.sin(fromLatRadians) * Math.cos(toLatRadians) * Math.cos(longitudeDelta);
		return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
	}
}
