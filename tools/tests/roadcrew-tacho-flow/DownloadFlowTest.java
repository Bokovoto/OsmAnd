import net.osmand.plus.roadcrew.tacho.RoadCrewTachoCardDownload;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDownloadDate;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDownloadFlow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The whole driver-card session - read, store, mark - on the simulated card
 * (ROADMAP 327/329, Codex's Test 118 review P1): a clean card is stored and
 * then marked; a card that reports an integrity error (6281) is stored for
 * diagnosis but never written to; each failure names its stage; the
 * development-only automation never works in a release build.
 */
public class DownloadFlowTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	/** EF 0504's READ BINARY answers 6281 (data kept), as in Codex's TachoIntegrityProbe. */
	static RoadCrewTachoDownloadDate.Channel damaged(CardDownloadTest.Card card) {
		return command -> {
			byte[] answer = card.exchange(command);
			if ((command[1] & 0xFF) == 0xB0 && Integer.valueOf(0x0504).equals(card.currentEf)
					&& answer.length > 2 && (answer[answer.length - 2] & 0xFF) == 0x90
					&& answer[answer.length - 1] == 0) {
				answer[answer.length - 2] = 0x62;
				answer[answer.length - 1] = (byte) 0x81;
			}
			return answer;
		};
	}

	public static void main(String[] args) throws Exception {
		long now = 0x68D50000L;

		// Clean generation 1 card: stored, then marked once.
		CardDownloadTest.Card g1 = CardDownloadTest.gen1Card();
		List<String> events = new ArrayList<>();
		RoadCrewTachoDownloadFlow.Outcome<String> clean = RoadCrewTachoDownloadFlow.run(g1,
				RoadCrewTachoCardDownload.Progress.NONE,
				result -> { events.add("store@" + g1.writes); return "file-g1"; },
				() -> now, (before, requested) -> events.add("audit@" + g1.writes));
		check(clean.marked && clean.markedAt == now, "clean G1: marked at the clock's time");
		check("file-g1".equals(clean.stored), "clean G1: the stored file is returned");
		check(g1.writes == 1, "clean G1: one UPDATE BINARY");
		check(events.equals(Arrays.asList("store@0", "audit@0")), "stored before the audit, audit before the write: " + events);
		check(clean.integrityWarnings.isEmpty(), "clean G1: no integrity warnings");

		// Clean generation 2 card (the working card's twin): both DFs marked.
		CardDownloadTest.Card twin = CardDownloadTest.workingCardTwin();
		RoadCrewTachoDownloadFlow.Outcome<String> g2 = RoadCrewTachoDownloadFlow.run(twin,
				RoadCrewTachoCardDownload.Progress.NONE, result -> "file-g2", () -> now, (b, r) -> { });
		check(g2.marked && twin.writes == 2, "clean G2: Tachograph and Tachograph_G2 marked");

		// 6281: the file is kept, the card is not touched, the warnings are handed on.
		CardDownloadTest.Card bad = CardDownloadTest.gen1Card();
		List<byte[]> kept = new ArrayList<>();
		List<String> audits = new ArrayList<>();
		RoadCrewTachoDownloadFlow.Outcome<String> warned = RoadCrewTachoDownloadFlow.run(damaged(bad),
				RoadCrewTachoCardDownload.Progress.NONE,
				result -> { kept.add(result.ddd); return "file-damaged"; },
				() -> now, (before, requested) -> audits.add("audit"));
		check(kept.size() == 1 && kept.get(0).length > 0, "6281: the downloaded bytes are stored for diagnosis");
		check(!warned.marked && warned.markedAt == 0, "6281: not marked");
		check(bad.writes == 0, "6281: zero writes to the card");
		check(audits.isEmpty(), "6281: no marking was even prepared");
		check(!warned.integrityWarnings.isEmpty() && warned.integrityWarnings.get(0).contains("6281"),
				"6281: the warning is handed to the screen");
		check("file-damaged".equals(warned.stored), "6281: the stored file is still offered");

		// An optional file the card lacks is not an integrity problem: still marked.
		CardDownloadTest.Card noOptional = CardDownloadTest.gen1Card();
		noOptional.g1.remove(0x0522);
		RoadCrewTachoDownloadFlow.Outcome<String> partial = RoadCrewTachoDownloadFlow.run(noOptional,
				RoadCrewTachoCardDownload.Progress.NONE, result -> "file", () -> now, (b, r) -> { });
		check(partial.marked && noOptional.writes == 1, "absent optional EF: marked as before");

		// Storing fails: nothing written, stage SAVING.
		CardDownloadTest.Card unsaved = CardDownloadTest.gen1Card();
		try {
			RoadCrewTachoDownloadFlow.run(unsaved, RoadCrewTachoCardDownload.Progress.NONE,
					result -> { throw new IOException("disk full"); }, () -> now, (b, r) -> { });
			check(false, "a storage failure must fail the session");
		} catch (RoadCrewTachoDownloadFlow.Failure failure) {
			check(failure.stage == RoadCrewTachoDownloadFlow.Stage.SAVING, "storage failure: stage SAVING");
			check(failure.stored == null, "storage failure: nothing stored");
			check(unsaved.writes == 0, "storage failure: zero writes");
		}

		// Marking refused (the card holds a later date): the file is stored, stage MARKING.
		CardDownloadTest.Card later = CardDownloadTest.gen1Card();
		later.g1.put(0x050E, new byte[]{(byte) 0x7F, 0, 0, 0});
		try {
			RoadCrewTachoDownloadFlow.run(later, RoadCrewTachoCardDownload.Progress.NONE,
					result -> "file-later", () -> now, (b, r) -> { });
			check(false, "a refused marking must fail the session");
		} catch (RoadCrewTachoDownloadFlow.Failure failure) {
			check(failure.stage == RoadCrewTachoDownloadFlow.Stage.MARKING, "refused marking: stage MARKING");
			check("file-later".equals(failure.stored), "refused marking: the stored file is named");
			check(later.writes == 0, "refused marking: zero writes");
		}

		// Reading fails: never stored, stage READING.
		CardDownloadTest.Card company = CardDownloadTest.gen1Card();
		company.g1.put(0x0501, CardDownloadTest.applicationIdentification(4, 10));
		List<String> stores = new ArrayList<>();
		try {
			RoadCrewTachoDownloadFlow.run(company, RoadCrewTachoCardDownload.Progress.NONE,
					result -> { stores.add("store"); return "x"; }, () -> now, (b, r) -> { });
			check(false, "a non-driver card must fail the session");
		} catch (RoadCrewTachoDownloadFlow.Failure failure) {
			check(failure.stage == RoadCrewTachoDownloadFlow.Stage.READING, "read failure: stage READING");
			check(stores.isEmpty() && company.writes == 0, "read failure: nothing stored, nothing written");
		}

		// Automation over adb exists for development builds only (Codex P1: an exported
		// activity must not let another app start a download that marks the card).
		check(RoadCrewTachoDownloadFlow.acceptsAutomation("0.1.0-dev.rcs2.19"), "dev build: automation accepted");
		check(!RoadCrewTachoDownloadFlow.acceptsAutomation("0.1.0-test.118"), "release: automation refused");
		check(!RoadCrewTachoDownloadFlow.acceptsAutomation("0.1.0"), "plain release: refused");
		check(!RoadCrewTachoDownloadFlow.acceptsAutomation(null), "unknown version: refused");
		check(!RoadCrewTachoDownloadFlow.acceptsAutomation("0.1.0-test.118-dev.notes"), "'-dev.' must be the build kind, not any substring");

		System.out.println(passed + " download flow checks passed");
	}
}
