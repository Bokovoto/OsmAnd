package net.osmand.router;

import net.osmand.binary.RouteDataObject;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.util.MapUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
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
}
