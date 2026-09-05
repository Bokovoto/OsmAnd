package net.osmand.router;

import net.osmand.binary.RouteDataObject;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.util.MapUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ROADMAP section 165, checkpoint C: the directed accumulator fed from the very
 * match the legacy segmentation uses.
 *
 * The last test here is the one the whole exercise exists for. It drives the
 * same road twice with a different set of neighbouring roads loaded each time -
 * exactly what the 900 m loading window does on the road - and shows that the
 * old identity changes between the two runs while the new one does not.
 */
public class RoadCrewDirectPipelineTest {

	private static final double LATITUDE = 43.35;
	private static final long WAY = 1037293829L;

	private final List<RoadCrewDirectPassageAccumulator.Passage> passages = new ArrayList<>();

	private RoadCrewDirectPipeline pipeline() {
		return new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, passages::add);
	}

	/** A straight east-west road of the given length in degrees of longitude. */
	private static RouteDataObject road(long osmWayId, double fromLongitude, double toLongitude) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[0];
		road.pointsX = new int[] {
			MapUtils.get31TileNumberX(fromLongitude), MapUtils.get31TileNumberX(toLongitude),
		};
		road.pointsY = new int[] {
			MapUtils.get31TileNumberY(LATITUDE), MapUtils.get31TileNumberY(LATITUDE),
		};
		return road;
	}

	private static RoadCrewSegmentMatcher.GpsFix fix(double longitude) {
		return new RoadCrewSegmentMatcher.GpsFix(LATITUDE, longitude, 3, 15, 90);
	}

	private RoadCrewSegmentMatcher.MatchResult match(RouteDataObject road, double longitude) {
		return RoadCrewSegmentMatcher.match(fix(longitude), Collections.singletonList(road));
	}

	@Test
	public void drivingOneRoadProducesOneDirectedPassage() {
		RouteDataObject road = road(WAY, 26.2000, 26.2200);
		RoadCrewDirectPipeline pipeline = pipeline();
		long time = 1_757_000_000_000L;

		for (double longitude = 26.2020; longitude <= 26.2180; longitude += 0.0020) {
			time += 3000;
			pipeline.accept(fix(longitude), match(road, longitude), road, time);
		}
		pipeline.flush();

		Assert.assertEquals("one road driven through is one passage", 1, passages.size());
		RoadCrewDirectPassageAccumulator.Passage passage = passages.get(0);
		Assert.assertEquals(WAY, passage.wayId);
		Assert.assertEquals(1, passage.spans.size());
		Assert.assertTrue("the passage must cover real ground", passage.progressMeters > 100);
	}

	@Test
	public void anUnmatchedFixIsNotMistakenForAPassage() {
		RoadCrewDirectPipeline pipeline = pipeline();
		pipeline.accept(fix(26.2000), null, null, 1_757_000_000_000L);
		pipeline.flush();

		Assert.assertEquals(0, passages.size());
	}

	@Test
	public void aRoadWithoutUsableGeometryIsSkippedRatherThanThrowing() {
		RouteDataObject broken = road(WAY, 26.2000, 26.2200);
		broken.pointsX = null;
		RoadCrewDirectPipeline pipeline = pipeline();

		pipeline.accept(fix(26.2100), null, broken, 1_757_000_000_000L);
		pipeline.flush();
		Assert.assertEquals(0, passages.size());
	}

	/**
	 * The defect this whole change exists to remove. The legacy identity depends
	 * on which neighbouring roads happen to be loaded, so the same drive yields
	 * different names on different trips. The directed identity depends only on
	 * the road itself.
	 */
	@Test
	public void theSameDriveKeepsItsIdentityWhateverElseIsLoaded() {
		RouteDataObject main = road(WAY, 26.2000, 26.2200);
		RouteDataObject neighbourNorth = road(555001L, 26.2080, 26.2081);
		RouteDataObject neighbourSouth = road(555002L, 26.2140, 26.2141);

		List<RoadCrewDirectPassageAccumulator.Passage> first = driveWith(main,
				Collections.singletonList(neighbourNorth));
		List<RoadCrewDirectPassageAccumulator.Passage> second = driveWith(main,
				java.util.Arrays.asList(neighbourNorth, neighbourSouth));

		Assert.assertEquals(1, first.size());
		Assert.assertEquals(1, second.size());
		Assert.assertEquals("the road is the road, whatever else was in memory",
				first.get(0).wayId, second.get(0).wayId);
		Assert.assertEquals("and so is the direction",
				first.get(0).forward, second.get(0).forward);
		Assert.assertEquals("and so is the stretch covered",
				first.get(0).spans.get(0).fromMeasureMeters,
				second.get(0).spans.get(0).fromMeasureMeters, 0.5);
		Assert.assertEquals(first.get(0).spans.get(0).toMeasureMeters,
				second.get(0).spans.get(0).toMeasureMeters, 0.5);
	}

	private List<RoadCrewDirectPassageAccumulator.Passage> driveWith(
			RouteDataObject main, List<RouteDataObject> alsoLoaded) {
		List<RoadCrewDirectPassageAccumulator.Passage> collected = new ArrayList<>();
		RoadCrewDirectPipeline pipeline = new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, collected::add);
		List<RouteDataObject> loaded = new ArrayList<>(alsoLoaded);
		loaded.add(main);
		long time = 1_757_000_000_000L;
		for (double longitude = 26.2020; longitude <= 26.2180; longitude += 0.0020) {
			time += 3000;
			RoadCrewSegmentMatcher.MatchResult result =
					RoadCrewSegmentMatcher.match(fix(longitude), loaded);
			RouteDataObject matched = null;
			if (result.isMatched() && result.getSegment() != null) {
				for (RouteDataObject road : loaded) {
					if (road.getId() == result.getSegment().getRoadId()) {
						matched = road;
						break;
					}
				}
			}
			pipeline.accept(fix(longitude), result, matched, time);
		}
		pipeline.flush();
		return collected;
	}

	@Test
	public void theLegacyBranchCanBeToldTheDirectionTheMatcherResolved() {
		// The old key carries no direction, and inferring one from the ends of a
		// piece is wrong wherever a road doubles back. Asking the matcher removes
		// that error from the comparison entirely.
		RouteDataObject road = doublingBackRoad();

		String forward = RoadCrewDirectPipeline.canonicalDirection(road, 0, 1);
		String backward = RoadCrewDirectPipeline.canonicalDirection(road, 1, 0);

		Assert.assertNotNull(forward);
		Assert.assertNotNull(backward);
		Assert.assertNotEquals(forward, backward);
		// Whatever the shape of the road, both directions of the same edge are
		// answered consistently.
		Assert.assertEquals(forward, RoadCrewDirectPipeline.canonicalDirection(road, 2, 3));
		Assert.assertEquals(backward, RoadCrewDirectPipeline.canonicalDirection(road, 3, 2));
	}

	@Test
	public void anEndPieceThatRunsTheOtherWayIsStillNamedByTheWayNotByItsOwnShape() {
		// This is the case the coordinate proxy gets wrong: the road as a whole
		// runs east, but its last leg turns back west. Asked about that leg, a
		// proxy would answer "R" while the traversal is "F".
		RouteDataObject road = doublingBackRoad();
		String alongTheWay = RoadCrewDirectPipeline.canonicalDirection(road, 0, 1);

		Assert.assertEquals("the doubling-back leg belongs to the same traversal",
				alongTheWay, RoadCrewDirectPipeline.canonicalDirection(road, 3, 4));
	}

	@Test
	public void anUnusableWayReportsNoDirectionRatherThanGuessing() {
		Assert.assertNull(RoadCrewDirectPipeline.canonicalDirection(null, 0, 1));
		RouteDataObject road = doublingBackRoad();
		Assert.assertNull("the two ends of one point are not a direction",
				RoadCrewDirectPipeline.canonicalDirection(road, 2, 2));
		Assert.assertNull(RoadCrewDirectPipeline.canonicalDirection(road, 0, 99));
		Assert.assertNull(RoadCrewDirectPipeline.canonicalDirection(road, -1, 1));
	}

	@Test
	public void oneOsmWaySplitIntoTwoLoadedObjectsMustNotSilenceThePassage() throws Exception {
		// OsmAnd stores a long way as several RouteDataObjects. They share one
		// OSM id but each carries its own point array, so its measures start at
		// nought again. Identity is per way; the measures were per fragment.
		//
		// Driving across the boundary therefore looks like a jump backwards
		// along the road, the continuity test refuses it, the passage restarts
		// with no progress, and nothing is ever emitted. On the first real drive
		// that was 88 legacy observations against 9 directed ones.
		List<RoadCrewDirectObservation> produced = new ArrayList<>();
		RoadCrewDirectPipeline pipeline = new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, passage -> { });
		pipeline.setObservationSink(produced::addAll);
		pipeline.setMapVersion("test.obf");

		RouteDataObject first = fragment(700123, 27.000, 27.010);
		RouteDataObject second = fragment(700123, 27.010, 27.020);
		Assert.assertEquals("both fragments are the same OSM way",
				net.osmand.binary.ObfConstants.getOsmObjectId(first),
				net.osmand.binary.ObfConstants.getOsmObjectId(second));

		long time = 9_000_000;
		int sequence = 0;
		for (RouteDataObject road : Arrays.asList(first, second)) {
			List<RouteDataObject> loaded = Collections.singletonList(road);
			RoadCrewSegmentMatcher.PreparedSegments segments = RoadCrewSegmentMatcher.prepare(loaded);
			// About 16 m per second - a lorry at 58 km/h, not a rocket. The
			// continuity test rightly refuses anything faster than the config
			// allows, so unrealistic spacing would prove nothing.
			double from = road == first ? 27.0002 : 27.0102;
			for (int step = 0; step < 48; step++) {
				RoadCrewSegmentMatcher.GpsFix fix = new RoadCrewSegmentMatcher.GpsFix(
						43.0, from + step * 0.0002, 5, 16, 90);
				RoadCrewSegmentMatcher.MatchResult match = segments.match(fix);
				pipeline.accept(fix, match, match.isMatched() ? road : null, time, ++sequence);
				time += 1_000;
			}
		}
		pipeline.flush();

		Assert.assertFalse("the drive produced no directed observation at all", produced.isEmpty());
		double covered = 0;
		for (RoadCrewDirectObservation observation : produced) {
			covered += observation.getLengthMeters();
		}
		// The two fragments are about 815 m each; losing one of them entirely is
		// the failure being guarded against.
		Assert.assertTrue("only " + Math.round(covered) + " m of about 1600 m was reported",
				covered > 1200);
	}

	@Test
	public void aPassageEndedByMovingToAnotherRoadMustStillBeReported() throws Exception {
		// The accumulator only closes the old passage once the NEW way has won
		// two consecutive fixes - by which time the pipeline has already moved
		// its "way of the last accepted fix" pointer onto the new way. The guard
		// that compares that pointer to the finished passage then throws the
		// observation away.
		//
		// On the drive of 5 September the accumulator emitted passages of 2 695,
		// 3 671, 5 210 and 4 226 metres and the server received almost none of
		// them: 19 emitted, 13 arrived, 5.9% coverage.
		List<RoadCrewDirectObservation> produced = new ArrayList<>();
		RoadCrewDirectPipeline pipeline = new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, passage -> { });
		pipeline.setObservationSink(produced::addAll);
		pipeline.setMapVersion("test.obf");

		RouteDataObject first = fragment(700501, 27.000, 27.010);
		RouteDataObject second = fragment(700502, 27.010, 27.020);
		List<RouteDataObject> loaded = Arrays.asList(first, second);
		RoadCrewSegmentMatcher.PreparedSegments segments = RoadCrewSegmentMatcher.prepare(loaded);

		long time = 9_000_000;
		int sequence = 0;
		for (int step = 0; step < 96; step++) {
			double longitude = 27.0002 + step * 0.0002;
			RoadCrewSegmentMatcher.GpsFix fix = new RoadCrewSegmentMatcher.GpsFix(
					43.0, longitude, 5, 16, 90);
			RoadCrewSegmentMatcher.MatchResult match = segments.match(fix);
			RouteDataObject road = null;
			if (match.isMatched() && match.getSegment() != null) {
				road = match.getSegment().getRoadId() == first.getId() ? first : second;
			}
			pipeline.accept(fix, match, road, time, ++sequence);
			time += 1_000;
		}
		pipeline.flush();

		long reported = 0;
		for (RoadCrewDirectObservation observation : produced) {
			reported += Math.round(observation.getLengthMeters());
		}
		Assert.assertTrue("the first road was driven end to end and reported nothing;"
				+ " observations=" + produced.size() + " metres=" + reported,
				reported > 1200);
	}

	@Test
	public void everyEmittedPassageBecomesAnObservation() throws Exception {
		// Four roads driven one after another. The accumulator's own count of
		// emitted passages and the number of observations that leave the pipeline
		// must agree - anything else is a passage lost between the two, which is
		// how nineteen emitted became five point nine per cent on the road.
		RoadCrewDiagnostics diagnostics = new RoadCrewDiagnostics();
		List<RoadCrewDirectObservation> produced = new ArrayList<>();
		RoadCrewDirectPipeline pipeline = new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, passage -> { });
		pipeline.setDiagnostics(diagnostics);
		pipeline.setObservationSink(produced::addAll);
		pipeline.setMapVersion("test.obf");

		List<RouteDataObject> loaded = Arrays.asList(
				fragment(700601, 27.000, 27.010), fragment(700602, 27.010, 27.020),
				fragment(700603, 27.020, 27.030), fragment(700604, 27.030, 27.040));
		drive(pipeline, loaded, 27.0002, 0.0002, 192);
		pipeline.flush();

		int emitted = diagnostics.counter("passages_emitted");
		Assert.assertTrue("the drive should have produced several passages", emitted >= 3);
		Assert.assertEquals("every emitted passage must reach an observation",
				emitted, diagnostics.counter("observations_created"));
		Assert.assertEquals(0, diagnostics.counter("observations_dropped_no_geometry"));
		Assert.assertEquals(0, diagnostics.counter("observations_dropped_geometry_mismatch"));
	}

	@Test
	public void thePassageClosedByTheNextRoadKeepsItsOwnRoadAndGeometry() throws Exception {
		// A A A A -> B B. The second road wins on its second fix and closes the
		// first road's passage; at that moment the vehicle is already on B, so
		// anything resolved then would describe the wrong road.
		List<RoadCrewDirectObservation> produced = new ArrayList<>();
		RoadCrewDirectPipeline pipeline = new RoadCrewDirectPipeline(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, passage -> { });
		pipeline.setObservationSink(produced::addAll);
		pipeline.setMapVersion("test.obf");

		List<RouteDataObject> loaded = Arrays.asList(
				fragment(700701, 27.000, 27.010), fragment(700702, 27.010, 27.020));
		drive(pipeline, loaded, 27.0002, 0.0002, 96);
		pipeline.flush();

		boolean reportedFirstRoad = false;
		for (RoadCrewDirectObservation observation : produced) {
			if (observation.osmWayId == 700701) {
				reportedFirstRoad = true;
				// The endpoints must lie on the first road, not the second.
				Assert.assertTrue("longitude " + observation.toLongitude + " is not on way A",
						observation.toLongitude <= 27.0101);
			}
		}
		Assert.assertTrue("the road driven end to end before the turn was never reported",
				reportedFirstRoad);
	}

	/** Drives a straight line west to east across whatever roads are loaded. */
	private static void drive(RoadCrewDirectPipeline pipeline, List<RouteDataObject> loaded,
			double fromLongitude, double step, int steps) {
		RoadCrewSegmentMatcher.PreparedSegments segments = RoadCrewSegmentMatcher.prepare(loaded);
		long time = 9_000_000;
		for (int index = 0; index < steps; index++) {
			RoadCrewSegmentMatcher.GpsFix fix = new RoadCrewSegmentMatcher.GpsFix(
					43.0, fromLongitude + index * step, 5, 16, 90);
			RoadCrewSegmentMatcher.MatchResult match = segments.match(fix);
			RouteDataObject road = null;
			if (match.isMatched() && match.getSegment() != null) {
				for (RouteDataObject candidate : loaded) {
					if (candidate.getId() == match.getSegment().getRoadId()) {
						road = candidate;
						break;
					}
				}
			}
			pipeline.accept(fix, match, road, time, index + 1);
			time += 1_000;
		}
	}

	/** One stretch of a longer OSM way, as the routing index would store it. */
	private static RouteDataObject fragment(long osmWayId, double fromLongitude,
			double toLongitude) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[0];
		int points = 5;
		road.pointsX = new int[points];
		road.pointsY = new int[points];
		for (int index = 0; index < points; index++) {
			double longitude = fromLongitude
					+ (toLongitude - fromLongitude) * index / (points - 1.0);
			road.pointsX[index] = MapUtils.get31TileNumberX(longitude);
			road.pointsY[index] = MapUtils.get31TileNumberY(43.0);
		}
		return road;
	}

	/** Runs east, then turns back west for its final leg. */
	private static RouteDataObject doublingBackRoad() {
		double[][] points = {{43.000, 27.000}, {43.000, 27.010}, {43.002, 27.020},
				{43.004, 27.030}, {43.006, 27.024}};
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		RouteDataObject road = new RouteDataObject(region);
		road.id = 7001L << 6;
		road.types = new int[0];
		road.pointsX = new int[points.length];
		road.pointsY = new int[points.length];
		for (int index = 0; index < points.length; index++) {
			road.pointsX[index] = MapUtils.get31TileNumberX(points[index][1]);
			road.pointsY[index] = MapUtils.get31TileNumberY(points[index][0]);
		}
		return road;
	}
}
