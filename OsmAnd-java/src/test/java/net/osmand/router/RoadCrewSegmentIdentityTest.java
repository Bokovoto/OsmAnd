package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RoadCrewSegmentIdentityTest {

	private static final long OSM_WAY_ID = 987654321L;

	@Test
	public void createsDirectionSensitiveKeysAndResolvesExactIndexes() {
		RouteDataObject road = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(43.0010, 27.0010), point(43.0020, 27.0020));

		RoadCrewSegmentIdentity.SegmentKey forward = RoadCrewSegmentIdentity.create(road, 0, 2);
		RoadCrewSegmentIdentity.SegmentKey backward = RoadCrewSegmentIdentity.create(road, 2, 0);

		Assert.assertNotEquals(forward.getCanonicalId(), backward.getCanonicalId());
		Assert.assertNotEquals(forward.getGeometryFingerprint(), backward.getGeometryFingerprint());
		RoadCrewSegmentIdentity.Resolution resolved = RoadCrewSegmentIdentity.resolve(forward,
				Collections.singletonList(road));
		Assert.assertEquals(RoadCrewSegmentIdentity.Status.EXACT, resolved.getStatus());
		Assert.assertEquals(road.getId(), resolved.getRoadId());
		Assert.assertEquals(0, resolved.getStartPointIndex());
		Assert.assertEquals(2, resolved.getEndPointIndex());
	}

	@Test
	public void remapsWhenMapAddsAnIntermediateGeometryPoint() {
		RouteDataObject original = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(43.0010, 27.0010), point(43.0020, 27.0020));
		RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.create(original, 0, 2);
		RouteDataObject updated = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(43.0005, 27.0005),
				point(43.0010, 27.0010), point(43.0020, 27.0020));

		RoadCrewSegmentIdentity.Resolution resolved = RoadCrewSegmentIdentity.resolve(key,
				Collections.singletonList(updated));

		Assert.assertEquals(RoadCrewSegmentIdentity.Status.REMAPPED, resolved.getStatus());
		Assert.assertEquals(0, resolved.getStartPointIndex());
		Assert.assertEquals(3, resolved.getEndPointIndex());
	}

	@Test
	public void failsClosedWhenTwoChangedGeometriesAreEquallyPlausible() {
		RouteDataObject original = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(43.0000, 27.0050), point(43.0000, 27.0100));
		RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.create(original, 0, 2);
		RouteDataObject north = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(43.00002, 27.0050), point(43.0000, 27.0100));
		RouteDataObject south = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(42.99998, 27.0050), point(43.0000, 27.0100));

		RoadCrewSegmentIdentity.Resolution resolved = RoadCrewSegmentIdentity.resolve(key,
				Arrays.asList(north, south));

		Assert.assertEquals(RoadCrewSegmentIdentity.Status.AMBIGUOUS, resolved.getStatus());
		Assert.assertFalse(resolved.isResolved());
		Assert.assertEquals(2, resolved.getCandidateCount());
	}

	@Test
	public void ignoresDifferentOsmWayAndDistantEndpoints() {
		RouteDataObject original = road(OSM_WAY_ID, "Bulgaria",
				point(43.0000, 27.0000), point(43.0010, 27.0010));
		RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.create(original, 0, 1);
		RouteDataObject differentWay = road(OSM_WAY_ID + 1, "Bulgaria",
				point(43.0000, 27.0000), point(43.0010, 27.0010));
		RouteDataObject distant = road(OSM_WAY_ID, "Bulgaria",
				point(43.0100, 27.0100), point(43.0110, 27.0110));

		RoadCrewSegmentIdentity.Resolution resolved = RoadCrewSegmentIdentity.resolve(key,
				Arrays.asList(differentWay, distant));

		Assert.assertEquals(RoadCrewSegmentIdentity.Status.NOT_FOUND, resolved.getStatus());
		Assert.assertFalse(resolved.isResolved());
	}

	@Test
	public void splitsAtSharedRoadGraphPointAndKeepsBothDirections() {
		RouteDataObject main = road(1001, "Bulgaria",
				point(43.0000, 27.0000), point(43.0000, 27.0010),
				point(43.0000, 27.0020), point(43.0000, 27.0030));
		RouteDataObject cross = road(1002, "Bulgaria",
				point(42.9990, 27.0020), point(43.0000, 27.0020), point(43.0010, 27.0020));

		List<RoadCrewSegmentIdentity.SegmentBinding> bindings =
				RoadCrewSegmentIdentity.buildLogicalSegments(Arrays.asList(main, cross));
		int mainBindings = 0;
		boolean firstForward = false;
		boolean firstBackward = false;
		for (RoadCrewSegmentIdentity.SegmentBinding binding : bindings) {
			if (binding.getRoadId() == main.getId()) {
				mainBindings++;
				firstForward |= binding.getStartPointIndex() == 0 && binding.getEndPointIndex() == 2;
				firstBackward |= binding.getStartPointIndex() == 2 && binding.getEndPointIndex() == 0;
			}
		}

		Assert.assertEquals(4, mainBindings);
		Assert.assertTrue(firstForward);
		Assert.assertTrue(firstBackward);
	}

	@Test
	public void createsOnlyPermittedDirectionForOneWayRoad() {
		RouteDataObject road = road(1003, "Bulgaria",
				point(43.0000, 27.0000), point(43.0010, 27.0010));
		road.region.initRouteEncodingRule(1, "oneway", "yes");
		road.types = new int[]{1};

		List<RoadCrewSegmentIdentity.SegmentBinding> bindings =
				RoadCrewSegmentIdentity.buildLogicalSegments(Collections.singletonList(road));

		Assert.assertEquals(1, bindings.size());
		Assert.assertEquals(0, bindings.get(0).getStartPointIndex());
		Assert.assertEquals(1, bindings.get(0).getEndPointIndex());
	}

	private static RouteDataObject road(long osmWayId, String regionName, double[]... points) {
		RouteRegion region = new RouteRegion();
		region.setName(regionName);
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
}
