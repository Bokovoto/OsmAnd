package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The map side of ROADMAP 330. Galin: "if between the point where GPS
 * disappears and the point where it reappears there is no branch, count it as
 * driven". A branch is any other road a vehicle could take; a way that simply
 * goes on as the next one - a tunnel drawn as its own way - is not.
 *
 * Roads run east along latitude 43, where 0.001 degrees of longitude is about
 * 81 metres.
 */
public class RoadCrewRoadTopologyTest {

	private static final double LAT = 43.0;

	private static RouteDataObject road(long osmWayId, String highway, double[]... points) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		region.initRouteEncodingRule(1, "highway", highway);
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[] {1};
		road.pointsX = new int[points.length];
		road.pointsY = new int[points.length];
		for (int index = 0; index < points.length; index++) {
			road.pointsY[index] = MapUtils.get31TileNumberY(points[index][0]);
			road.pointsX[index] = MapUtils.get31TileNumberX(points[index][1]);
		}
		return road;
	}

	/** Straight east along LAT, one point every 0.0025 degrees. */
	private static RouteDataObject east(long osmWayId, double fromLongitude, double toLongitude) {
		List<double[]> points = new ArrayList<>();
		int count = (int) Math.round(Math.abs(toLongitude - fromLongitude) / 0.0025);
		for (int index = 0; index <= count; index++) {
			points.add(new double[] {LAT, fromLongitude + (toLongitude - fromLongitude) * index / count});
		}
		return road(osmWayId, "primary", points.toArray(new double[0][]));
	}

	private static RoadCrewRoadTopology map(RouteDataObject... roads) {
		RoadCrewRoadTopology topology = new RoadCrewRoadTopology(null);
		topology.replace(Arrays.asList(roads), LAT, 27.015, 5_000);
		return topology;
	}

	private static double length(RouteDataObject road) {
		return RoadCrewRoadTopology.measures(road)[road.getPointsLength() - 1];
	}

	@Test
	public void waysMeetOnlyAtASharedNodeWithEveryPositionNearIt() {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject b = road(2, "residential", new double[] {LAT, 27.010}, new double[] {43.010, 27.010});
		RouteDataObject parallel = road(3, "residential", new double[] {43.0003, 27.000},
				new double[] {43.0003, 27.010});
		List<double[]> near = Arrays.asList(new double[] {LAT, 27.0096}, new double[] {LAT, 27.0100},
				new double[] {43.0004, 27.0100});
		List<double[]> wandering = new ArrayList<>(near);
		wandering.add(new double[] {LAT, 27.0085});

		Assert.assertTrue(RoadCrewRoadTopology.meetAt(a, b, near, 60));
		Assert.assertFalse("one position 120 m from the junction",
				RoadCrewRoadTopology.meetAt(a, b, wandering, 60));
		Assert.assertFalse("parallel roads share no node",
				RoadCrewRoadTopology.meetAt(a, parallel, near, 60));
		Assert.assertFalse(RoadCrewRoadTopology.meetAt(a, b, Collections.emptyList(), 60));
		Assert.assertFalse(RoadCrewRoadTopology.meetAt(a, b, null, 60));
	}

	@Test
	public void theSharedNodeIsFoundOnBothWaysAtTheEndOrInTheMiddle() {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject b = road(2, "residential", new double[] {LAT, 27.010}, new double[] {43.010, 27.010});
		RouteDataObject across = road(3, "residential", new double[] {42.999, 27.005},
				new double[] {LAT, 27.005}, new double[] {43.001, 27.005});
		List<double[]> atTheEnd = Arrays.asList(new double[] {LAT, 27.0096}, new double[] {43.0004, 27.0100});
		List<double[]> inTheMiddle = Arrays.asList(new double[] {LAT, 27.0047}, new double[] {43.0003, 27.0050});

		Assert.assertArrayEquals(new int[] {4, 0}, RoadCrewRoadTopology.sharedNode(a, b, atTheEnd, 60));
		Assert.assertArrayEquals("a crossing in the middle of A is A's third point, not its end",
				new int[] {2, 1}, RoadCrewRoadTopology.sharedNode(a, across, inTheMiddle, 60));
		Assert.assertNull("positions far from any shared node",
				RoadCrewRoadTopology.sharedNode(a, across, atTheEnd, 60));
		Assert.assertEquals("measured on A's own points", length(a) / 2,
				RoadCrewRoadTopology.measures(a)[2], 1);
	}

	@Test
	public void theSameWayWithNothingJoiningItIsProven() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RoadCrewRoadTopology topology = map(a);

		Assert.assertEquals(Collections.emptyList(), topology.withoutBranch(a, true, 100, a, true, 700));
		Assert.assertNull("the other way round is not the same drive",
				topology.withoutBranch(a, true, 100, a, false, 700));
	}

	@Test
	public void aSideRoadBetweenTheTwoPointsIsABranch() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		// At a's third point, about 407 m along it.
		RouteDataObject side = road(2, "service", new double[] {LAT, 27.005}, new double[] {43.002, 27.005});
		RoadCrewRoadTopology topology = map(a, side);

		Assert.assertNull(topology.withoutBranch(a, true, 100, a, true, 700));
		Assert.assertEquals("a side road beyond the reappearance does not matter",
				Collections.emptyList(), topology.withoutBranch(a, true, 450, a, true, 700));
		Assert.assertNull("driven backwards past it is the same question",
				topology.withoutBranch(a, false, 700, a, false, 100));
	}

	@Test
	public void aFootwayIsNotABranchButATrackIs() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject footway = road(2, "footway", new double[] {LAT, 27.005}, new double[] {43.002, 27.005});
		RouteDataObject track = road(3, "track", new double[] {LAT, 27.0025}, new double[] {43.002, 27.0025});

		Assert.assertEquals(Collections.emptyList(),
				map(a, footway).withoutBranch(a, true, 100, a, true, 700));
		Assert.assertNull(map(a, track).withoutBranch(a, true, 100, a, true, 700));
	}

	@Test
	public void aTunnelDrawnAsItsOwnWayIsCrossed() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject tunnel = east(2, 27.010, 27.020);
		RouteDataObject c = east(3, 27.020, 27.030);
		RoadCrewRoadTopology topology = map(a, tunnel, c);

		List<RoadCrewRoadTopology.Step> steps = topology.withoutBranch(a, true, 600, c, true, 200);
		Assert.assertNotNull(steps);
		Assert.assertEquals(1, steps.size());
		Assert.assertEquals(2, steps.get(0).road.getId() >> 6);
		Assert.assertTrue(steps.get(0).forward);

		Assert.assertEquals("and back the other way", 1,
				topology.withoutBranch(c, false, 200, a, false, 600).size());
		Assert.assertFalse(topology.withoutBranch(c, false, 200, a, false, 600).get(0).forward);
	}

	@Test
	public void aTunnelDrawnAgainstTheTrafficIsCrossedBackwards() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject tunnel = east(2, 27.020, 27.010);
		RouteDataObject c = east(3, 27.020, 27.030);

		List<RoadCrewRoadTopology.Step> steps = map(a, tunnel, c).withoutBranch(a, true, 600, c, true, 200);
		Assert.assertNotNull(steps);
		Assert.assertFalse(steps.get(0).forward);
	}

	@Test
	public void aRoadJoiningAtAPortalIsABranch() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject tunnel = east(2, 27.010, 27.020);
		RouteDataObject c = east(3, 27.020, 27.030);
		RouteDataObject side = road(4, "unclassified", new double[] {LAT, 27.020}, new double[] {43.003, 27.020});

		Assert.assertNull(map(a, tunnel, c, side).withoutBranch(a, true, 600, c, true, 200));
	}

	@Test
	public void aRoadCrossingOnTheSameLevelIsABranch() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject tunnel = east(2, 27.010, 27.020);
		RouteDataObject c = east(3, 27.020, 27.030);
		// Shares the tunnel's middle node: a crossroads, not a bridge over it.
		RouteDataObject crossing = road(4, "tertiary", new double[] {42.998, 27.015},
				new double[] {LAT, 27.015}, new double[] {43.002, 27.015});
		// Passes over without a shared node: another layer, not a branch.
		RouteDataObject bridge = road(5, "tertiary", new double[] {42.998, 27.0138},
				new double[] {43.002, 27.0138});

		Assert.assertNull(map(a, tunnel, c, crossing).withoutBranch(a, true, 600, c, true, 200));
		Assert.assertNotNull(map(a, tunnel, c, bridge).withoutBranch(a, true, 600, c, true, 200));
	}

	@Test
	public void aDeadEndOrAWrongDirectionIsNotProven() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject c = east(3, 27.020, 27.030);
		RouteDataObject tunnel = east(2, 27.010, 27.020);

		Assert.assertNull("nothing continues a", map(a, c).withoutBranch(a, true, 600, c, true, 200));
		Assert.assertNull(map(a, tunnel, c).withoutBranch(a, true, 600, c, false, 200));
		Assert.assertNull("the reappearance lies behind the entry",
				map(a, tunnel, c).withoutBranch(a, false, 600, c, true, 200));
	}

	@Test
	public void aBranchOnTheLastWayBeforeTheReappearanceCounts() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject c = east(3, 27.010, 27.020);
		RouteDataObject side = road(4, "service", new double[] {LAT, 27.0125}, new double[] {43.002, 27.0125});
		RoadCrewRoadTopology topology = map(a, c, side);

		Assert.assertNull(topology.withoutBranch(a, true, 600, c, true, 300));
		Assert.assertEquals("reappearing before it", Collections.emptyList(),
				topology.withoutBranch(a, true, 600, c, true, 150));
	}

	@Test
	public void aClosedWayIsNeverWalked() throws IOException {
		RouteDataObject ring = road(9, "primary", new double[] {LAT, 27.000}, new double[] {43.001, 27.001},
				new double[] {LAT, 27.002}, new double[] {LAT, 27.000});
		Assert.assertNull(map(ring).withoutBranch(ring, true, 10, ring, true, 100));
	}

	@Test
	public void roadsBeyondWhatIsInMemoryAreLoadedAndAFailedLoadProvesNothing() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject tunnel = east(2, 27.010, 27.020);
		RouteDataObject c = east(3, 27.020, 27.030);
		List<double[]> asked = new ArrayList<>();
		RoadCrewRoadTopology topology = new RoadCrewRoadTopology((latitude, longitude, radius) -> {
			asked.add(new double[] {latitude, longitude, radius});
			return Arrays.asList(a, tunnel, c);
		});
		// Only the first few hundred metres of a are in memory.
		topology.replace(Collections.singletonList(a), LAT, 27.003, 300);

		Assert.assertNotNull(topology.withoutBranch(a, true, 200, c, true, 200));
		Assert.assertFalse(asked.isEmpty());

		RoadCrewRoadTopology failing = new RoadCrewRoadTopology((latitude, longitude, radius) -> null);
		failing.replace(Collections.singletonList(a), LAT, 27.003, 300);
		Assert.assertNull(failing.withoutBranch(a, true, 200, c, true, 200));

		RoadCrewRoadTopology nothingLoaded = new RoadCrewRoadTopology(null);
		nothingLoaded.replace(Collections.singletonList(a), LAT, 27.003, 300);
		Assert.assertNull(nothingLoaded.withoutBranch(a, true, 200, c, true, 200));
	}

	@Test
	public void theWayOnWithoutAnotherLoadOnceItIsInMemory() throws IOException {
		RouteDataObject a = east(1, 27.000, 27.010);
		RouteDataObject tunnel = east(2, 27.010, 27.020);
		RouteDataObject c = east(3, 27.020, 27.030);
		int[] loads = {0};
		RoadCrewRoadTopology topology = new RoadCrewRoadTopology((latitude, longitude, radius) -> {
			loads[0]++;
			return Arrays.asList(a, tunnel, c);
		});
		topology.replace(Arrays.asList(a, tunnel, c), LAT, 27.015, 5_000);

		Assert.assertNotNull(topology.withoutBranch(a, true, 200, c, true, 200));
		Assert.assertEquals(0, loads[0]);
		Assert.assertTrue(length(tunnel) > 800);
	}
}
