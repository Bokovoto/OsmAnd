package net.osmand.router;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoadCrewValidationStopGateTest {

	@Test public void allowsImmediatelyAfterNavigationEnds() {
		RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
		assertTrue(gate.update(0, true, Long.MAX_VALUE, false, 0, false, 0));
		assertTrue(gate.update(1000, true, 1000, true, 5, true, 100));
	}

	@Test public void gpsDriftAndSuspensionDoNotDelayFinishedCourse() {
		RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
		assertTrue(gate.update(0, true, 1000, true, 0, true, 5));
		assertTrue(gate.update(120_000, true, 1000, true, 2, true, 50));
	}

	@Test public void rejectsOnlyIneligibleApplicationState() {
		RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
		assertFalse(gate.update(0, false, 1000, true, 0, true, 5));
		assertTrue(gate.update(1000, true, -1, false, Float.NaN, false, Float.NaN));
	}
}
