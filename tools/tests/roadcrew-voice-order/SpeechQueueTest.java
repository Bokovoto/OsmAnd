package net.osmand.plus.roadcrew;

/**
 * Galin, 06.10.2026: two announcements must never talk over each other - the
 * second waits and is said right after the first ("Изчаква, после се казва"),
 * dropped only when it no longer applies (the camera is already passed).
 */
public class SpeechQueueTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	public static void main(String[] args) {
		RoadCrewSpeechQueue queue = new RoadCrewSpeechQueue();
		long now = 1_000_000L;
		check(queue.isEmpty(), "empty at first");
		check(queue.next(false, now) == null, "nothing to say");

		// The navigation is speaking: the warning waits, then follows it.
		queue.add("ка̀мера наблизо.", now, () -> true);
		check(queue.next(true, now + 500) == null, "the navigation speaks: wait");
		check(!queue.isEmpty(), "still waiting");
		check("ка̀мера наблизо.".equals(queue.next(false, now + 2_000)), "the navigation done: said right after");
		check(queue.isEmpty(), "said once");

		// In the order they came.
		queue.add("first", now, () -> true);
		queue.add("second", now, () -> true);
		check("first".equals(queue.next(false, now)), "first in, first said");
		check("second".equals(queue.next(false, now)), "then the second");

		// No longer true: the camera was passed while waiting.
		boolean[] ahead = {true};
		queue.add("camera", now, () -> ahead[0]);
		ahead[0] = false;
		check(queue.next(false, now + 1_000) == null, "passed while waiting: dropped");
		check(queue.isEmpty(), "and gone");

		// Waited too long: a warning that late says the wrong thing.
		queue.add("late", now, () -> true);
		check(queue.next(true, now + RoadCrewSpeechQueue.WAIT_MILLIS - 1) == null, "still waiting just before the limit");
		check(queue.next(false, now + RoadCrewSpeechQueue.WAIT_MILLIS + 1) == null, "past the limit: dropped");
		check(queue.isEmpty(), "and gone");

		System.out.println(passed + " speech order checks passed");
	}
}
