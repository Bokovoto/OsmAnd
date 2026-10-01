package net.osmand.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ROADMAP 330, Galin's rule of 26.09: "if a truck cannot prove it drove a road,
 * better leave it as never driven". Two observations are one drive only when
 * GPS saw the truck through the junction of two ways that meet (R1), or when
 * the map path from where GPS disappeared to where it came back has no branch
 * (R2). Everything else stays apart - no turn, no finished exit cell.
 *
 * The map is a stand-in here: what it answers is decided by each test, and
 * what it is asked is checked. The real map walk has its own tests.
 */
public class RoadCrewProvenPassageTest {

	private static final long WAY_A = 101L;
	private static final long WAY_B = 202L;
	private static final long WAY_C = 303L;
	private static final double LENGTH = 4000;

	/** A map whose answers the test decides. */
	private static final class Map implements RoadCrewDirectPassageAccumulator.Topology {
		boolean meet;
		/** Where the ways meet; null leaves it to meetAt, as an older map would. */
		RoadCrewDirectPassageAccumulator.Junction junction;
		List<RoadCrewDirectPassageAccumulator.Leg> path;
		int meetQuestions;
		int pathQuestions;
		List<double[]> positionsAsked;

		@Override
		public boolean meetAt(Object fromWay, Object toWay, List<double[]> positions,
				double radiusMeters) {
			meetQuestions++;
			positionsAsked = positions;
			Assert.assertEquals(60, radiusMeters, 0);
			return meet;
		}

		@Override
		public RoadCrewDirectPassageAccumulator.Junction junction(Object fromWay, Object toWay,
				List<double[]> positions, double radiusMeters) {
			if (junction == null) {
				return RoadCrewDirectPassageAccumulator.Topology.super.junction(fromWay, toWay,
						positions, radiusMeters);
			}
			meetQuestions++;
			positionsAsked = positions;
			return junction;
		}

		@Override
		public List<RoadCrewDirectPassageAccumulator.Leg> withoutBranch(Object fromWay,
				boolean fromForward, double fromMeasure, Object toWay, boolean toForward,
				double toMeasure) {
			pathQuestions++;
			return path;
		}
	}

	private List<RoadCrewDirectPassageAccumulator.Passage> passages;
	private RoadCrewDirectPassageAccumulator accumulator;
	private Map map;
	private long clock;
	private long sequence;

	@Before
	public void setUp() {
		passages = new ArrayList<>();
		map = new Map();
		accumulator = new RoadCrewDirectPassageAccumulator(
				RoadCrewDirectPassageAccumulator.Config.PROVEN_330, passages::add);
		accumulator.setTopology(map);
		clock = 1_757_000_000_000L;
	}

	/** A matched fix, with its GPS position, after {@code afterMillis}. */
	private void fix(long afterMillis, long way, double measure, double movement) {
		clock += afterMillis;
		sequence++;
		accumulator.position(clock, 43.0, 27.0 + measure / 80_000.0);
		accumulator.accept(new RoadCrewDirectPassageAccumulator.Fix(way, true, measure, false,
				LENGTH, clock, movement, sequence, 3, 5, "way" + way));
	}

	/** The same, travelling against the measures. */
	private void fixAgainst(long afterMillis, long way, double measure, double movement) {
		clock += afterMillis;
		sequence++;
		accumulator.position(clock, 43.0, 27.0 + measure / 80_000.0);
		accumulator.accept(new RoadCrewDirectPassageAccumulator.Fix(way, false, measure, false,
				LENGTH, clock, movement, sequence, 3, 5, "way" + way));
	}

	/** GPS present, nothing matched. */
	private void unmatched(long afterMillis) {
		clock += afterMillis;
		sequence++;
		accumulator.position(clock, 43.0, 27.0);
		accumulator.acceptNoMatch(clock);
	}

	/** GPS present without speed or bearing: a truck standing at a stop line. */
	private void standing(long afterMillis) {
		clock += afterMillis;
		accumulator.position(clock, 43.0, 27.0);
	}

	private void assertSpan(RoadCrewDirectPassageAccumulator.Passage passage, double from, double to) {
		Assert.assertEquals(1, passage.spans.size());
		Assert.assertEquals("from", from, passage.spans.get(0).fromMeasureMeters, 0.5);
		Assert.assertEquals("to", to, passage.spans.get(0).toMeasureMeters, 0.5);
	}

	@Test
	public void aTunnelOnOneWayWithoutABranchIsOnePassage() {
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 130, 30);
		// GPS gone for 90 s, back 2 km further along the same road.
		map.path = Collections.emptyList();
		fix(90_000, WAY_A, 2130, 1900);
		fix(1000, WAY_A, 2160, 30);
		accumulator.flush();

		Assert.assertEquals(1, map.pathQuestions);
		Assert.assertEquals(1, passages.size());
		assertSpan(passages.get(0), 100, 2160);
	}

	@Test
	public void leavingAndReturningToTheSameWayOverABranchIsNotFilledIn() {
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 130, 30);
		// GPS gone; a branch lies in between, so the truck may have left and come back.
		map.path = null;
		fix(90_000, WAY_A, 2130, 1900);
		fix(1000, WAY_A, 2160, 30);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		assertSpan(passages.get(0), 100, 130);
		assertSpan(passages.get(1), 2130, 2160);
		Assert.assertFalse(passages.get(1).joinsPrevious);
	}

	@Test
	public void aVillageBendWithUnmatchedFixesStaysOnePassageWithoutAskingTheMap() {
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 115, 15);
		unmatched(1000);
		unmatched(1000);
		unmatched(1000);
		fix(1000, WAY_A, 175, 60);
		accumulator.flush();

		Assert.assertEquals(0, map.pathQuestions + map.meetQuestions);
		Assert.assertEquals(1, passages.size());
		assertSpan(passages.get(0), 100, 175);
	}

	@Test
	public void standingAtAStopLineIsGpsPresentNotADisappearance() {
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 115, 15);
		for (int second = 0; second < 40; second++) {
			standing(1000);
		}
		fix(1000, WAY_A, 130, 15);
		accumulator.flush();

		Assert.assertEquals(0, map.pathQuestions);
		Assert.assertEquals(1, passages.size());
		assertSpan(passages.get(0), 100, 130);
	}

	@Test
	public void aTurnSeenByGpsWhereTheWaysMeetIsJoined() {
		map.meet = true;
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 30, 20);
		fix(1000, WAY_B, 50, 20);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertFalse("a course starts unjoined", passages.get(0).joinsPrevious);
		Assert.assertTrue(passages.get(1).joinsPrevious);
		Assert.assertEquals(1, map.meetQuestions);
		Assert.assertEquals("last fix on A, the unmatched one, first on B", 3,
				map.positionsAsked.size());
		assertSpan(passages.get(0), 100, 120);
		assertSpan(passages.get(1), 10, 50);
	}

	@Test
	public void aShortConnectorTheMatcherMissedIsNotATurn() {
		// A -> connector -> B: A and B do not meet, and the connector's ends are junctions.
		map.meet = false;
		map.path = null;
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		unmatched(1000);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 30, 20);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertFalse(passages.get(1).joinsPrevious);
		Assert.assertEquals(1, map.meetQuestions);
		Assert.assertEquals(1, map.pathQuestions);
	}

	@Test
	public void aJumpToAParallelRoadIsNotATurn() {
		map.meet = false;
		map.path = null;
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		fix(1000, WAY_B, 140, 20);
		fix(1000, WAY_B, 160, 20);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertFalse(passages.get(1).joinsPrevious);
	}

	// Codex's Test 118 probe: ends 16 m apart, hours without GPS in between.
	@Test
	public void aDisappearanceAtAJunctionIsNotJoinedHoweverCloseTheEnds() {
		map.meet = true;
		map.path = null;
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		fix(9_000_000, WAY_B, 10, 16);
		fix(1000, WAY_B, 30, 20);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertFalse(passages.get(1).joinsPrevious);
		Assert.assertEquals("GPS was gone: whether the ways meet proves nothing", 0,
				map.meetQuestions);
		Assert.assertEquals(1, map.pathQuestions);
	}

	@Test
	public void aTunnelSplitIntoWaysIsRecordedThroughTheMiddle() {
		// A (4 km) -> tunnel B (1.5 km, no fix inside) -> C; nothing branches.
		map.path = Arrays.asList(new RoadCrewDirectPassageAccumulator.Leg(
				WAY_B, false, 1500, "wayB"));
		fix(0, WAY_A, 3900, 0);
		fix(1000, WAY_A, 3930, 30);
		fix(60_000, WAY_C, 40, 1600);
		fix(1000, WAY_C, 70, 30);
		fix(1000, WAY_C, 100, 30);
		accumulator.flush();

		Assert.assertEquals(3, passages.size());
		RoadCrewDirectPassageAccumulator.Passage a = passages.get(0);
		RoadCrewDirectPassageAccumulator.Passage b = passages.get(1);
		RoadCrewDirectPassageAccumulator.Passage c = passages.get(2);
		Assert.assertEquals(WAY_A, a.wayId);
		assertSpan(a, 3900, LENGTH);
		Assert.assertFalse(a.bridged);

		Assert.assertEquals(WAY_B, b.wayId);
		Assert.assertFalse("driven the other way round", b.forward);
		assertSpan(b, 0, 1500);
		Assert.assertTrue(b.bridged);
		Assert.assertTrue(b.joinsPrevious);
		Assert.assertEquals(0, b.fixCount);
		Assert.assertEquals("wayB", b.attachment);

		Assert.assertEquals(WAY_C, c.wayId);
		assertSpan(c, 0, 100);
		Assert.assertTrue(c.joinsPrevious);
		Assert.assertFalse(c.bridged);
	}

	@Test
	public void aPassageThatIsDiscardedBreaksTheNextJoin() {
		map.meet = true;
		fix(0, WAY_A, 100, 0);
		// A single fix on A: nothing driven, nothing sent - so nothing to join to.
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 30, 20);
		accumulator.flush();

		Assert.assertEquals(1, passages.size());
		Assert.assertEquals(WAY_B, passages.get(0).wayId);
		Assert.assertFalse(passages.get(0).joinsPrevious);
	}

	@Test
	public void turningRoundOnTheSameWayIsNeverJoined() {
		map.meet = true;
		map.path = Collections.emptyList();
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 130, 30);
		clock += 1000;
		sequence++;
		accumulator.position(clock, 43.0, 27.0);
		accumulator.accept(new RoadCrewDirectPassageAccumulator.Fix(WAY_A, false, 125, false,
				LENGTH, clock, 5, sequence, 3, 5, "way" + WAY_A));
		clock += 1000;
		sequence++;
		accumulator.position(clock, 43.0, 27.0);
		accumulator.accept(new RoadCrewDirectPassageAccumulator.Fix(WAY_A, false, 95, false,
				LENGTH, clock, 30, sequence, 3, 5, "way" + WAY_A));
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertFalse(passages.get(1).joinsPrevious);
	}

	@Test
	public void withoutAMapNothingIsJoinedAndASilenceSplits() {
		accumulator.setTopology(null);
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 130, 30);
		fix(90_000, WAY_A, 2130, 1900);
		fix(1000, WAY_A, 2160, 30);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 30, 20);
		accumulator.flush();

		Assert.assertEquals(3, passages.size());
		for (RoadCrewDirectPassageAccumulator.Passage passage : passages) {
			Assert.assertFalse(passage.joinsPrevious);
		}
	}

	// The historic configurations keep their recorded behaviour: 322 joined a
	// same-way silence of any length, and the offline replays reproduce it.
	@Test
	public void experiment321StillJoinsASilenceOnTheSameWay() {
		accumulator = new RoadCrewDirectPassageAccumulator(
				RoadCrewDirectPassageAccumulator.Config.EXPERIMENT_321, passages::add);
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 130, 30);
		fix(90_000, WAY_A, 2130, 1900);
		accumulator.flush();

		Assert.assertEquals(1, passages.size());
		assertSpan(passages.get(0), 100, 2130);
	}

	// Galin, 01.10.2026, and Codex's review of 30.09: the server must not close
	// a stretch up to the way's end on a junction boolean - the junction can be
	// a node in the middle of the way. The phone knows the node: it is the one
	// every position of the turn lies near. So the phone records each way up to
	// that node, measured on its own map, and the server has nothing to guess.

	@Test
	public void aTurnSeenByGpsIsRecordedUpToTheSharedNodeOnBothWays() {
		map.junction = new RoadCrewDirectPassageAccumulator.Junction(140, 0);
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 30, 20);
		fix(1000, WAY_B, 50, 20);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertTrue(passages.get(1).joinsPrevious);
		assertSpan(passages.get(0), 100, 140);
		assertSpan(passages.get(1), 0, 50);
	}

	@Test
	public void theNodeInTheMiddleOfTheWayIsWhereTheRecordStops() {
		// The junction is 1 km into a 4 km way: recorded to it, not to the end.
		map.junction = new RoadCrewDirectPassageAccumulator.Junction(1030, 600);
		fix(0, WAY_A, 950, 0);
		fix(1000, WAY_A, 1010, 60);
		unmatched(1000);
		fix(1000, WAY_B, 615, 20);
		fix(1000, WAY_B, 640, 25);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		assertSpan(passages.get(0), 950, 1030);
		assertSpan(passages.get(1), 600, 640);
	}

	@Test
	public void theNodeNeverTakesBackWhatWasDriven() {
		// GPS noise put the last fix on A past the node, and the first on B before it.
		map.junction = new RoadCrewDirectPassageAccumulator.Junction(110, 20);
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 50, 40);
		accumulator.flush();

		assertSpan(passages.get(0), 100, 120);
		assertSpan(passages.get(1), 10, 50);
	}

	@Test
	public void aNodeFarFromTheFixesIsNotTrustedOnThatWay() {
		// Ways that meet twice: the node found on A lies 780 m on. Not that one.
		map.junction = new RoadCrewDirectPassageAccumulator.Junction(900, 0);
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 50, 40);
		accumulator.flush();

		assertSpan(passages.get(0), 100, 120);
		assertSpan(passages.get(1), 0, 50);
	}

	@Test
	public void againstTheMeasuresTheNodeIsAtTheHighEnd() {
		map.junction = new RoadCrewDirectPassageAccumulator.Junction(140, LENGTH);
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		fixAgainst(1000, WAY_B, 3990, 20);
		fixAgainst(1000, WAY_B, 3970, 20);
		fixAgainst(1000, WAY_B, 3950, 20);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		assertSpan(passages.get(0), 100, 140);
		assertSpan(passages.get(1), 3950, LENGTH);
	}

	@Test
	public void aMapThatCannotPlaceTheNodeLeavesTheFixesAsTheyAre() {
		map.junction = RoadCrewDirectPassageAccumulator.Junction.UNPLACED;
		fix(0, WAY_A, 100, 0);
		fix(1000, WAY_A, 120, 20);
		unmatched(1000);
		fix(1000, WAY_B, 10, 20);
		fix(1000, WAY_B, 50, 40);
		accumulator.flush();

		Assert.assertTrue(passages.get(1).joinsPrevious);
		assertSpan(passages.get(0), 100, 120);
		assertSpan(passages.get(1), 10, 50);
	}
}
