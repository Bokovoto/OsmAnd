package net.osmand.router;

import org.junit.Assert;
import org.junit.Test;

/** Tests for the bounded, exact coverage summary of ROADMAP section 217. */
public class RoadCrewDiagnosticsTest {
	private RoadCrewDiagnostics longCourse(int fixes) {
		RoadCrewDiagnostics diagnostics = new RoadCrewDiagnostics();
		for (int sequence = 1; sequence <= fixes; sequence++) {
			// A different way each time deliberately exhausts the old run sample.
			diagnostics.matched(sequence, sequence, true);
			if (sequence % 10 != 0) {
				diagnostics.passageStarted(sequence);
				diagnostics.passageCovered(sequence, sequence);
			}
		}
		diagnostics.finishCoverage();
		return diagnostics;
	}

	@Test
	public void aHundredThousandFixCourseKeepsExactCoverageInBoundedJson() {
		final int fixes = 100_000;
		RoadCrewDiagnostics diagnostics = longCourse(fixes);

		Assert.assertEquals(fixes, diagnostics.matchedFixCount());
		Assert.assertEquals(90_000, diagnostics.coveredMatchedFixCount());
		Assert.assertEquals(10_000, diagnostics.uncoveredMatchedFixCount());
		Assert.assertEquals(0, diagnostics.pendingMatchedFixCount());
		Assert.assertEquals(10_000, diagnostics.uncoveredRunCount());
		Assert.assertEquals(1, diagnostics.longestUncoveredRun());
		Assert.assertTrue(diagnostics.isCoverageComplete());

		String json = diagnostics.toJson();
		Assert.assertTrue(json.contains("\"diagnosticsSchemaVersion\":2"));
		Assert.assertTrue(json.contains("\"coverageComplete\":true"));
		Assert.assertTrue(json.contains("\"matcherBaselineTruncated\":true"));
		Assert.assertTrue("diagnostics grew with the course: " + json.length(),
				json.length() < 30_000);
		String twiceAsLong = longCourse(fixes * 2).toJson();
		Assert.assertTrue("serialised size followed course length: " + json.length()
				+ " then " + twiceAsLong.length(),
				Math.abs(twiceAsLong.length() - json.length()) < 100);
	}

	@Test
	public void anIntermediateSnapshotCannotPretendToBeFinal() {
		RoadCrewDiagnostics diagnostics = new RoadCrewDiagnostics();
		diagnostics.matched(1, 100, true);
		diagnostics.passageStarted(1);

		Assert.assertFalse(diagnostics.isCoverageComplete());
		Assert.assertEquals(1, diagnostics.pendingMatchedFixCount());
		Assert.assertTrue(diagnostics.toJson().contains("\"coverageComplete\":false"));

		diagnostics.finishCoverage();
		Assert.assertTrue(diagnostics.isCoverageComplete());
		Assert.assertEquals(diagnostics.matchedFixCount(),
				diagnostics.coveredMatchedFixCount()
						+ diagnostics.uncoveredMatchedFixCount());
	}
}
