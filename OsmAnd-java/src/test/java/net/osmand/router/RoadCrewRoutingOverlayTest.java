package net.osmand.router;

import net.osmand.NativeLibrary;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;

import org.junit.Assert;
import org.junit.Test;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

public class RoadCrewRoutingOverlayTest {

	private static final String OVERLAY_JSON = "{\n"
			+ "  \"schemaVersion\": 1,\n"
			+ "  \"revision\": \"local-poc-1\",\n"
			+ "  \"generatedAt\": 900,\n"
			+ "  \"overrides\": [\n"
			+ "    {\"id\":\"block-1\",\"operation\":\"BLOCK_ROAD\",\"profile\":\"truck\",\"validated\":true,\"roadId\":\"1234\","
			+ "\"segmentKey\":{\"version\":1,\"osmWayId\":\"987654321\",\"region\":\"Bulgaria\","
			+ "\"fromLatitude\":43.0,\"fromLongitude\":27.0,\"toLatitude\":43.001,\"toLongitude\":27.001,"
			+ "\"geometryFingerprint\":\"00000000000000000000000000000000\",\"lengthMeters\":137.5}},\n"
			+ "    {\"id\":\"height-1\",\"operation\":\"SET_MAXHEIGHT\",\"direction\":\"FORWARD\",\"profile\":\"truck\",\"validated\":true,\"latitude\":43.2,\"longitude\":27.9,\"value\":3.5,\"directionAngle\":90},\n"
			+ "    {\"id\":\"car-only\",\"operation\":\"BLOCK_ROAD\",\"profile\":\"car\",\"validated\":true,\"roadId\":\"5678\"},\n"
			+ "    {\"id\":\"not-validated\",\"operation\":\"BLOCK_ROAD\",\"profile\":\"truck\",\"validated\":false,\"roadId\":\"99\"},\n"
			+ "    {\"id\":\"expired\",\"operation\":\"BLOCK_ROAD\",\"profile\":\"truck\",\"validated\":true,\"roadId\":\"98\",\"validUntil\":500},\n"
			+ "    {\"id\":\"permissive\",\"operation\":\"REMOVE_HGV_PROHIBITION\",\"profile\":\"truck\",\"validated\":true,\"roadId\":\"97\"}\n"
			+ "  ]\n"
			+ "}";

	@Test
	public void filtersUnsafeExpiredAndOtherProfileOverrides() {
		RoadCrewRoutingOverlay.Snapshot all = RoadCrewRoutingOverlay.parse(new StringReader(OVERLAY_JSON), 1000);
		RoadCrewRoutingOverlay.Snapshot truck = all.forProfile("truck");
		RoadCrewRoutingOverlay.Snapshot car = all.forProfile("car");

		Assert.assertEquals("local-poc-1", truck.getRevision());
		Assert.assertEquals(2, truck.getOverrides().size());
		Assert.assertNotNull(truck.getOverrides().get(0).getSegmentKey());
		Assert.assertEquals(987654321L, truck.getOverrides().get(0).getSegmentKey().getOsmWayId());
		Assert.assertEquals(3, truck.getRejectedCount());
		Assert.assertEquals(1, car.getOverrides().size());
		Assert.assertTrue(all.forProfile("bicycle").isEmpty());
	}

	@Test
	public void appliesRoadBlockAndDirectionalHeightPoint() {
		RoadCrewRoutingOverlay.Snapshot truck = RoadCrewRoutingOverlay
				.parse(new StringReader(OVERLAY_JSON), 1000).forProfile("truck");
		RoutingConfiguration configuration = buildTruckConfiguration(4.0);

		Assert.assertEquals(2, truck.applyTo(configuration));
		Assert.assertArrayEquals(new long[]{1234}, configuration.router.getImpassableRoadIds());
		Assert.assertEquals("local-poc-1", configuration.attributes.get("roadcrewOverlayRevision"));

		NativeLibrary.NativeDirectionPoint[] points = configuration.getNativeDirectionPoints();
		Assert.assertEquals(1, points.length);
		Map<String, String> tags = toMap(points[0].tags);
		Assert.assertEquals("3.5", tags.get("maxheight:forward"));
		Assert.assertEquals("90.0", tags.get(RoutingConfiguration.DirectionPoint.ANGLE_TAG));
	}

	@Test
	public void truckHeightRuleBlocksOnlyConfiguredDirection() {
		RoutingConfiguration configuration = buildTruckConfiguration(4.0);
		RouteRegion region = new RouteRegion();
		region.initRouteEncodingRule(1, "maxheight:forward", "3.5");
		RouteDataObject road = new RouteDataObject(region);
		road.types = new int[0];
		road.pointTypes = new int[][]{{1}};

		Assert.assertTrue(configuration.router.defineRoutingObstacle(road, 0, false) < 0);
		Assert.assertEquals(0, configuration.router.defineRoutingObstacle(road, 0, true), 0.0);
	}

	@Test(expected = com.google.gson.JsonParseException.class)
	public void rejectsUnknownSchema() {
		RoadCrewRoutingOverlay.parse(new StringReader("{\"schemaVersion\":2}"), 1000);
	}

	@Test
	public void rejectsMalformedCanonicalSegmentKey() {
		String json = "{\"schemaVersion\":1,\"overrides\":[{"
				+ "\"id\":\"bad-segment\",\"operation\":\"BLOCK_ROAD\",\"profile\":\"truck\","
				+ "\"validated\":true,\"roadId\":\"1234\",\"segmentKey\":{"
				+ "\"version\":1,\"osmWayId\":\"987654321\",\"region\":\"Bulgaria\","
				+ "\"fromLatitude\":43.0,\"fromLongitude\":27.0,\"toLatitude\":43.001,"
				+ "\"toLongitude\":27.001,\"geometryFingerprint\":\"not-a-hash\",\"lengthMeters\":100}}]}";

		RoadCrewRoutingOverlay.Snapshot snapshot = RoadCrewRoutingOverlay.parse(new StringReader(json), 1000);

		Assert.assertTrue(snapshot.isEmpty());
		Assert.assertEquals(1, snapshot.getRejectedCount());
	}

	private static RoutingConfiguration buildTruckConfiguration(double height) {
		Map<String, String> params = new HashMap<>();
		params.put("height", Double.toString(height));
		return RoutingConfiguration.getDefault().build("truck",
				new RoutingMemoryLimits(64, RoutingConfiguration.DEFAULT_NATIVE_MEMORY_LIMIT), params);
	}

	private static Map<String, String> toMap(String[][] tags) {
		Map<String, String> result = new HashMap<>();
		for (String[] tag : tags) {
			result.put(tag[0], tag[1]);
		}
		return result;
	}
}
