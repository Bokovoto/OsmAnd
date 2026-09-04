package net.osmand.router;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The synthetic sequences of ROADMAP section 165, checkpoint B. Every case
 * exists because a real defect hides behind it: a single lost fix shattering a
 * passage, jitter between parallel roads inventing fragments, or - the worst of
 * them - a map-match jump silently recording hundreds of metres nobody drove.
 */
public class RoadCrewDirectPassageAccumulatorTest {

	private static final long WAY_A = 1037293829L;
	private static final long WAY_B = 41160487L;
	private static final double OPEN_LENGTH = 4000;
	private static final double RING_LENGTH = 400;

	private List<RoadCrewDirectPassageAccumulator.Passage> passages;
	private RoadCrewDirectPassageAccumulator accumulator;
	private long clock;

	@Before
	public void setUp() {
		passages = new ArrayList<>();
		accumulator = new RoadCrewDirectPassageAccumulator(
				RoadCrewDirectPassageAccumulator.Config.DEFAULT_V1, passages::add);
		clock = 1_757_000_000_000L;
	}

	/** A fix one second later, having moved as far as the measure claims. */
	private void fix(long way, boolean forward, double measure, double movement) {
		fixAfter(1000, way, forward, measure, movement, false, OPEN_LENGTH);
	}

	private void fixAfter(long deltaMillis, long way, boolean forward, double measure,
			double movement, boolean closed, double length) {
		clock += deltaMillis;
		accumulator.accept(new RoadCrewDirectPassageAccumulator.Fix(
				way, forward, measure, closed, length, clock, movement));
	}

	private void noMatch(long deltaMillis) {
		clock += deltaMillis;
		accumulator.acceptNoMatch(clock);
	}

	private RoadCrewDirectPassageAccumulator.Passage only() {
		Assert.assertEquals("expected exactly one passage", 1, passages.size());
		return passages.get(0);
	}

	private void assertSpan(RoadCrewDirectPassageAccumulator.Passage passage, int index,
			double from, double to) {
		RoadCrewDirectPassageAccumulator.Span span = passage.spans.get(index);
		Assert.assertEquals("span " + index + " from", from, span.fromMeasureMeters, 0.5);
		Assert.assertEquals("span " + index + " to", to, span.toMeasureMeters, 0.5);
	}

	@Test
	public void oneRoadDrivenStraightThroughIsOnePassage() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_A, true, 140, 40);
		fix(WAY_A, true, 190, 50);
		fix(WAY_A, true, 245, 55);
		accumulator.flush();

		RoadCrewDirectPassageAccumulator.Passage passage = only();
		Assert.assertEquals(WAY_A, passage.wayId);
		Assert.assertTrue(passage.forward);
		Assert.assertEquals(1, passage.spans.size());
		assertSpan(passage, 0, 100, 245);
	}

	@Test
	public void oneLostFixDoesNotShatterAPassage() {
		fix(WAY_A, true, 100, 0);
		noMatch(1000);
		fix(WAY_A, true, 180, 80);
		accumulator.flush();

		assertSpan(only(), 0, 100, 180);
	}

	@Test
	public void severalLostFixesInsideTheGraceStillJoin() {
		fix(WAY_A, true, 100, 0);
		noMatch(1000);
		noMatch(1000);
		noMatch(1000);
		fix(WAY_A, true, 240, 140);
		accumulator.flush();

		assertSpan(only(), 0, 100, 240);
	}

	@Test
	public void aLongSilenceEndsThePassageEvenOnTheSameRoad() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_A, true, 145, 45);
		for (int index = 0; index < 6; index++) {
			noMatch(2000);
		}
		fix(WAY_A, true, 1200, 40);
		fix(WAY_A, true, 1245, 45);
		accumulator.flush();

		// Twelve seconds of silence: the same road, but nothing says the truck
		// spent them on it. Two honest passages, not one invented kilometre.
		Assert.assertEquals(2, passages.size());
		assertSpan(passages.get(0), 0, 100, 145);
		assertSpan(passages.get(1), 0, 1200, 1245);
	}

	@Test
	public void aSingleForeignMatchDoesNotHandOverTheRoad() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_B, true, 900, 40);
		fix(WAY_A, true, 180, 40);
		accumulator.flush();

		// B never confirmed itself, so A survives whole.
		RoadCrewDirectPassageAccumulator.Passage passage = only();
		Assert.assertEquals(WAY_A, passage.wayId);
		assertSpan(passage, 0, 100, 180);
	}

	@Test
	public void twoConsecutiveForeignMatchesHandOver() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_A, true, 150, 50);
		fix(WAY_B, true, 900, 50);
		fix(WAY_B, true, 950, 50);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertEquals(WAY_A, passages.get(0).wayId);
		assertSpan(passages.get(0), 0, 100, 150);
		Assert.assertEquals(WAY_B, passages.get(1).wayId);
		// The new road starts at the first fix that named it, not the second.
		assertSpan(passages.get(1), 0, 900, 950);
	}

	@Test
	public void jitterBetweenParallelRoadsProducesNoFragments() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_B, true, 900, 30);
		fix(WAY_A, true, 160, 30);
		fix(WAY_B, true, 960, 30);
		fix(WAY_A, true, 220, 30);
		accumulator.flush();

		Assert.assertEquals("jitter must not create passages on B", 1, passages.size());
		Assert.assertEquals(WAY_A, passages.get(0).wayId);
	}

	@Test
	public void anImpossibleJumpAlongTheSameRoadIsNeverFilledIn() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_A, true, 145, 45);
		// The matcher jumps 800 m along the road while the truck moved 20.
		fix(WAY_A, true, 950, 20);
		fix(WAY_A, true, 995, 45);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		assertSpan(passages.get(0), 0, 100, 145);
		assertSpan(passages.get(1), 0, 950, 995);
		for (RoadCrewDirectPassageAccumulator.Passage passage : passages) {
			for (RoadCrewDirectPassageAccumulator.Span span : passage.spans) {
				Assert.assertTrue("no span may cover the invented stretch",
						span.toMeasureMeters <= 150 || span.fromMeasureMeters >= 950);
			}
		}
	}

	@Test
	public void aFastLorryOnAMotorwayIsStillContinuous() {
		fix(WAY_A, true, 100, 0);
		// Five seconds at motorway speed: 140 m is perfectly ordinary.
		fixAfter(5000, WAY_A, true, 240, 140, false, OPEN_LENGTH);
		accumulator.flush();

		assertSpan(only(), 0, 100, 240);
	}

	@Test
	public void turningRoundOnTheSameRoadIsTwoPassages() {
		fix(WAY_A, true, 100, 0);
		fix(WAY_A, true, 145, 45);
		fix(WAY_A, false, 140, 10);
		fix(WAY_A, false, 95, 45);
		accumulator.flush();

		Assert.assertEquals(2, passages.size());
		Assert.assertTrue(passages.get(0).forward);
		Assert.assertFalse(passages.get(1).forward);
		assertSpan(passages.get(0), 0, 100, 145);
		assertSpan(passages.get(1), 0, 95, 140);
	}

	@Test
	public void wrappingPastTheEndOfARingBecomesTwoAscendingSpans() {
		fixAfter(1000, WAY_A, true, 350, 0, true, RING_LENGTH);
		fixAfter(1000, WAY_A, true, 30, 80, true, RING_LENGTH);
		accumulator.flush();

		RoadCrewDirectPassageAccumulator.Passage passage = only();
		Assert.assertEquals(2, passage.spans.size());
		assertSpan(passage, 0, 350, 400);
		assertSpan(passage, 1, 0, 30);
		Assert.assertEquals(80, passage.progressMeters, 0.5);
	}

	@Test
	public void wrappingBackwardsRoundARingAlsoSplits() {
		fixAfter(1000, WAY_A, false, 30, 0, true, RING_LENGTH);
		fixAfter(1000, WAY_A, false, 350, 80, true, RING_LENGTH);
		accumulator.flush();

		RoadCrewDirectPassageAccumulator.Passage passage = only();
		Assert.assertEquals(2, passage.spans.size());
		assertSpan(passage, 0, 0, 30);
		assertSpan(passage, 1, 350, 400);
	}

	@Test
	public void anApparentWrapThatIsPhysicallyImpossibleIsRejected() {
		fixAfter(1000, WAY_A, true, 350, 0, true, RING_LENGTH);
		// Looks like a small wrap, but the truck moved two metres in one second.
		fixAfter(1000, WAY_A, true, 340, 2, true, RING_LENGTH);
		accumulator.flush();

		// 390 m of progress in one second is refused; no span may claim it.
		for (RoadCrewDirectPassageAccumulator.Passage passage : passages) {
			Assert.assertTrue(passage.progressMeters < 100);
		}
	}

	@Test
	public void smallBackwardJitterDoesNotEndAPassage() {
		fix(WAY_A, true, 500, 0);
		fix(WAY_A, true, 492, 8);
		fix(WAY_A, true, 540, 48);
		accumulator.flush();

		assertSpan(only(), 0, 500, 540);
	}

	@Test
	public void realBackwardMovementUnderTheSameDirectionEndsThePassage() {
		fix(WAY_A, true, 500, 0);
		fix(WAY_A, true, 420, 80);
		accumulator.flush();

		// Eighty metres backwards is not jitter; the passage must not absorb it.
		Assert.assertEquals(0, passages.size());
	}

	@Test
	public void goingRoundARingMoreThanOnceIsNotLimitedToTwoParts() {
		fixAfter(1000, WAY_A, true, 350, 0, true, RING_LENGTH);
		fixAfter(10000, WAY_A, true, 300, 350, true, RING_LENGTH);
		accumulator.flush();

		RoadCrewDirectPassageAccumulator.Passage passage = only();
		Assert.assertTrue("a lap and a half needs more than two spans",
				passage.spans.size() >= 2);
		Assert.assertEquals(350, passage.progressMeters, 0.5);
	}
}
