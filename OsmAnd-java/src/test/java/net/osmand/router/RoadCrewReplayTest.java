package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a recorded drive through the pipeline (ROADMAP section 184).
 *
 *   ROADCREW_REPLAY_RECORDING=...\9f8c1a2b-....jsonl
 *   ROADCREW_TEST_OBF=D:\...\Bulgaria_europe_2.obf
 *   gradlew :OsmAnd-java:test --tests "*RoadCrewReplayTest*" -i
 *
 * With one recording and one map, the only variable left is the code. Two
 * builds must agree exactly on what the matcher resolved - the same
 * `fixes_seen`, the same `matched_fixes`, the same `roads_loaded` - because
 * none of that depends on the change being tested. A difference of one is worth
 * investigating, not rounding away: it would mean something upstream is not
 * deterministic, and every comparison afterwards would rest on it.
 *
 * And the same is true one step further on. The fault of section 183 lived
 * AFTER a passage was complete: the accumulator's decision was correct and the
 * passage was discarded on the way to becoming an observation. So the two builds
 * must also agree on every PASSAGE - the same way, direction, fix range, count,
 * duration, progress and spans - and differ only in how many observations
 * survived. If the segmentation moves as well, something besides that fix is at
 * work and the experiment has isolated nothing.
 *
 *   ROADCREW_REPLAY_PASSAGES_OUT=...	est86-passages.txt      (record a baseline)
 *   ROADCREW_REPLAY_PASSAGES_EXPECT=...	est86-passages.txt   (compare against it)
 */
public class RoadCrewReplayTest {

	@Test
	public void replayARecordedDrive() throws Exception {
		String recordingPath = System.getenv("ROADCREW_REPLAY_RECORDING");
		String mapPath = System.getenv("ROADCREW_TEST_OBF");
		File recording = recordingPath == null ? null : new File(recordingPath);
		File map = mapPath == null ? null : new File(mapPath);
		Assume.assumeTrue("Set ROADCREW_REPLAY_RECORDING and ROADCREW_TEST_OBF to replay a drive",
				recording != null && recording.isFile() && map != null && map.isFile());

		List<RoadCrewReplay.RecordedFix> fixes = RoadCrewReplay.read(recording);
		Assert.assertFalse("the recording holds no fixes", fixes.isEmpty());

		try (RandomAccessFile file = new RandomAccessFile(map, "r")) {
			RoadCrewReplay.Result result = RoadCrewReplay.run(fixes,
					new BinaryMapIndexReader[]{new BinaryMapIndexReader(file, map)},
					900, 350, 60_000, 8_000);

			List<String> fingerprint = result.passageFingerprint();
			String out = System.getenv("ROADCREW_REPLAY_PASSAGES_OUT");
			if (out != null && !out.isEmpty()) {
				Files.write(new File(out).toPath(), fingerprint, StandardCharsets.UTF_8);
			}

			int matched = result.diagnostics.counter("matched_fixes");
			int seen = result.diagnostics.counter("fixes_seen");
			System.out.println();
			System.out.println("Replay of " + recording.getName());
			System.out.println("------------------------------------------------------------");
			System.out.println("  fixes replayed        " + result.fixesReplayed);
			System.out.println("  fixes seen            " + seen);
			System.out.println("  matched fixes         " + matched);
			System.out.println("  matcher coverage      "
					+ (seen == 0 ? "-" : Math.round(1000.0 * matched / seen) / 10.0 + "%"));
			System.out.println("  rcs1 observations     " + result.legacyObservations);
			System.out.println("  rcs2 observations     " + result.directed.size());
			System.out.println("  rcs2 recall           "
					+ Math.round(1000.0 * result.directedRecall()) / 10.0 + "%");
			for (String name : new String[]{"roads_loaded", "load_truncated", "pipeline_reset",
					"passages_started", "passages_emitted", "observations_created",
					"observations_dropped_no_geometry", "observations_dropped_geometry_mismatch",
					"observations_dropped_no_span", "no_match", "missing_road", "missing_way_id",
					"invalid_indices", "canonicalisation_failed"}) {
				System.out.println("  " + pad(name) + result.diagnostics.counter(name));
			}
			System.out.println("------------------------------------------------------------");

			String expect = System.getenv("ROADCREW_REPLAY_PASSAGES_EXPECT");
			if (expect != null && !expect.isEmpty()) {
				List<String> baseline = Files.readAllLines(new File(expect).toPath(),
						StandardCharsets.UTF_8);
				// Line by line, so a report names WHICH passage moved rather than
				// only that the counts differ. Two builds that segment the drive
				// differently are not a controlled experiment about anything.
				Assert.assertEquals("the two builds must segment the drive identically",
						baseline, fingerprint);
			}

			// The invariant the fix of section 183 is about: nothing the
			// accumulator emits may vanish on the way to an observation.
			Assert.assertEquals("every emitted passage must become an observation",
					result.diagnostics.counter("passages_emitted"),
					result.diagnostics.counter("observations_created"));
			Assert.assertEquals(0, result.diagnostics.counter("observations_dropped_no_geometry"));
			Assert.assertEquals(0,
					result.diagnostics.counter("observations_dropped_geometry_mismatch"));
		}
	}

	/**
	 * The gate the diagnostic instrument had to pass before it was allowed near
	 * the eleven uncovered fixes (ROADMAP section 203).
	 *
	 * An instrument that changes what it measures is not an instrument. The same
	 * recording is replayed with the decision trace off and on, and every
	 * passage must come out identical - way, direction, fix range, count,
	 * duration, progress, worst match, spans - along with the matcher's own
	 * totals. A single character of difference and the instrumentation goes
	 * back, however useful its output looked.
	 */
	@Test
	public void tracingDecisionsChangesNothingItMeasures() throws Exception {
		String recordingPath = System.getenv("ROADCREW_REPLAY_RECORDING");
		String mapPath = System.getenv("ROADCREW_TEST_OBF");
		File recording = recordingPath == null ? null : new File(recordingPath);
		File map = mapPath == null ? null : new File(mapPath);
		Assume.assumeTrue("Set ROADCREW_REPLAY_RECORDING and ROADCREW_TEST_OBF",
				recording != null && recording.isFile() && map != null && map.isFile());

		List<RoadCrewReplay.RecordedFix> fixes = RoadCrewReplay.read(recording);
		RoadCrewReplay.Result off;
		RoadCrewReplay.Result on;
		try (RandomAccessFile file = new RandomAccessFile(map, "r")) {
			off = RoadCrewReplay.run(fixes,
					new BinaryMapIndexReader[]{new BinaryMapIndexReader(file, map)},
					900, 350, 60_000, 8_000, false);
		}
		try (RandomAccessFile file = new RandomAccessFile(map, "r")) {
			on = RoadCrewReplay.run(fixes,
					new BinaryMapIndexReader[]{new BinaryMapIndexReader(file, map)},
					900, 350, 60_000, 8_000, true);
		}

		Assert.assertEquals("matched fixes", off.diagnostics.counter("matched_fixes"),
				on.diagnostics.counter("matched_fixes"));
		Assert.assertEquals("fixes seen", off.diagnostics.counter("fixes_seen"),
				on.diagnostics.counter("fixes_seen"));
		Assert.assertEquals("passages emitted", off.diagnostics.counter("passages_emitted"),
				on.diagnostics.counter("passages_emitted"));
		Assert.assertEquals("observations created",
				off.diagnostics.counter("observations_created"),
				on.diagnostics.counter("observations_created"));
		Assert.assertEquals("every passage identical",
				off.passageFingerprint(), on.passageFingerprint());
		Assert.assertTrue("the trace recorded nothing while off", off.fixTrace.isEmpty());
		Assert.assertFalse("the trace recorded nothing while on", on.fixTrace.isEmpty());
		System.out.println();
		System.out.println("Trace off against on: " + off.passageFingerprint().size()
				+ " passages identical, " + on.fixTrace.size() + " decisions recorded");
	}

	/**
	 * Step 5 of the agreed order: which matched fixes the pipeline did not cover,
	 * and what the accumulator decided at each of them (ROADMAP sections 199-200).
	 *
	 * Everything here is computed the way the server report computes it - the
	 * matcher baseline minus what the passages covered - so that the replay's
	 * answer can be checked against the frozen set before a single fix is
	 * classified. If the two disagree, the replay is describing a different drive
	 * and nothing it says about the eleven is worth reading.
	 *
	 * Deliberately test-only. Nothing in the shipped classes changes for it, so
	 * the immutability gate of section 203 still holds without being re-run.
	 */
	@Test
	public void whichMatchedFixesThePipelineDidNotCover() throws Exception {
		String recordingPath = System.getenv("ROADCREW_REPLAY_RECORDING");
		String mapPath = System.getenv("ROADCREW_TEST_OBF");
		String out = System.getenv("ROADCREW_UNCOVERED_OUT");
		File recording = recordingPath == null ? null : new File(recordingPath);
		File map = mapPath == null ? null : new File(mapPath);
		Assume.assumeTrue("Set ROADCREW_REPLAY_RECORDING, ROADCREW_TEST_OBF and"
						+ " ROADCREW_UNCOVERED_OUT",
				recording != null && recording.isFile() && map != null && map.isFile()
						&& out != null && !out.isEmpty());

		List<RoadCrewReplay.RecordedFix> fixes = RoadCrewReplay.read(recording);
		RoadCrewReplay.Result result;
		try (RandomAccessFile file = new RandomAccessFile(map, "r")) {
			result = RoadCrewReplay.run(fixes,
					new BinaryMapIndexReader[]{new BinaryMapIndexReader(file, map)},
					900, 350, 60_000, 8_000, true);
		}

		// The baseline, read out of the diagnostics JSON rather than through a
		// new accessor: no shipped class needs to change for a diagnostic.
		boolean[] matched = new boolean[fixes.size() + 2];
		// Scanned rather than matched with a regular expression. Three separate
		// escaping layers mangled the pattern today; a diagnostic is not worth
		// fighting them for. The format is "baseline":[[from,to,way,forward],...].
		String json = result.diagnostics.toJson();
		int baselineAt = json.indexOf("\"baseline\":[");
		if (baselineAt >= 0) {
			int closes = json.indexOf("]]", baselineAt);
			String body = closes < 0 ? ""
					: json.substring(json.indexOf('[', baselineAt + 11) + 1, closes + 1);
			for (String run : body.replace("],[", ";").split(";")) {
				String[] parts = run.replace("[", "").replace("]", "").split(",");
				if (parts.length < 2) {
					continue;
				}
				int from = Integer.parseInt(parts[0].trim());
				int to = Integer.parseInt(parts[1].trim());
				for (int one = from; one <= to && one < matched.length; one++) {
					matched[one] = true;
				}
			}
		}
		boolean[] covered = new boolean[matched.length];
		for (RoadCrewDirectPassageAccumulator.Passage passage : result.passages) {
			for (long at = passage.firstFixSequence;
					at <= passage.lastFixSequence && at < covered.length; at++) {
				covered[(int) at] = true;
			}
		}

		List<Integer> uncovered = new ArrayList<>();
		for (int at = 1; at < matched.length; at++) {
			if (matched[at] && !covered[at]) {
				uncovered.add(at);
			}
		}

		List<String> lines = new ArrayList<>();
		lines.add("recording " + recording.getName());
		lines.add("fixes " + fixes.size()
				+ "  matched " + result.diagnostics.counter("matched_fixes")
				+ "  passages " + result.passages.size()
				+ "  observations " + result.directed.size());
		lines.add("uncovered matched fixes: " + uncovered.size() + " " + uncovered);
		lines.add("");
		for (int fix : uncovered) {
			lines.add("--- fix " + fix + " ---");
			// Everything the accumulator recorded within five fixes either side,
			// which is what the categories of section 200 are decided from.
			for (String note : result.fixTrace) {
				int space = note.indexOf(' ');
				int at = Integer.parseInt(note.substring(0, space));
				if (Math.abs(at - fix) <= 5) {
					lines.add((at == fix ? "  >> " : "     ") + note);
				}
			}
			lines.add("");
		}
		lines.add("=== every decision recorded ===");
		lines.addAll(result.fixTrace);
		Files.write(new File(out).toPath(), lines, StandardCharsets.UTF_8);

		System.out.println();
		System.out.println("Uncovered matched fixes: " + uncovered.size() + " " + uncovered);
		System.out.println("Decisions recorded: " + result.fixTrace.size());
	}

	private static String pad(String name) {
		StringBuilder padded = new StringBuilder(name);
		while (padded.length() < 22) {
			padded.append(' ');
		}
		return padded.toString();
	}
}
