package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RoadCrewShadowRouteEvaluatorTest {

	@Test
	public void scoresOnlyExactDirectedSegmentsInsideSnapshotBounds() {
		RouteSegmentResult exact = segment(1001, 43.3500, 26.2200, 43.3510, 26.2210);
		RouteSegmentResult reverse = new RouteSegmentResult(exact.getObject(), 1, 0);
		RouteSegmentResult outside = segment(1002, 44.0000, 27.0000, 44.0010, 27.0010);
		RoadCrewSegmentIdentity.SegmentKey exactKey = RoadCrewSegmentIdentity.create(
				exact.getObject(), exact.getStartPointIndex(), exact.getEndPointIndex());
		RoadCrewShadowIndex.Entry entry = entry(exactKey,
				RoadCrewShadowIndex.Level.MATURE_SHADOW, 0.8);
		RoadCrewShadowIndex index = RoadCrewShadowIndex.create(1, 1_700_000_000_000L,
				RoadCrewShadowIndex.ROUTING_EFFECT_NONE,
				new RoadCrewShadowIndex.Bounds(43.0, 43.8, 26.0, 26.8),
				Collections.singletonList(entry));

		RoadCrewShadowRouteEvaluator.Result result = RoadCrewShadowRouteEvaluator.evaluate(
				Arrays.asList(exact, reverse, outside), index);

		Assert.assertEquals(3, result.getRouteSegmentCount());
		Assert.assertEquals(3, result.getIdentifiableSegmentCount());
		Assert.assertEquals(2, result.getEvaluatedSegmentCount());
		Assert.assertEquals(1, result.getExactMatchCount());
		Assert.assertEquals(1, result.getMatureMatchCount());
		Assert.assertEquals(0.5, result.getExactCoverage(), 0.01);
		Assert.assertEquals(0.5, result.getMatureCoverage(), 0.01);
		Assert.assertEquals(0.4, result.getConfidenceCoverage(), 0.01);
	}

	@Test
	public void preservesAlternativeOrderEvenWhenLaterRouteScoresHigher() {
		RouteSegmentResult unsupported = segment(2001, 43.3500, 26.2200, 43.3510, 26.2210);
		RouteSegmentResult supported = segment(2002, 43.3520, 26.2220, 43.3530, 26.2230);
		RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.create(
				supported.getObject(), supported.getStartPointIndex(), supported.getEndPointIndex());
		RoadCrewShadowIndex index = RoadCrewShadowIndex.create(1, 1_700_000_000_000L,
				RoadCrewShadowIndex.ROUTING_EFFECT_NONE,
				new RoadCrewShadowIndex.Bounds(43.0, 43.8, 26.0, 26.8),
				Collections.singletonList(entry(key, RoadCrewShadowIndex.Level.CANDIDATE, 1.0)));

		List<RoadCrewShadowRouteEvaluator.Result> results =
				RoadCrewShadowRouteEvaluator.evaluateAlternatives(Arrays.asList(
						Collections.singletonList(unsupported),
						Collections.singletonList(supported)), index);

		Assert.assertEquals(0, results.get(0).getInputPosition());
		Assert.assertEquals(0, results.get(0).getExactMatchCount());
		Assert.assertEquals(1, results.get(1).getInputPosition());
		Assert.assertEquals(1, results.get(1).getExactMatchCount());
		Assert.assertEquals(1.0, results.get(1).getConfidenceCoverage(), 0.001);
	}

	private static RouteSegmentResult segment(long osmWayId, double fromLatitude,
			double fromLongitude, double toLatitude, double toLongitude) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria_europe");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[0];
		road.pointsX = new int[]{MapUtils.get31TileNumberX(fromLongitude),
				MapUtils.get31TileNumberX(toLongitude)};
		road.pointsY = new int[]{MapUtils.get31TileNumberY(fromLatitude),
				MapUtils.get31TileNumberY(toLatitude)};
		return new RouteSegmentResult(road, 0, 1);
	}

	private static RoadCrewShadowIndex.Entry entry(RoadCrewSegmentIdentity.SegmentKey key,
			RoadCrewShadowIndex.Level level, double confidence) {
		return new RoadCrewShadowIndex.Entry(RoadCrewShadowIndex.segmentId(key),
				key.getCanonicalId(), key.getGeometryFingerprint(), level, confidence, 8, 4, 3);
	}
}
