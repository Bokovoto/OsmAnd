package net.osmand.plus.roadcrew;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The truck-map tiles on the phone: the same fixed 0.25-degree grid as
 * backend/roadcrew-api/src/truck-map-tiles.ts, and the same merge rules.
 *
 * Until Test 105 every phone asked for its own 30 km box (shadow-segments),
 * and every such request scanned the segment table - about 4,700 rows each on
 * 13.09, the largest reader of the database. A tile is built once per 15-minute
 * epoch and served from cache to every phone in the area, so the database load
 * follows the number of areas, not the number of phones. No Android types here,
 * so the rules are tested on a desktop JVM (tools/tests).
 */
final class RoadCrewShadowTiles {

	static final double TILE_SIZE_DEGREES = 0.25;
	static final long TILE_EPOCH_MILLIS = 15 * 60_000L;
	static final int MAX_SEGMENTS_PER_TILE = 500;
	private static final int LAT_ROWS = 720;
	private static final int LON_COLUMNS = 1440;

	private RoadCrewShadowTiles() {
	}

	static int row(double latitude) {
		return Math.min(LAT_ROWS - 1, Math.max(0, (int) Math.floor((latitude + 90) / TILE_SIZE_DEGREES)));
	}

	static int column(double longitude) {
		return Math.min(LON_COLUMNS - 1, Math.max(0, (int) Math.floor((longitude + 180) / TILE_SIZE_DEGREES)));
	}

	static String key(int x, int y) {
		return x + "_" + y;
	}

	/** The one tile a point belongs to; a point on a border goes to the tile that starts there. */
	static String tileKey(double latitude, double longitude) {
		return key(column(longitude), row(latitude));
	}

	/**
	 * The 2x2 block around the phone: its own tile and the neighbours on the nearer
	 * side each way, so the phone is at least half a tile (about 10 km east-west,
	 * 14 km north-south) from the edge of what it holds. At the poles and the
	 * antimeridian the block is smaller rather than wrapped.
	 */
	static List<String> coverage(double latitude, double longitude) {
		int x = column(longitude);
		int y = row(latitude);
		double insideX = (longitude + 180) / TILE_SIZE_DEGREES - x;
		double insideY = (latitude + 90) / TILE_SIZE_DEGREES - y;
		int neighbourX = insideX < 0.5 ? x - 1 : x + 1;
		int neighbourY = insideY < 0.5 ? y - 1 : y + 1;
		Set<String> keys = new LinkedHashSet<>(4);
		for (int tileY : new int[] {Math.min(y, neighbourY), Math.max(y, neighbourY)}) {
			for (int tileX : new int[] {Math.min(x, neighbourX), Math.max(x, neighbourX)}) {
				if (tileY >= 0 && tileY < LAT_ROWS && tileX >= 0 && tileX < LON_COLUMNS) {
					keys.add(key(tileX, tileY));
				}
			}
		}
		return new ArrayList<>(keys);
	}

	/** minLatitude, maxLatitude, minLongitude, maxLongitude of a tile key such as "831_532". */
	static double[] bounds(String key) {
		String[] parts = key.split("_");
		if (parts.length != 2) {
			throw new IllegalArgumentException("Invalid tile key " + key);
		}
		int x = Integer.parseInt(parts[0]);
		int y = Integer.parseInt(parts[1]);
		if (x < 0 || x >= LON_COLUMNS || y < 0 || y >= LAT_ROWS) {
			throw new IllegalArgumentException("Tile key off the grid " + key);
		}
		return new double[] {
				y * TILE_SIZE_DEGREES - 90, (y + 1) * TILE_SIZE_DEGREES - 90,
				x * TILE_SIZE_DEGREES - 180, (x + 1) * TILE_SIZE_DEGREES - 180};
	}

	/** One segment of a tile, reduced to what the merge decides on; the JSON rides along. */
	static final class Segment {
		final String segmentId;
		final boolean eligible;
		/** The routing preference's validUntil, or +infinity when there is none. */
		final double validUntil;
		final String json;

		Segment(String segmentId, boolean eligible, double validUntil, String json) {
			this.segmentId = segmentId;
			this.eligible = eligible;
			this.validUntil = validUntil;
			this.json = json;
		}
	}

	static final class Tile {
		final String key;
		final long generatedAtMillis;
		final boolean complete;
		final List<Segment> segments;

		Tile(String key, long generatedAtMillis, boolean complete, List<Segment> segments) {
			this.key = key;
			this.generatedAtMillis = generatedAtMillis;
			this.complete = complete;
			this.segments = segments;
		}
	}

	static final class Merge {
		final long generatedAtMillis;
		final boolean complete;
		final List<Segment> segments;

		Merge(long generatedAtMillis, boolean complete, List<Segment> segments) {
			this.generatedAtMillis = generatedAtMillis;
			this.complete = complete;
			this.segments = segments;
		}
	}

	/**
	 * The requested tiles back into one answer, as mergeTiles on the server:
	 * complete only if every requested tile came back complete; a tile not
	 * requested is ignored; as old as the oldest tile used; a segment on a border
	 * is kept once, and two different versions resolve to the more cautious one
	 * whatever the tile order.
	 */
	static Merge merge(List<String> requested, List<Tile> parts) {
		Set<String> wanted = new LinkedHashSet<>(requested);
		List<Tile> used = new ArrayList<>();
		for (Tile part : parts) {
			if (part != null && wanted.contains(part.key)) {
				used.add(part);
			}
		}
		if (wanted.isEmpty() || used.isEmpty()) {
			return new Merge(0, false, new ArrayList<>());
		}
		boolean complete = true;
		for (String key : wanted) {
			boolean found = false;
			for (Tile part : used) {
				found |= part.key.equals(key);
			}
			complete &= found;
		}
		long generatedAt = Long.MAX_VALUE;
		Map<String, Segment> kept = new LinkedHashMap<>();
		for (Tile part : used) {
			complete &= part.complete;
			generatedAt = Math.min(generatedAt, part.generatedAtMillis);
			for (Segment segment : part.segments) {
				Segment other = kept.get(segment.segmentId);
				kept.put(segment.segmentId, other == null ? segment : preferred(other, segment));
			}
		}
		return new Merge(generatedAt, complete, new ArrayList<>(kept.values()));
	}

	/** Not eligible over eligible, then the earlier validity, last a fixed order of the JSON. */
	static Segment preferred(Segment a, Segment b) {
		if (a.eligible != b.eligible) {
			return a.eligible ? b : a;
		}
		if (a.validUntil != b.validUntil) {
			return a.validUntil < b.validUntil ? a : b;
		}
		return a.json.compareTo(b.json) <= 0 ? a : b;
	}
}
