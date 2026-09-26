package net.osmand.plus.roadcrew.tacho;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * One driver-card session in the order that makes marking safe (ROADMAP 327,
 * 329): read the card, store the DDD (the store reads it back), and only then
 * mark the card as downloaded - automatically, as Galin decided on 25.09.
 *
 * A card that reports an integrity error in its stored data (READ BINARY
 * 6281) is stored for diagnosis but NOT marked: a file read back intact proves
 * the phone kept what it received, not that the chip handed over sound data
 * (Codex's Test 118 review, P1). An optional file the card simply lacks is not
 * such an error. Plain Java, tested on the simulated card.
 */
public final class RoadCrewTachoDownloadFlow {

	public enum Stage { READING, SAVING, MARKING }

	/** Keeps the DDD somewhere the driver can reach it; returns what the screen needs to name it. */
	public interface Store<T> {
		T store(RoadCrewTachoCardDownload.Result result) throws IOException;
	}

	public static final class Outcome<T> {
		public final RoadCrewTachoCardDownload.Result result;
		public final T stored;
		public final boolean marked;
		/** Seconds since 1970 written as LastCardDownload; 0 when not marked. */
		public final long markedAt;
		/** Why the card was not marked; empty when it was. */
		public final List<String> integrityWarnings;

		Outcome(RoadCrewTachoCardDownload.Result result, T stored, boolean marked, long markedAt,
				List<String> integrityWarnings) {
			this.result = result;
			this.stored = stored;
			this.marked = marked;
			this.markedAt = markedAt;
			this.integrityWarnings = Collections.unmodifiableList(integrityWarnings);
		}
	}

	/** A session that stopped, with the stage it stopped in and anything already stored. */
	public static final class Failure extends IOException {
		public final Stage stage;
		public final Object stored;

		Failure(Stage stage, Object stored, IOException cause) {
			super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
			this.stage = stage;
			this.stored = stored;
		}
	}

	private RoadCrewTachoDownloadFlow() {
	}

	public static <T> Outcome<T> run(RoadCrewTachoDownloadDate.Channel channel,
			RoadCrewTachoCardDownload.Progress progress, Store<T> store, LongSupplier nowSeconds,
			RoadCrewTachoDownloadDate.BeforeWrite audit) throws Failure {
		RoadCrewTachoCardDownload.Result result;
		try {
			result = RoadCrewTachoCardDownload.download(channel, progress);
		} catch (IOException e) {
			throw new Failure(Stage.READING, null, e);
		}
		T stored;
		try {
			stored = store.store(result);
		} catch (IOException e) {
			throw new Failure(Stage.SAVING, null, e);
		}
		if (!result.warnings.isEmpty()) {
			return new Outcome<>(result, stored, false, 0, result.warnings);
		}
		long now = nowSeconds.getAsLong();
		try {
			RoadCrewTachoCardDownload.markDownloaded(channel, now, result.secondGeneration, audit);
		} catch (IOException e) {
			throw new Failure(Stage.MARKING, stored, e);
		}
		return new Outcome<>(result, stored, true, now, Collections.emptyList());
	}

	/**
	 * The adb triggers (download, trace) exist for development builds only. The
	 * activity is exported for the USB reader; in a release another app must
	 * not be able to start a download that marks the card (Codex, Test 118 P1).
	 */
	public static boolean acceptsAutomation(String versionName) {
		return versionName != null && versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+-dev\\..*");
	}
}
