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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RoadCrewPassageDetectorTest {

	@Test
	public void confirmsOnlyAfterConsistentForwardMovement() {
		RoadCrewSegmentMatcher.PreparedSegments prepared = preparedRoad(2001, 43.0000);
		RoadCrewPassageDetector detector = new RoadCrewPassageDetector();

		RoadCrewPassageDetector.DetectionResult first = detector.accept(match(prepared, 43.0000, 27.0020), 1_000);
		RoadCrewPassageDetector.DetectionResult second = detector.accept(match(prepared, 43.0000, 27.0022), 2_000);
		RoadCrewPassageDetector.DetectionResult third = detector.accept(match(prepared, 43.0000, 27.0024), 3_000);

		Assert.assertEquals(RoadCrewPassageDetector.Status.TRACKING, first.getStatus());
		Assert.assertEquals(RoadCrewPassageDetector.Status.TRACKING, second.getStatus());
		Assert.assertTrue(third.isConfirmed());
		Assert.assertNotNull(third.getEvidence());
		Assert.assertEquals(3, third.getEvidence().getFixCount());
		Assert.assertEquals(2_000, third.getEvidence().getDurationMillis());
		Assert.assertTrue(third.getEvidence().getForwardMovementMeters() >= 20);
		Assert.assertTrue(third.getEvidence().getMaximumDistanceMeters() < 1);
	}

	@Test
	public void emitsOnlyOneEvidenceRecordPerTrackedSegment() {
		RoadCrewSegmentMatcher.PreparedSegments prepared = preparedRoad(2002, 43.0000);
		RoadCrewPassageDetector detector = new RoadCrewPassageDetector();
		detector.accept(match(prepared, 43.0000, 27.0020), 1_000);
		detector.accept(match(prepared, 43.0000, 27.0022), 2_000);
		Assert.assertTrue(detector.accept(match(prepared, 43.0000, 27.0024), 3_000).isConfirmed());

		RoadCrewPassageDetector.DetectionResult duplicate =
				detector.accept(match(prepared, 43.0000, 27.0026), 4_000);

		Assert.assertEquals(RoadCrewPassageDetector.Status.ALREADY_CONFIRMED, duplicate.getStatus());
		Assert.assertNull(duplicate.getEvidence());
	}

	@Test
	public void resetsOnBacktrackTimeGapAndImplausibleJump() {
		RoadCrewSegmentMatcher.PreparedSegments prepared = preparedRoad(2003, 43.0000);
		RoadCrewPassageDetector detector = new RoadCrewPassageDetector();

		detector.accept(match(prepared, 43.0000, 27.0050), 1_000);
		Assert.assertEquals(RoadCrewPassageDetector.Status.RESET_BACKTRACK,
				detector.accept(match(prepared, 43.0000, 27.0040), 2_000).getStatus());

		Assert.assertEquals(RoadCrewPassageDetector.Status.RESET_TIME_GAP,
				detector.accept(match(prepared, 43.0000, 27.0042), 20_000).getStatus());

		Assert.assertEquals(RoadCrewPassageDetector.Status.RESET_IMPLAUSIBLE_JUMP,
				detector.accept(match(prepared, 43.0000, 27.0090), 21_000).getStatus());
	}

	@Test
	public void resetsWhenMatchedSegmentChangesOrMatchIsLost() {
		RouteDataObject firstRoad = road(2004, 43.0000);
		RouteDataObject secondRoad = road(2005, 43.0010);
		RoadCrewSegmentMatcher.PreparedSegments prepared =
				RoadCrewSegmentMatcher.prepare(Arrays.asList(firstRoad, secondRoad));
		RoadCrewPassageDetector detector = new RoadCrewPassageDetector();

		detector.accept(match(prepared, 43.0000, 27.0020), 1_000);
		Assert.assertEquals(RoadCrewPassageDetector.Status.RESET_SEGMENT_CHANGED,
				detector.accept(match(prepared, 43.0010, 27.0020), 2_000).getStatus());

		RoadCrewSegmentMatcher.MatchResult wrongHeading = prepared.match(
				new RoadCrewSegmentMatcher.GpsFix(43.0010, 27.0022, 3, 15, 0));
		Assert.assertFalse(wrongHeading.isMatched());
		Assert.assertEquals(RoadCrewPassageDetector.Status.NO_MATCH,
				detector.accept(wrongHeading, 3_000).getStatus());
		Assert.assertFalse(detector.isTracking());
	}

	@Test
	public void rejectsInvalidMonotonicTime() {
		RoadCrewSegmentMatcher.PreparedSegments prepared = preparedRoad(2006, 43.0000);
		RoadCrewPassageDetector detector = new RoadCrewPassageDetector();

		Assert.assertEquals(RoadCrewPassageDetector.Status.INVALID_TIME,
				detector.accept(match(prepared, 43.0000, 27.0020), -1).getStatus());
		Assert.assertFalse(detector.isTracking());
	}

	@Test
	public void confirmsPassageOnRealBulgariaObfWhenProvided() throws Exception {
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
				Map<Long, RouteDataObject> roadsById = new LinkedHashMap<>();
				for (RouteDataObject road : loaded.getRouteObjects()) {
					roadsById.putIfAbsent(road.getId(), road);
				}

				RoadCrewPassageDetector.PassageEvidence evidence = null;
				for (RoadCrewSegmentIdentity.SegmentBinding binding : bindings) {
					if (binding.getKey().getLengthMeters() < 50 || binding.getKey().getLengthMeters() > 500) {
						continue;
					}
					RouteDataObject road = roadsById.get(binding.getRoadId());
					PolylineSample first = sample(road, binding, 0.20);
					PolylineSample second = sample(road, binding, 0.50);
					PolylineSample third = sample(road, binding, 0.80);
					if (first == null || second == null || third == null) {
						continue;
					}
					RoadCrewSegmentMatcher.MatchResult firstMatch = prepared.match(first.toFix());
					RoadCrewSegmentMatcher.MatchResult secondMatch = prepared.match(second.toFix());
					RoadCrewSegmentMatcher.MatchResult thirdMatch = prepared.match(third.toFix());
					if (!matches(binding, firstMatch) || !matches(binding, secondMatch) || !matches(binding, thirdMatch)) {
						continue;
					}
					RoadCrewPassageDetector detector = new RoadCrewPassageDetector();
					detector.accept(firstMatch, 0);
					detector.accept(secondMatch, 5_000);
					RoadCrewPassageDetector.DetectionResult result = detector.accept(thirdMatch, 10_000);
					if (result.isConfirmed()) {
						evidence = result.getEvidence();
						break;
					}
				}

				Assert.assertNotNull("Expected confirmed passage evidence in the Varna test area", evidence);
				System.out.printf("RoadCrew real-OBF passage: segment=%s, fixes=%d, durationMs=%d, movement=%.2fm%n",
						evidence.getSegmentKey().getCanonicalId(), evidence.getFixCount(),
						evidence.getDurationMillis(), evidence.getForwardMovementMeters());
			} finally {
				reader.close();
			}
		}
	}

	private static boolean matches(RoadCrewSegmentIdentity.SegmentBinding expected,
			RoadCrewSegmentMatcher.MatchResult actual) {
		return actual.isMatched() && actual.getSegment().getKey().getCanonicalId()
				.equals(expected.getKey().getCanonicalId());
	}

	private static RoadCrewSegmentMatcher.PreparedSegments preparedRoad(long osmWayId, double latitude) {
		return RoadCrewSegmentMatcher.prepare(Collections.singletonList(road(osmWayId, latitude)));
	}

	private static RoadCrewSegmentMatcher.MatchResult match(RoadCrewSegmentMatcher.PreparedSegments prepared,
			double latitude, double longitude) {
		RoadCrewSegmentMatcher.MatchResult result = prepared.match(
				new RoadCrewSegmentMatcher.GpsFix(latitude, longitude, 3, 15, 90));
		Assert.assertTrue("Expected a directed segment match", result.isMatched());
		return result;
	}

	private static RouteDataObject road(long osmWayId, double latitude) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[0];
		road.pointsX = new int[]{MapUtils.get31TileNumberX(27.0000), MapUtils.get31TileNumberX(27.0100)};
		road.pointsY = new int[]{MapUtils.get31TileNumberY(latitude), MapUtils.get31TileNumberY(latitude)};
		return road;
	}

	private static PolylineSample sample(RouteDataObject road,
			RoadCrewSegmentIdentity.SegmentBinding binding, double fraction) {
		if (road == null || fraction < 0 || fraction > 1) {
			return null;
		}
		int start = binding.getStartPointIndex();
		int end = binding.getEndPointIndex();
		int step = start < end ? 1 : -1;
		double total = 0;
		for (int index = start; index != end; index += step) {
			total += edgeLength(road, index, index + step);
		}
		if (total <= 0) {
			return null;
		}
		double target = total * fraction;
		double completed = 0;
		for (int index = start; index != end; index += step) {
			int next = index + step;
			double edgeLength = edgeLength(road, index, next);
			if (completed + edgeLength >= target) {
				double edgeFraction = edgeLength == 0 ? 0 : (target - completed) / edgeLength;
				double fromLatitude = latitude(road, index);
				double fromLongitude = longitude(road, index);
				double toLatitude = latitude(road, next);
				double toLongitude = longitude(road, next);
				return new PolylineSample(
						fromLatitude + (toLatitude - fromLatitude) * edgeFraction,
						fromLongitude + (toLongitude - fromLongitude) * edgeFraction,
						bearing(fromLatitude, fromLongitude, toLatitude, toLongitude));
			}
			completed += edgeLength;
		}
		return null;
	}

	private static double edgeLength(RouteDataObject road, int from, int to) {
		return MapUtils.getDistance(latitude(road, from), longitude(road, from),
				latitude(road, to), longitude(road, to));
	}

	private static double latitude(RouteDataObject road, int index) {
		return MapUtils.get31LatitudeY(road.getPoint31YTile(index));
	}

	private static double longitude(RouteDataObject road, int index) {
		return MapUtils.get31LongitudeX(road.getPoint31XTile(index));
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

	private static final class PolylineSample {
		private final double latitude;
		private final double longitude;
		private final double bearing;

		private PolylineSample(double latitude, double longitude, double bearing) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.bearing = bearing;
		}

		private RoadCrewSegmentMatcher.GpsFix toFix() {
			return new RoadCrewSegmentMatcher.GpsFix(latitude, longitude, 3, 15, bearing);
		}
	}
}
