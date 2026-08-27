package net.osmand.router;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoadCrewValidationStopGateTest {

	@Test public void waitsForContinuousStop() {
		RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
		for (long time = 0; time < 30_000; time += 1000) {
			assertFalse(gate.update(time, true, 1000, true, 0, true, 5));
		}
		assertFalse(gate.update(29_999, true, 1000, true, 0, true, 5));
		assertTrue(gate.update(30_000, true, 1000, true, 0, true, 5));
		assertFalse(gate.update(31_000, true, 1000, true, 1, true, 5));
		assertFalse(gate.update(32_000, true, 1000, true, 0, true, 5));
	}

	@Test public void doesNotCountSuspendedTimeAsConfirmedStandstill() {
		RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
		for (long time = 0; time <= 30_000; time += 1000) {
			gate.update(time, true, 1000, true, 0, true, 5);
		}
		assertTrue(gate.update(31_000, true, 1000, true, 0, true, 5));
		assertFalse(gate.update(120_000, true, 1000, true, 0, true, 5));
		for (long time = 121_000; time < 150_000; time += 1000) {
			assertFalse(gate.update(time, true, 1000, true, 0, true, 5));
		}
		assertTrue(gate.update(150_000, true, 1000, true, 0, true, 5));
	}

	@Test public void rejectsStaleUnknownAndSimulatedFixes() {
		RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
		assertFalse(gate.update(0, true, 1000, true, 0, true, 5));
		assertFalse(gate.update(31_000, true, 11_000, true, 0, true, 5));
		assertFalse(gate.update(62_000, true, -1, true, 0, true, 5));
		assertFalse(gate.update(93_000, true, 1000, false, 0, true, 5));
		assertFalse(gate.update(124_000, true, 1000, true, 0, true, Float.NaN));
		assertFalse(gate.update(155_000, false, 1000, true, 0, true, 5));
	}
}
