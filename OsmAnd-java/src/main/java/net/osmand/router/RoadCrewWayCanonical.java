package net.osmand.router;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonical orientation of an OSM way, as ROADMAP section 162 defines it.
 *
 * The identity of a recorded stretch of road is the way and the direction of
 * travel. "Direction" has to mean something stable, and the order of the points
 * in the map file is not: a later map build may emit the same way reversed, or
 * start a closed ring at a different vertex. Either would silently swap F and R
 * and send half the collected evidence to the wrong side of the road.
 *
 * So the orientation is decided by the geometry itself - the lexicographically
 * smaller of the forward and the reverse reading - rather than by the order the
 * file happens to use. The same physical road then yields the same name from
 * any map build that describes it with the same points.
 *
 * This is a port of the reference implementation in the RoadCrew Worker
 * (src/osm-way-canonical.ts). Both must reproduce
 * test/resources/canonicalization-v1-vectors.json exactly for fingerprints and
 * within {@link #MEASURE_TOLERANCE_METERS} for measures.
 */
public final class RoadCrewWayCanonical {

	public static final int FINGERPRINT_ALGORITHM = 1;

	/**
	 * Measure algorithm 1, frozen as a RoadCrew contract rather than a call into
	 * MapUtils. The formulas are today's haversine and 31-bit conversions,
	 * copied deliberately: both the phone and the server must derive the same
	 * metre from the same integers for years, and that must not shift because a
	 * future OsmAnd release refines its geodesy. A different formula later
	 * becomes algorithm 2, leaving old data readable.
	 */
	public static final int MEASURE_ALGORITHM = 1;

	/**
	 * How far two independent implementations may differ. Java and V8 disagree
	 * in the last bits of sin, cos and asin; a fingerprint, by contrast, must
	 * match exactly.
	 */
	public static final double MEASURE_TOLERANCE_METERS = 0.01;

	private static final double SCALE_31 = 2147483648.0;
	private static final double HAVERSINE_EARTH_RADIUS_METERS = 6372800.0;

	private RoadCrewWayCanonical() {
	}

	/** The canonical reading of a way, and where it sits relative to the raw one. */
	public static final class CanonicalWay {
		public final boolean closed;
		public final boolean reversed;
		/** Index into the raw cycle at which the canonical reading begins. */
		public final int startIndex;
		/** Points in canonical order; a closed way has its duplicated end dropped. */
		public final int[] pointsX;
		public final int[] pointsY;
		/** Cumulative distance along the canonical order, closing leg included when closed. */
		public final double[] measures;
		public final double lengthMeters;
		/** Distance from the raw first point to the canonical first point. */
		public final double rawStartMeasure;

		CanonicalWay(boolean closed, boolean reversed, int startIndex, int[] pointsX, int[] pointsY,
				double[] measures, double lengthMeters, double rawStartMeasure) {
			this.closed = closed;
			this.reversed = reversed;
			this.startIndex = startIndex;
			this.pointsX = pointsX;
			this.pointsY = pointsY;
			this.measures = measures;
			this.lengthMeters = lengthMeters;
			this.rawStartMeasure = rawStartMeasure;
		}

		public int getPointCount() {
			return pointsX.length;
		}
	}

	public static double longitudeFrom31(int x) {
		return (x / SCALE_31) * 360 - 180;
	}

	public static double latitudeFrom31(int y) {
		int sign = y < 0 ? -1 : 1;
		return Math.atan(sign * Math.sinh(Math.PI * (1 - (2 * y) / SCALE_31))) * 180 / Math.PI;
	}

	public static double distanceMeters(int fromX, int fromY, int toX, int toY) {
		double latitudeA = latitudeFrom31(fromY);
		double latitudeB = latitudeFrom31(toY);
		double deltaLatitude = Math.toRadians(latitudeB - latitudeA);
		double deltaLongitude = Math.toRadians(longitudeFrom31(toX) - longitudeFrom31(fromX));
		double sinHalfLatitude = Math.sin(deltaLatitude / 2);
		double sinHalfLongitude = Math.sin(deltaLongitude / 2);
		double value = sinHalfLatitude * sinHalfLatitude
				+ Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
				* sinHalfLongitude * sinHalfLongitude;
		return 2 * HAVERSINE_EARTH_RADIUS_METERS * Math.asin(Math.sqrt(value));
	}

	private static int comparePoint(int[] xs, int[] ys, int a, int b) {
		if (xs[a] != xs[b]) {
			return xs[a] < xs[b] ? -1 : 1;
		}
		if (ys[a] != ys[b]) {
			return ys[a] < ys[b] ? -1 : 1;
		}
		return 0;
	}

	/**
	 * Booth's algorithm: where the lexicographically smallest rotation of a
	 * cycle begins, in linear time. Comparing every rotation naively is
	 * quadratic, which matters on a ring of several hundred points.
	 */
	static int leastRotation(int[] xs, int[] ys) {
		int n = xs.length;
		if (n <= 1) {
			return 0;
		}
		int[] failure = new int[2 * n];
		for (int index = 0; index < failure.length; index++) {
			failure[index] = -1;
		}
		int k = 0;
		for (int j = 1; j < 2 * n; j++) {
			int current = j % n;
			int i = failure[j - k - 1];
			while (i != -1 && comparePoint(xs, ys, current, (k + i + 1) % n) != 0) {
				if (comparePoint(xs, ys, current, (k + i + 1) % n) < 0) {
					k = j - i - 1;
				}
				i = failure[i];
			}
			if (comparePoint(xs, ys, current, (k + i + 1) % n) != 0) {
				if (comparePoint(xs, ys, current, k % n) < 0) {
					k = j;
				}
				failure[j - k] = -1;
			} else {
				failure[j - k] = i + 1;
			}
		}
		return k;
	}

	private static int[] rotate(int[] values, int start) {
		int n = values.length;
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[i] = values[(start + i) % n];
		}
		return out;
	}

	private static int[] reverse(int[] values) {
		int n = values.length;
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[i] = values[n - 1 - i];
		}
		return out;
	}

	private static int compareSequence(int[] aX, int[] aY, int[] bX, int[] bY) {
		int shared = Math.min(aX.length, bX.length);
		for (int i = 0; i < shared; i++) {
			if (aX[i] != bX[i]) {
				return aX[i] < bX[i] ? -1 : 1;
			}
			if (aY[i] != bY[i]) {
				return aY[i] < bY[i] ? -1 : 1;
			}
		}
		return aX.length - bX.length;
	}

	private static double[] cumulativeMeasures(int[] xs, int[] ys, boolean closed) {
		int n = xs.length;
		double[] measures = new double[closed && n > 1 ? n + 1 : n];
		measures[0] = 0;
		for (int i = 1; i < n; i++) {
			measures[i] = measures[i - 1] + distanceMeters(xs[i - 1], ys[i - 1], xs[i], ys[i]);
		}
		if (closed && n > 1) {
			measures[n] = measures[n - 1] + distanceMeters(xs[n - 1], ys[n - 1], xs[0], ys[0]);
		}
		return measures;
	}

	/**
	 * An open way is decided by a single comparison: lexicographic order looks
	 * at the first pair of points, and the first and last point of a road differ
	 * in every case that is not a closed ring.
	 */
	public static CanonicalWay canonicalise(int[] rawX, int[] rawY) {
		if (rawX == null || rawY == null || rawX.length != rawY.length || rawX.length < 2) {
			throw new IllegalArgumentException("A way needs at least two points.");
		}
		int last = rawX.length - 1;
		boolean closed = rawX.length > 2 && rawX[0] == rawX[last] && rawY[0] == rawY[last];

		if (!closed) {
			boolean reversed = rawX[0] != rawX[last]
					? rawX[0] > rawX[last]
					: rawY[0] > rawY[last];
			int[] xs = reversed ? reverse(rawX) : rawX.clone();
			int[] ys = reversed ? reverse(rawY) : rawY.clone();
			double[] measures = cumulativeMeasures(xs, ys, false);
			double length = measures[measures.length - 1];
			return new CanonicalWay(false, reversed, 0, xs, ys, measures, length,
					reversed ? length : 0);
		}

		// A ring: the duplicated closing point carries no information, and the
		// starting vertex is an accident of how the file was written.
		int n = rawX.length - 1;
		int[] cycleX = new int[n];
		int[] cycleY = new int[n];
		System.arraycopy(rawX, 0, cycleX, 0, n);
		System.arraycopy(rawY, 0, cycleY, 0, n);

		int forwardStart = leastRotation(cycleX, cycleY);
		int[] forwardX = rotate(cycleX, forwardStart);
		int[] forwardY = rotate(cycleY, forwardStart);
		int[] reverseCycleX = reverse(cycleX);
		int[] reverseCycleY = reverse(cycleY);
		int reverseStart = leastRotation(reverseCycleX, reverseCycleY);
		int[] reverseX = rotate(reverseCycleX, reverseStart);
		int[] reverseY = rotate(reverseCycleY, reverseStart);

		boolean reversed = compareSequence(reverseX, reverseY, forwardX, forwardY) < 0;
		int[] xs = reversed ? reverseX : forwardX;
		int[] ys = reversed ? reverseY : forwardY;

		double[] rawMeasures = cumulativeMeasures(cycleX, cycleY, true);
		int rawStartIndex = reversed ? n - 1 - reverseStart : forwardStart;
		double[] measures = cumulativeMeasures(xs, ys, true);
		return new CanonicalWay(true, reversed, rawStartIndex, xs, ys, measures,
				measures[measures.length - 1], rawMeasures[rawStartIndex]);
	}

	/**
	 * Converts a distance measured along the map file's own reading into the
	 * same distance along the canonical reading. Reversing an open way simply
	 * mirrors it; a ring can be mirrored and rotated at once, so the origin
	 * moves too.
	 */
	public static double canonicalMeasure(double rawMeasure, CanonicalWay way) {
		double length = way.lengthMeters;
		if (!way.closed) {
			return way.reversed ? length - rawMeasure : rawMeasure;
		}
		double shifted = way.reversed
				? way.rawStartMeasure - rawMeasure
				: rawMeasure - way.rawStartMeasure;
		return ((shifted % length) + length) % length;
	}

	/**
	 * OsmAnd reads 1 as "along the drawing direction", -1 as "against it", 0 as
	 * two-way. Only mirroring changes that; rotating a ring's start does not.
	 * Zero is guarded explicitly so a two-way road never becomes negative zero.
	 */
	public static int canonicalOneway(int rawOneway, boolean reversed) {
		if (rawOneway == 0) {
			return 0;
		}
		return reversed ? -rawOneway : rawOneway;
	}

	/**
	 * Fingerprint algorithm 1, pinned so it stays reproducible: points, each as
	 * two big-endian 32-bit integers, SHA-256, first sixteen bytes in hex.
	 */
	public static String fingerprintPoints(int[] xs, int[] ys) {
		ByteBuffer buffer = ByteBuffer.allocate(xs.length * 8);
		for (int index = 0; index < xs.length; index++) {
			buffer.putInt(xs[index]);
			buffer.putInt(ys[index]);
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(buffer.array());
			StringBuilder hex = new StringBuilder(32);
			for (int index = 0; index < 16; index++) {
				hex.append(Character.forDigit((digest[index] >> 4) & 0xf, 16));
				hex.append(Character.forDigit(digest[index] & 0xf, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required for RoadCrew fingerprints", e);
		}
	}

	/** The identity fingerprint: over the canonical reading of the way. */
	public static String canonicalFingerprint(CanonicalWay way) {
		return fingerprintPoints(way.pointsX, way.pointsY);
	}

	/**
	 * A diagnostic fingerprint over the points exactly as the map file gave
	 * them. Comparing the two separates failures that otherwise look identical:
	 * the same source with a different canonical fingerprint means the two
	 * implementations disagree, which is a bug in one of them; a different
	 * source with the same canonical means the map file wrote the way
	 * differently and canonicalisation absorbed it.
	 */
	public static String sourceFingerprint(int[] rawX, int[] rawY) {
		return fingerprintPoints(rawX, rawY);
	}
}
