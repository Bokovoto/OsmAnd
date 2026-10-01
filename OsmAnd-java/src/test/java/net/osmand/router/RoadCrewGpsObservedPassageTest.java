package net.osmand.router;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ROADMAP 348: another driver may fill the map, never this driver's GPS hole. */
public class RoadCrewGpsObservedPassageTest {

	private static final class Drive implements RoadCrewDirectPassageAccumulator.Topology {
		final List<RoadCrewDirectPassageAccumulator.Passage> passages = new ArrayList<>();
		final RoadCrewDirectPassageAccumulator accumulator = new RoadCrewDirectPassageAccumulator(
				RoadCrewDirectPassageAccumulator.Config.GPS_OBSERVED_348, passages::add);
		long time = 1_757_000_000_000L;
		long sequence;
		boolean meet = true;
		int mapInferences;

		Drive() { accumulator.setTopology(this); }

		void fix(long after, long way, boolean forward, double measure, double movement) {
			time += after;
			accumulator.position(time, 43, 27);
			accumulator.accept(new RoadCrewDirectPassageAccumulator.Fix(way, forward, measure,
					false, 4000, time, movement, ++sequence, 3, 5, way));
		}

		void standing(long after) {
			time += after;
			accumulator.position(time, 43, 27);
		}

		@Override
		public boolean meetAt(Object from, Object to, List<double[]> positions, double radius) {
			return meet;
		}

		@Override
		public List<RoadCrewDirectPassageAccumulator.Leg> withoutBranch(Object from, boolean f,
				double fromMeasure, Object to, boolean t, double toMeasure) {
			mapInferences++;
			return from.equals(to) ? Collections.emptyList() : Collections.singletonList(
					new RoadCrewDirectPassageAccumulator.Leg(303, true, 100, 303L));
		}
	}

	private static void span(Drive d, int index, long way, double from, double to, boolean joined) {
		RoadCrewDirectPassageAccumulator.Passage p = d.passages.get(index);
		Assert.assertEquals(way, p.wayId);
		Assert.assertEquals(1, p.spans.size());
		Assert.assertEquals(from, p.spans.get(0).fromMeasureMeters, 0.001);
		Assert.assertEquals(to, p.spans.get(0).toMeasureMeters, 0.001);
		Assert.assertEquals(joined, p.joinsPrevious);
		Assert.assertFalse(p.bridged);
		Assert.assertTrue(p.fixCount >= 2);
	}

	@Test
	public void branchFreeSameWayGapPreservesBothObservedSidesInBothDirections() {
		for (boolean forward : new boolean[] {true, false}) {
			Drive d = new Drive();
			d.fix(0, 101, forward, forward ? 100 : 2160, 0);
			d.fix(1000, 101, forward, forward ? 130 : 2130, 30);
			d.fix(90_000, 101, forward, forward ? 2130 : 130, 2000);
			d.fix(1000, 101, forward, forward ? 2160 : 100, 30);
			d.accumulator.flush();
			Assert.assertEquals(2, d.passages.size());
			span(d, forward ? 0 : 1, 101, 100, 130, false);
			span(d, forward ? 1 : 0, 101, 2130, 2160, false);
			Assert.assertEquals(0, d.mapInferences);
		}
	}

	@Test
	public void silenceCannotInventATunnelRoadOrATurn() {
		Drive d = new Drive();
		d.fix(0, 101, true, 3900, 0);
		d.fix(1000, 101, true, 3930, 30);
		d.fix(60_000, 202, true, 40, 200);
		d.fix(1000, 202, true, 70, 30);
		d.accumulator.flush();
		Assert.assertEquals(2, d.passages.size());
		span(d, 0, 101, 3900, 3930, false);
		span(d, 1, 202, 40, 70, false);
		Assert.assertEquals(0, d.mapInferences);
	}

	@Test
	public void silenceWhileANewWayIsPendingDoesNotExtendItsFirstFixAcrossTheHole() {
		Drive d = new Drive();
		d.fix(0, 101, true, 100, 0);
		d.fix(1000, 101, true, 130, 30);
		d.fix(1000, 202, true, 10, 20);
		d.fix(60_000, 202, true, 1010, 1000);
		d.fix(1000, 202, true, 1040, 30);
		d.accumulator.flush();
		Assert.assertEquals(2, d.passages.size());
		span(d, 0, 101, 100, 130, false);
		span(d, 1, 202, 1010, 1040, false);
	}

	@Test
	public void mapOnlyConnectorIsNotEmittedEvenWithGpsPresent() {
		Drive d = new Drive();
		d.meet = false;
		d.fix(0, 101, true, 3900, 0);
		d.fix(1000, 101, true, 3930, 30);
		d.fix(1000, 202, true, 40, 200);
		d.fix(1000, 202, true, 70, 30);
		d.accumulator.flush();
		Assert.assertEquals(2, d.passages.size());
		span(d, 0, 101, 3900, 3930, false);
		span(d, 1, 202, 40, 70, false);
		Assert.assertEquals(0, d.mapInferences);
	}

	@Test
	public void stationaryPositionsDoNotBecomeAGpsHole() {
		Drive d = new Drive();
		d.fix(0, 101, true, 100, 0);
		d.fix(1000, 101, true, 130, 30);
		for (int i = 0; i < 120; i++) { d.standing(1000); }
		d.fix(1000, 101, true, 150, 20);
		d.accumulator.flush();
		Assert.assertEquals(1, d.passages.size());
		span(d, 0, 101, 100, 150, false);
	}

	@Test
	public void aLaterGpsObservedTurnStillJoinsAfterAnEarlierHole() {
		Drive d = new Drive();
		d.fix(0, 101, true, 100, 0);
		d.fix(1000, 101, true, 130, 30);
		d.fix(60_000, 101, true, 1000, 870);
		d.fix(1000, 101, true, 1030, 30);
		d.fix(1000, 202, true, 10, 20);
		d.fix(1000, 202, true, 40, 30);
		d.accumulator.flush();
		Assert.assertEquals(3, d.passages.size());
		span(d, 0, 101, 100, 130, false);
		span(d, 1, 101, 1000, 1030, false);
		span(d, 2, 202, 10, 40, true);
	}

	@Test
	public void existingSilenceBoundaryIsUnchanged() {
		Drive d = new Drive();
		d.fix(0, 101, true, 100, 0);
		d.fix(10_000, 101, true, 130, 30);
		d.fix(10_001, 101, true, 160, 30);
		d.fix(1000, 101, true, 190, 30);
		d.accumulator.flush();
		Assert.assertEquals(2, d.passages.size());
		span(d, 0, 101, 100, 130, false);
		span(d, 1, 101, 160, 190, false);
	}
}
