package net.osmand.router;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The same golden vectors the RoadCrew Worker's TypeScript test reads
 * (backend/roadcrew-api/test/canonicalization-v1-vectors.json, copied here as a
 * test resource). The TypeScript implementation is the specification; this test
 * is what makes it executable in Java.
 *
 * Fingerprints must match exactly - they are the identity. Measures are
 * compared within the tolerance, because Java and V8 differ in the last bits of
 * the trigonometric functions.
 *
 * Regenerate the file with
 * backend/roadcrew-api/tools/build-canonicalization-vectors.mjs and copy it into
 * both places. If the two implementations ever disagree, one of them is wrong
 * and neither should ship.
 */
public class RoadCrewWayCanonicalTest {

	private JsonObject loadSuite() throws Exception {
		try (InputStream stream = getClass().getClassLoader()
				.getResourceAsStream("roadcrew/canonicalization-v1-vectors.json")) {
			Assert.assertNotNull("canonicalization-v1-vectors.json is missing from test resources",
					stream);
			return JsonParser.parseReader(
					new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private int[] readIntArray(JsonArray points, int component) {
		int[] values = new int[points.size()];
		for (int index = 0; index < points.size(); index++) {
			values[index] = points.get(index).getAsJsonArray().get(component).getAsInt();
		}
		return values;
	}

	@Test
	public void goldenVectorsDescribeThePinnedAlgorithms() throws Exception {
		JsonObject suite = loadSuite();
		Assert.assertEquals(RoadCrewWayCanonical.FINGERPRINT_ALGORITHM,
				suite.get("fingerprintAlgorithm").getAsInt());
		Assert.assertEquals(RoadCrewWayCanonical.MEASURE_ALGORITHM,
				suite.get("measureAlgorithm").getAsInt());
		Assert.assertTrue("too few vectors to be worth trusting",
				suite.getAsJsonArray("vectors").size() >= 10);
	}

	@Test
	public void javaReproducesEveryVector() throws Exception {
		JsonObject suite = loadSuite();
		double tolerance = suite.get("measureToleranceMeters").getAsDouble();
		Assert.assertEquals(RoadCrewWayCanonical.MEASURE_TOLERANCE_METERS, tolerance, 1e-9);

		for (JsonElement element : suite.getAsJsonArray("vectors")) {
			JsonObject vector = element.getAsJsonObject();
			String name = vector.get("name").getAsString();
			JsonArray points = vector.getAsJsonArray("points");
			int[] rawX = readIntArray(points, 0);
			int[] rawY = readIntArray(points, 1);
			JsonObject expected = vector.getAsJsonObject("expected");

			RoadCrewWayCanonical.CanonicalWay way = RoadCrewWayCanonical.canonicalise(rawX, rawY);

			Assert.assertEquals(name + ": closed",
					expected.get("closed").getAsBoolean(), way.closed);
			Assert.assertEquals(name + ": reversed",
					expected.get("reversed").getAsBoolean(), way.reversed);
			Assert.assertEquals(name + ": startIndex",
					expected.get("startIndex").getAsInt(), way.startIndex);
			Assert.assertEquals(name + ": pointCount",
					expected.get("pointCount").getAsInt(), way.getPointCount());
			Assert.assertEquals(name + ": canonicalOneway",
					expected.get("canonicalOneway").getAsInt(),
					RoadCrewWayCanonical.canonicalOneway(
							vector.get("rawOneway").getAsInt(), way.reversed));

			// Exactly: a fingerprint is the identity, not an approximation of it.
			Assert.assertEquals(name + ": canonical fingerprint",
					expected.get("canonicalFingerprint").getAsString(),
					RoadCrewWayCanonical.canonicalFingerprint(way));
			Assert.assertEquals(name + ": source fingerprint",
					expected.get("sourceFingerprint").getAsString(),
					RoadCrewWayCanonical.sourceFingerprint(rawX, rawY));

			Assert.assertEquals(name + ": length",
					expected.get("lengthMeters").getAsDouble(), way.lengthMeters, tolerance);
			Assert.assertEquals(name + ": rawStartMeasure",
					expected.get("rawStartMeasure").getAsDouble(), way.rawStartMeasure, tolerance);

			for (JsonElement measureElement : expected.getAsJsonArray("measures")) {
				JsonObject measure = measureElement.getAsJsonObject();
				Assert.assertEquals(name + ": measure of " + measure.get("rawMeasure").getAsDouble(),
						measure.get("canonicalMeasure").getAsDouble(),
						RoadCrewWayCanonical.canonicalMeasure(
								measure.get("rawMeasure").getAsDouble(), way),
						tolerance);
			}
		}
	}

	@Test
	public void aWayNeedsAtLeastTwoPoints() {
		try {
			RoadCrewWayCanonical.canonicalise(new int[] {1}, new int[] {1});
			Assert.fail("a single point is not a way");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(expected.getMessage().contains("at least two points"));
		}
	}

	@Test
	public void aTwoWayRoadNeverBecomesNegativeZero() {
		// Negative zero survives JSON as zero but compares unequal to it, which
		// would make an ordinary two-way road look like a special case. The
		// TypeScript implementation had exactly this defect before it was found.
		Assert.assertEquals(0, RoadCrewWayCanonical.canonicalOneway(0, true));
		Assert.assertEquals(0, RoadCrewWayCanonical.canonicalOneway(0, false));
		Assert.assertEquals(-1, RoadCrewWayCanonical.canonicalOneway(1, true));
		Assert.assertEquals(1, RoadCrewWayCanonical.canonicalOneway(-1, true));
	}
}
