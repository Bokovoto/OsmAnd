package net.osmand.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The wire shape of a directed observation. What the server checks, this must
 * produce: ascending measures, endpoints on the road, indexes that differ, and
 * a canonical id it can recompute for itself.
 */
public class RoadCrewDirectObservationTest {

	@Test
	public void anOrdinaryPassageBecomesOneObservationOnTheRoad() {
		RoadCrewWayCanonical.CanonicalWay way = straightWay();
		RoadCrewDirectPassageAccumulator.Passage passage = capture(way, 43.0, 27.0, 43.0, 27.02);

		List<RoadCrewDirectObservation> observations = RoadCrewDirectObservation.fromPassage(
				passage, way, "Bulgaria", "Bulgaria_europe_2026_08");

		Assert.assertEquals(1, observations.size());
		RoadCrewDirectObservation observation = observations.get(0);
		Assert.assertEquals("rcs2:5001:F", observation.getCanonicalId());
		Assert.assertEquals("F", observation.getDirection());
		Assert.assertTrue("measures must ascend",
				observation.toMeasureMeters > observation.fromMeasureMeters);
		Assert.assertTrue("the server refuses anything under a metre",
				observation.getLengthMeters() >= 1);
		Assert.assertNotEquals("the point indexes must differ",
				observation.startPointIndex, observation.endPointIndex);
		Assert.assertEquals(32, observation.geometryFingerprint.length());
		Assert.assertEquals(RoadCrewWayCanonical.FINGERPRINT_ALGORITHM,
				observation.geometryFingerprintAlgorithm);
		Assert.assertEquals("Bulgaria", observation.region);
		Assert.assertEquals("Bulgaria_europe_2026_08", observation.mapVersion);
		// The whole passage, undivided, because there was only one stretch.
		Assert.assertEquals(passage.fixCount, observation.fixCount);
		// The endpoints sit on the road that was measured, not near it.
		Assert.assertEquals(43.0, observation.fromLatitude, 1e-5);
		Assert.assertEquals(43.0, observation.toLatitude, 1e-5);
		Assert.assertTrue(observation.fromLongitude < observation.toLongitude);
	}

	@Test
	public void theBucketNeverCarriesTheMomentOfTheDrive() {
		RoadCrewWayCanonical.CanonicalWay way = straightWay();
		long observedAt = 1_757_000_123_456L;
		RoadCrewDirectPassageAccumulator.Passage passage = passage(way, observedAt, observedAt + 61_000,
				Arrays.asList(new double[]{100, 900}));

		RoadCrewDirectObservation observation = RoadCrewDirectObservation.fromPassage(
				passage, way, "Bulgaria", "map").get(0);

		Assert.assertEquals(0,
				observation.observedAtBucketMillis % RoadCrewDirectObservation.OBSERVATION_BUCKET_MILLIS);
		Assert.assertTrue(observation.observedAtBucketMillis <= observedAt);
		Assert.assertTrue(observedAt - observation.observedAtBucketMillis
				< RoadCrewDirectObservation.OBSERVATION_BUCKET_MILLIS);
	}

	@Test
	public void aStretchInsideOneLegStillNamesThatLeg() {
		RoadCrewWayCanonical.CanonicalWay way = straightWay();
		RoadCrewDirectPassageAccumulator.Passage passage = passage(way, 9_000_000, 9_010_000,
				Arrays.asList(new double[]{10, 40}));

		RoadCrewDirectObservation observation = RoadCrewDirectObservation.fromPassage(
				passage, way, "Bulgaria", "map").get(0);

		Assert.assertNotEquals(observation.startPointIndex, observation.endPointIndex);
		Assert.assertTrue(observation.startPointIndex >= 0);
		Assert.assertTrue(observation.endPointIndex < way.getPointCount());
	}

	@Test
	public void aStretchShorterThanAMetreIsNotReported() {
		RoadCrewWayCanonical.CanonicalWay way = straightWay();
		RoadCrewDirectPassageAccumulator.Passage passage = passage(way, 9_000_000, 9_000_500,
				Arrays.asList(new double[]{100, 100.4}));

		Assert.assertTrue(RoadCrewDirectObservation.fromPassage(passage, way, "Bulgaria", "map")
				.isEmpty());
	}

	@Test
	public void aRingTraversedPastItsEndBecomesTwoStretchesOfOneDrive() {
		RoadCrewWayCanonical.CanonicalWay ring = ringWay();
		RoadCrewDirectPassageAccumulator.Passage passage = passage(ring, 9_000_000, 9_120_000,
				Arrays.asList(new double[]{ring.lengthMeters * 0.75, ring.lengthMeters},
						new double[]{0, ring.lengthMeters * 0.25}));

		List<RoadCrewDirectObservation> observations =
				RoadCrewDirectObservation.fromPassage(passage, ring, "Bulgaria", "map");

		Assert.assertEquals(2, observations.size());
		int totalFixes = 0;
		for (RoadCrewDirectObservation observation : observations) {
			Assert.assertTrue(observation.toMeasureMeters > observation.fromMeasureMeters);
			Assert.assertTrue(observation.toMeasureMeters <= ring.lengthMeters + 1e-6);
			Assert.assertNotEquals(observation.startPointIndex, observation.endPointIndex);
			Assert.assertTrue("an index must name a real point of the ring",
					observation.startPointIndex < ring.getPointCount()
							&& observation.endPointIndex < ring.getPointCount());
			// Both stretches belong to the same passage on the shared timeline.
			Assert.assertEquals(passage.firstFixSequence, observation.firstFixSequence);
			Assert.assertEquals(passage.lastFixSequence, observation.lastFixSequence);
			totalFixes += observation.fixCount;
		}
		Assert.assertTrue("the effort is shared out, not counted twice",
				totalFixes <= passage.fixCount + 1);
	}

	@Test
	public void theDirectionIsTheOnlyPlaceTravelSenseIsCarried() {
		RoadCrewWayCanonical.CanonicalWay way = straightWay();
		RoadCrewDirectPassageAccumulator.Passage backwards = new PassageBuilder()
				.forward(false).span(200, 800).build();

		RoadCrewDirectObservation observation = RoadCrewDirectObservation.fromPassage(
				backwards, way, "Bulgaria", "map").get(0);

		Assert.assertEquals("R", observation.getDirection());
		Assert.assertEquals("rcs2:5001:R", observation.getCanonicalId());
		Assert.assertTrue("the interval stays ascending whichever way it was driven",
				observation.toMeasureMeters > observation.fromMeasureMeters);
	}

	private static RoadCrewDirectPassageAccumulator.Passage capture(
			RoadCrewWayCanonical.CanonicalWay way, double fromLatitude, double fromLongitude,
			double toLatitude, double toLongitude) {
		double from = measureOf(way, fromLatitude, fromLongitude);
		double to = measureOf(way, toLatitude, toLongitude);
		return passage(way, 9_000_000, 9_060_000, Arrays.asList(new double[]{from, to}));
	}

	private static double measureOf(RoadCrewWayCanonical.CanonicalWay way,
			double latitude, double longitude) {
		int x = net.osmand.util.MapUtils.get31TileNumberX(longitude);
		int y = net.osmand.util.MapUtils.get31TileNumberY(latitude);
		double best = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int index = 0; index < way.getPointCount(); index++) {
			double distance = RoadCrewWayCanonical.distanceMeters(
					way.pointsX[index], way.pointsY[index], x, y);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = way.measures[index];
			}
		}
		return best;
	}

	private static RoadCrewDirectPassageAccumulator.Passage passage(
			RoadCrewWayCanonical.CanonicalWay way, long start, long end, List<double[]> spans) {
		PassageBuilder builder = new PassageBuilder().times(start, end);
		for (double[] span : spans) {
			builder.span(span[0], span[1]);
		}
		return builder.build();
	}

	/** Builds a Passage without reaching into the accumulator's own state machine. */
	private static final class PassageBuilder {
		private final List<RoadCrewDirectPassageAccumulator.Span> spans = new ArrayList<>();
		private boolean forward = true;
		private long start = 9_000_000;
		private long end = 9_060_000;

		PassageBuilder forward(boolean value) {
			forward = value;
			return this;
		}

		PassageBuilder times(long startMillis, long endMillis) {
			start = startMillis;
			end = endMillis;
			return this;
		}

		PassageBuilder span(double from, double to) {
			spans.add(new RoadCrewDirectPassageAccumulator.Span(from, to));
			return this;
		}

		RoadCrewDirectPassageAccumulator.Passage build() {
			double covered = 0;
			for (RoadCrewDirectPassageAccumulator.Span span : spans) {
				covered += span.toMeasureMeters - span.fromMeasureMeters;
			}
			return new RoadCrewDirectPassageAccumulator.Passage(5001, forward, spans, start, end,
					12, covered, 120, 138);
		}
	}

	/** A straight east-west road of eleven points, about 1.6 km long. */
	private static RoadCrewWayCanonical.CanonicalWay straightWay() {
		int[] xs = new int[11];
		int[] ys = new int[11];
		for (int index = 0; index < xs.length; index++) {
			xs[index] = net.osmand.util.MapUtils.get31TileNumberX(27.0 + index * 0.002);
			ys[index] = net.osmand.util.MapUtils.get31TileNumberY(43.0);
		}
		return RoadCrewWayCanonical.canonicalise(xs, ys);
	}

	/** A closed square, so a traversal can run past its end. */
	private static RoadCrewWayCanonical.CanonicalWay ringWay() {
		double[][] corners = {{43.00, 27.00}, {43.00, 27.01}, {43.01, 27.01}, {43.01, 27.00},
				{43.00, 27.00}};
		int[] xs = new int[corners.length];
		int[] ys = new int[corners.length];
		for (int index = 0; index < corners.length; index++) {
			xs[index] = net.osmand.util.MapUtils.get31TileNumberX(corners[index][1]);
			ys[index] = net.osmand.util.MapUtils.get31TileNumberY(corners[index][0]);
		}
		return RoadCrewWayCanonical.canonicalise(xs, ys);
	}
}
