package net.osmand.plus.roadcrew;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * RoadCrew's warnings in the order they came, each said only while the
 * navigation's voice is silent. Plain Java, no Android -
 * tools/tests/roadcrew-voice-order.test.mjs.
 *
 * Galin, 06.10.2026: the navigation and RoadCrew spoke at once in the route
 * simulation; asked, he chose "Изчаква, после се казва" - the second waits and
 * follows the first, and is dropped only when it no longer applies.
 */
final class RoadCrewSpeechQueue {

	/** Longer than this behind the other voice, a warning would come too late. */
	static final long WAIT_MILLIS = 10_000;

	private static final class Pending {
		final String text;
		final long since;
		final BooleanSupplier stillTrue;

		Pending(String text, long since, BooleanSupplier stillTrue) {
			this.text = text;
			this.since = since;
			this.stillTrue = stillTrue;
		}
	}

	private final Deque<Pending> pending = new ArrayDeque<>();

	/** stillTrue says whether the warning still applies - a camera still ahead. */
	synchronized void add(String text, long now, BooleanSupplier stillTrue) {
		pending.addLast(new Pending(text, now, stillTrue));
	}

	/**
	 * The text to say now, or null: nothing while the navigation speaks. What
	 * waited too long or no longer applies is dropped, never said late.
	 */
	synchronized String next(boolean navigationSpeaking, long now) {
		while (!pending.isEmpty()) {
			Pending first = pending.peekFirst();
			if (now - first.since > WAIT_MILLIS || !first.stillTrue.getAsBoolean()) {
				pending.removeFirst();
				continue;
			}
			if (navigationSpeaking) {
				return null;
			}
			pending.removeFirst();
			return first.text;
		}
		return null;
	}

	synchronized boolean isEmpty() {
		return pending.isEmpty();
	}

	synchronized void clear() {
		pending.clear();
	}
}
