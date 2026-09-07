package net.osmand.router;

import java.util.ArrayDeque;
import java.util.Deque;

/** Serialises upload runs while retaining every immutable final snapshot. */
public final class RoadCrewFinalDiagnosticsQueue {

	private final Deque<String> finalSnapshots = new ArrayDeque<>();
	private boolean running;

	/** @return true when the caller must start the single upload worker. */
	public synchronized boolean requestRun(String finalSnapshot) {
		if (finalSnapshot != null && !finalSnapshot.isEmpty()) {
			finalSnapshots.addLast(finalSnapshot);
		}
		if (running) {
			return false;
		}
		running = true;
		return true;
	}

	public synchronized String pollFinalSnapshot() {
		return finalSnapshots.pollFirst();
	}

	/**
	 * Atomically stops the worker only when no final request arrived after its
	 * last poll. A false result tells the same worker to drain again.
	 */
	public synchronized boolean finishIfIdle() {
		if (!finalSnapshots.isEmpty()) {
			return false;
		}
		running = false;
		return true;
	}
}
