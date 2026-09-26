package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ROADMAP 330 end to end on the phone: real matches over synthetic roads, and
 * what reaches the wire - each observation's place in the course, whether it
 * is proven to continue the one before, and whether it was crossed without a
 * fix. Roads run east along latitude 43.
 */
public class RoadCrewProvenChainPipelineTest {

	private static final double LAT = 43.0;

	private static RouteDataObject east(long osmWayId, String highway, double from, double to) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		region.initRouteEncodingRule(1, "highway", highway);
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[] {1};
		int points = 5;
		road.pointsX = new int[points];
		road.pointsY = new int[points];
		for (int index = 0; index < points; index++) {
			road.pointsX[index] = MapUtils.get31TileNumberX(from + (to - from) * index / (points - 1.0));
			road.pointsY[index] = MapUtils.get31TileNumberY(LAT);
		}
		return road;
	}

	private static RouteDataObject north(long osmWayId, double longitude) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		region.initRouteEncodingRule(1, "highway", "service");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[] {1};
		road.pointsX = new int[] {MapUtils.get31TileNumberX(longitude), MapUtils.get31TileNumberX(longitude)};
		road.pointsY = new int[] {MapUtils.get31TileNumberY(LAT), MapUtils.get31TileNumberY(43.003)};
		return road;
	}

	private final List<RoadCrewDirectObservation> produced = new ArrayList<>();
	private long time = 1_757_000_000_000L;
	private long sequence;

	private RoadCrewDirectPipeline pipeline(List<RouteDataObject> loaded) {
		RoadCrewDirectPipeline pipeline = new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.PROVEN_330, passage -> { }, null);
		pipeline.setObservationSink(produced::addAll);
		pipeline.setMapVersion("test.obf");
		pipeline.replaceRoads(loaded, LAT, 27.015, 5_000);
		return pipeline;
	}

	/** Fixes every {@code step} degrees east, one a second, matched over {@code loaded}. */
	private void drive(RoadCrewDirectPipeline pipeline, List<RouteDataObject> loaded,
			double from, double to, double step) {
		RoadCrewSegmentMatcher.PreparedSegments segments = RoadCrewSegmentMatcher.prepare(loaded);
		for (double longitude = from; longitude <= to + 1e-9; longitude += step) {
			time += 1_000;
			sequence++;
			RoadCrewSegmentMatcher.GpsFix fix = new RoadCrewSegmentMatcher.GpsFix(LAT, longitude, 5, 16, 90);
			RoadCrewSegmentMatcher.MatchResult match = segments.match(fix);
			RouteDataObject road = null;
			if (match.isMatched() && match.getSegment() != null) {
				for (RouteDataObject candidate : loaded) {
					if (candidate.getId() == match.getSegment().getRoadId()) {
						road = candidate;
					}
				}
			}
			pipeline.accept(fix, match, road, time, sequence);
		}
	}

	private RoadCrewDirectObservation on(long osmWayId) {
		RoadCrewDirectObservation found = null;
		for (RoadCrewDirectObservation observation : produced) {
			if (observation.osmWayId == osmWayId) {
				Assert.assertNull("one observation per way here", found);
				found = observation;
			}
		}
		Assert.assertNotNull("nothing recorded on way " + osmWayId, found);
		return found;
	}

	private void assertNumberedInOrder() {
		for (int index = 0; index < produced.size(); index++) {
			Assert.assertEquals(index, produced.get(index).passageIndex);
		}
	}

	@Test
	public void aTurnSeenByGpsWhereTheWaysMeetIsNumberedAndJoined() {
		List<RouteDataObject> loaded = Arrays.asList(
				east(1, "primary", 27.000, 27.010), east(2, "primary", 27.010, 27.020));
		RoadCrewDirectPipeline pipeline = pipeline(loaded);
		drive(pipeline, loaded, 27.0002, 27.0180, 0.0002);
		pipeline.flush();

		Assert.assertEquals(2, produced.size());
		assertNumberedInOrder();
		Assert.assertFalse(on(1).joinsPrevious);
		Assert.assertTrue(on(2).joinsPrevious);
		Assert.assertFalse(on(2).bridged);
	}

	@Test
	public void aTunnelWithoutAFixIsRecordedFromEndToEnd() {
		RouteDataObject a = east(1, "primary", 27.000, 27.010);
		RouteDataObject tunnel = east(2, "primary", 27.010, 27.020);
		RouteDataObject c = east(3, "primary", 27.020, 27.030);
		List<RouteDataObject> loaded = Arrays.asList(a, tunnel, c);
		RoadCrewDirectPipeline pipeline = pipeline(loaded);
		drive(pipeline, loaded, 27.0002, 27.0090, 0.0002);
		time += 60_000;
		drive(pipeline, loaded, 27.0210, 27.0280, 0.0002);
		pipeline.flush();

		Assert.assertEquals(3, produced.size());
		assertNumberedInOrder();
		RoadCrewDirectObservation inTunnel = on(2);
		Assert.assertTrue(inTunnel.bridged);
		Assert.assertTrue(inTunnel.joinsPrevious);
		Assert.assertEquals(0, inTunnel.fixCount);
		Assert.assertEquals(0, inTunnel.fromMeasureMeters, 0.01);
		Assert.assertEquals(RoadCrewRoadTopology.measures(tunnel)[4], inTunnel.toMeasureMeters, 0.5);
		Assert.assertTrue(on(3).joinsPrevious);
		Assert.assertEquals("the way into the tunnel, driven to its end",
				RoadCrewRoadTopology.measures(a)[4], on(1).toMeasureMeters, 0.5);
		Assert.assertEquals("the way out, driven from its start", 0, on(3).fromMeasureMeters, 0.5);
		Assert.assertSame("its shape can still be sent", tunnel, pipeline.roadForOsmWay(2));
	}

	@Test
	public void aDisappearanceOverABranchRecordsOnlyWhatTheFixesProved() {
		RouteDataObject a = east(1, "primary", 27.000, 27.010);
		RouteDataObject tunnel = east(2, "primary", 27.010, 27.020);
		RouteDataObject c = east(3, "primary", 27.020, 27.030);
		RouteDataObject side = north(4, 27.015);
		List<RouteDataObject> loaded = Arrays.asList(a, tunnel, c, side);
		RoadCrewDirectPipeline pipeline = pipeline(loaded);
		drive(pipeline, loaded, 27.0002, 27.0090, 0.0002);
		time += 60_000;
		drive(pipeline, loaded, 27.0210, 27.0280, 0.0002);
		pipeline.flush();

		Assert.assertEquals(2, produced.size());
		assertNumberedInOrder();
		Assert.assertFalse(on(3).joinsPrevious);
		Assert.assertTrue("a ends at its last fix", on(1).toMeasureMeters < 740);
		Assert.assertTrue("c starts at its first fix", on(3).fromMeasureMeters > 70);
	}

	@Test
	public void aPassageTooShortToSendBreaksTheChain() {
		// a is 40 m; the truck is matched on it for half a metre, then turns onto b.
		RouteDataObject a = east(1, "primary", 27.0000, 27.0005);
		RouteDataObject b = east(2, "primary", 27.0005, 27.0100);
		List<RouteDataObject> loaded = Arrays.asList(a, b);
		RoadCrewDirectPipeline pipeline = pipeline(loaded);
		drive(pipeline, loaded, 27.000400, 27.000406, 0.000006);
		drive(pipeline, loaded, 27.0006, 27.0030, 0.0002);
		pipeline.flush();

		Assert.assertEquals(1, produced.size());
		Assert.assertEquals(2, produced.get(0).osmWayId);
		Assert.assertFalse("the half metre on a was never sent", produced.get(0).joinsPrevious);
	}
}
