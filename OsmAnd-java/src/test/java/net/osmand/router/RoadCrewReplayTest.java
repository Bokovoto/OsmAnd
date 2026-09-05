package net.osmand.router;

import net.osmand.binary.BinaryMapIndexReader;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
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

	private static String pad(String name) {
		StringBuilder padded = new StringBuilder(name);
		while (padded.length() < 22) {
			padded.append(' ');
		}
		return padded.toString();
	}
}
