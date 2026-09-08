package net.osmand.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class RoadCrewReplayRecallTest {

	@Test
	public void unmatchedInteriorFixesDoNotInflateRecall() {
		RoadCrewReplay.Result result = new RoadCrewReplay.Result();
		for (long sequence : new long[]{1, 3, 5}) {
			result.diagnostics.matched(sequence, 5001, true);
			result.recordMatchedFix(sequence);
		}
		result.diagnostics.passageCovered(1, 5);
		result.diagnostics.finishCoverage();
		result.directed.add(observation(1, 5));
		Assert.assertEquals(1.0, result.directedRecall(), 0.0);
	}

	@Test
	public void overlappingAndRepeatedObservationRangesCountEachMatchedFixOnce() {
		RoadCrewReplay.Result result = matched(1, 3, 5, 7);
		result.directed.add(observation(3, 5));
		result.directed.add(observation(1, 3));
		result.directed.add(observation(1, 3));
		Assert.assertEquals(Long.valueOf(3), result.directedCoveredMatchedFixCount());
		Assert.assertEquals(0.75, result.directedRecall(), 0.0);
		Collections.reverse(result.directed);
		Assert.assertEquals(0.75, result.directedRecall(), 0.0);
	}

	@Test
	public void passageCoverageDoesNotHideMissingObservations() {
		RoadCrewReplay.Result result = matched(1, 3, 5);
		Assert.assertEquals(3, result.diagnostics.coveredMatchedFixCount());
		Assert.assertEquals(0.0, result.directedRecall(), 0.0);
		result.directed.add(observation(1, 3));
		Assert.assertEquals(2.0 / 3, result.directedRecall(), 0.0);
	}

	@Test
	public void emptyOrIncompleteBaselineIsUnavailableNotZero() {
		RoadCrewReplay.Result empty = matched();
		Assert.assertTrue(Double.isNaN(empty.directedRecall()));
		RoadCrewReplay.Result unfinished = new RoadCrewReplay.Result();
		unfinished.recordMatchedFix(1);
		unfinished.diagnostics.matched(1, 5001, true);
		Assert.assertNull(unfinished.directedCoveredMatchedFixCount());
		Assert.assertTrue(Double.isNaN(unfinished.directedRecall()));
		RoadCrewReplay.Result inconsistent = matched(1, 3);
		inconsistent.recordMatchedFix(5);
		Assert.assertTrue(Double.isNaN(inconsistent.directedRecall()));
	}

	@Test
	public void exactBaselineSurvivesTheForensicRunCap() {
		long[] ids = new long[RoadCrewDiagnostics.MAX_BASELINE_RUNS + 100];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = 2L * i + 1;
		}
		RoadCrewReplay.Result result = matched(ids);
		result.directed.add(observation(1, ids[ids.length - 1]));
		Assert.assertEquals(ids.length, result.matchedFixCount());
		Assert.assertEquals(Long.valueOf(ids.length), result.directedCoveredMatchedFixCount());
		Assert.assertEquals(1.0, result.directedRecall(), 0.0);
	}

	private static RoadCrewReplay.Result matched(long... ids) {
		RoadCrewReplay.Result result = new RoadCrewReplay.Result();
		for (long id : ids) {
			result.recordMatchedFix(id);
			result.diagnostics.matched(id, 5001, true);
		}
		if (ids.length > 0) {
			result.diagnostics.passageCovered(ids[0], ids[ids.length - 1]);
		}
		result.diagnostics.finishCoverage();
		return result;
	}

	private static RoadCrewDirectObservation observation(long first, long last) {
		int[] x = {net.osmand.util.MapUtils.get31TileNumberX(27),
				net.osmand.util.MapUtils.get31TileNumberX(27.01)};
		int[] y = {net.osmand.util.MapUtils.get31TileNumberY(43),
				net.osmand.util.MapUtils.get31TileNumberY(43)};
		RoadCrewWayCanonical.CanonicalWay way = RoadCrewWayCanonical.canonicalise(x, y);
		RoadCrewDirectPassageAccumulator.Passage passage =
				new RoadCrewDirectPassageAccumulator.Passage(5001, true,
						Collections.singletonList(new RoadCrewDirectPassageAccumulator.Span(10, 20)),
						1000, 3000, 3, 10, first, last);
		return RoadCrewDirectObservation.fromPassage(passage, way, "test", "test").get(0);
	}
}
