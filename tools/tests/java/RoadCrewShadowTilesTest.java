package net.osmand.plus.roadcrew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// Test 106 (Galin, 2026-09-13): the phone asks for fixed tiles instead of its own
// 30 km box. The grid must be the server's (vectors from truck-map-tiles.ts), the
// 2x2 coverage must hold the phone at least half a tile from the edge, and the
// merge must follow the server's rules. Run with "keys lat,lon ..." to print keys
// for the cross-check against the server code in roadcrew-shadow-tiles.test.mjs.
public final class RoadCrewShadowTilesTest {

	public static void main(String[] args) {
		if (args.length > 0 && args[0].equals("keys")) {
			for (int i = 1; i < args.length; i++) {
				String[] point = args[i].split(",");
				System.out.println(RoadCrewShadowTiles.tileKey(Double.parseDouble(point[0]), Double.parseDouble(point[1])));
			}
			return;
		}
		serverVectors();
		coverageIsTheNearerTwoByTwo();
		coverageShrinksAtTheEdgesOfTheWorld();
		mergeKeepsABorderSegmentOnceAndTheCautiousVersion();
		mergeIsCompleteOnlyWhenEveryRequestedTileIsComplete();
		mergeDoesNotDependOnTileOrder();
		System.out.println("shadow tiles scenarios PASS");
	}

	private static void serverVectors() {
		// backend/roadcrew-api/src/truck-map-tiles.ts, tileKey(tileOf(lat, lon)), 13.09.
		String[][] vectors = {
				{"43.2141", "27.9147", "831_532"}, {"43.25", "27.75", "831_533"},
				{"43.2499999", "27.7499999", "830_532"}, {"0", "0", "720_360"},
				{"-0.0001", "-0.0001", "719_359"}, {"90", "180", "1439_719"},
				{"-90", "-180", "0_0"}, {"42.6977", "23.3219", "813_530"},
				{"44.4268", "26.1025", "824_537"}, {"-33.8688", "151.2093", "1324_224"},
				{"89.99", "-179.99", "0_719"}};
		for (String[] v : vectors) {
			String key = RoadCrewShadowTiles.tileKey(Double.parseDouble(v[0]), Double.parseDouble(v[1]));
			check(key.equals(v[2]), v[0] + "," + v[1] + " -> " + key + ", server says " + v[2]);
		}
		check(Arrays.equals(RoadCrewShadowTiles.bounds("831_532"), new double[] {43, 43.25, 27.75, 28}), "bounds of 831_532");
	}

	private static void coverageIsTheNearerTwoByTwo() {
		// Varna: in the east and north part of 831_532, so the east and north neighbours.
		check(RoadCrewShadowTiles.coverage(43.2141, 27.9147)
				.equals(Arrays.asList("831_532", "832_532", "831_533", "832_533")), "Varna coverage");
		// Sofia 42.6977, 23.3219 is in the north-west part of 813_530 (42.5-42.75, 23.25-23.5).
		check(RoadCrewShadowTiles.coverage(42.6977, 23.3219)
				.equals(Arrays.asList("812_530", "813_530", "812_531", "813_531")), "Sofia coverage");
		// The phone is at least half a tile from the edge of what it holds.
		for (double lat = 42.01; lat < 44; lat += 0.037) {
			for (double lon = 22.01; lon < 29; lon += 0.041) {
				double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
				for (String key : RoadCrewShadowTiles.coverage(lat, lon)) {
					double[] b = RoadCrewShadowTiles.bounds(key);
					minLat = Math.min(minLat, b[0]); maxLat = Math.max(maxLat, b[1]);
					minLon = Math.min(minLon, b[2]); maxLon = Math.max(maxLon, b[3]);
				}
				double margin = Math.min(Math.min(lat - minLat, maxLat - lat), Math.min(lon - minLon, maxLon - lon));
				check(margin >= RoadCrewShadowTiles.TILE_SIZE_DEGREES / 2 - 1e-9, "margin at " + lat + "," + lon + " is " + margin);
			}
		}
	}

	private static void coverageShrinksAtTheEdgesOfTheWorld() {
		check(RoadCrewShadowTiles.coverage(89.99, -179.99).equals(Collections.singletonList("0_719")), "pole and antimeridian");
		check(RoadCrewShadowTiles.coverage(0.01, 179.99).size() == 2, "antimeridian: no wrap");
	}

	private static RoadCrewShadowTiles.Segment segment(String id, boolean eligible, double validUntil) {
		return new RoadCrewShadowTiles.Segment(id, eligible, validUntil,
				"{\"segmentId\":\"" + id + "\",\"eligible\":" + eligible + ",\"validUntil\":" + validUntil + "}");
	}

	private static void mergeKeepsABorderSegmentOnceAndTheCautiousVersion() {
		RoadCrewShadowTiles.Tile a = new RoadCrewShadowTiles.Tile("1_1", 2000, true,
				Arrays.asList(segment("border", true, 9000), segment("only-a", false, Double.POSITIVE_INFINITY)));
		RoadCrewShadowTiles.Tile b = new RoadCrewShadowTiles.Tile("2_1", 1000, true,
				Collections.singletonList(segment("border", false, Double.POSITIVE_INFINITY)));
		RoadCrewShadowTiles.Merge merged = RoadCrewShadowTiles.merge(Arrays.asList("1_1", "2_1"), Arrays.asList(a, b));
		check(merged.segments.size() == 2, "border segment kept once");
		for (RoadCrewShadowTiles.Segment s : merged.segments) {
			if (s.segmentId.equals("border")) check(!s.eligible, "not eligible wins over eligible");
		}
		check(merged.generatedAtMillis == 1000, "as old as the oldest tile");
		check(merged.complete, "both requested tiles complete");
		RoadCrewShadowTiles.Merge earlier = RoadCrewShadowTiles.merge(Collections.singletonList("1_1"), Arrays.asList(
				new RoadCrewShadowTiles.Tile("1_1", 1, true, Arrays.asList(segment("s", true, 9000), segment("s", true, 5000)))));
		check(earlier.segments.get(0).validUntil == 5000, "the earlier validity wins");
	}

	private static void mergeIsCompleteOnlyWhenEveryRequestedTileIsComplete() {
		RoadCrewShadowTiles.Tile full = new RoadCrewShadowTiles.Tile("1_1", 1, true, new ArrayList<>());
		RoadCrewShadowTiles.Tile cut = new RoadCrewShadowTiles.Tile("2_1", 1, false, new ArrayList<>());
		check(!RoadCrewShadowTiles.merge(Arrays.asList("1_1", "2_1"), Arrays.asList(full, cut)).complete, "a tile at its limit");
		check(!RoadCrewShadowTiles.merge(Arrays.asList("1_1", "2_1"), Arrays.asList(full, null)).complete, "a requested tile missing");
		check(RoadCrewShadowTiles.merge(Collections.singletonList("1_1"), Arrays.asList(full, cut)).complete, "a tile not requested is ignored");
		check(RoadCrewShadowTiles.merge(new ArrayList<>(), Arrays.asList(full)).generatedAtMillis == 0, "nothing requested is not an answer");
	}

	private static void mergeDoesNotDependOnTileOrder() {
		RoadCrewShadowTiles.Tile a = new RoadCrewShadowTiles.Tile("1_1", 1, true, Collections.singletonList(segment("s", true, 7000)));
		RoadCrewShadowTiles.Tile b = new RoadCrewShadowTiles.Tile("2_1", 1, true, Collections.singletonList(segment("s", true, 7000.5)));
		List<String> keys = Arrays.asList("1_1", "2_1");
		String one = RoadCrewShadowTiles.merge(keys, Arrays.asList(a, b)).segments.get(0).json;
		String two = RoadCrewShadowTiles.merge(keys, Arrays.asList(b, a)).segments.get(0).json;
		check(one.equals(two), "same result whatever the order");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
