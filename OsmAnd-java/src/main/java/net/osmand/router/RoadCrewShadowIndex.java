package net.osmand.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only community evidence downloaded by RoadCrew. This index deliberately
 * has no routing mutation API: it is safe for diagnostics and shadow analysis
 * only until a separately reviewed routing integration is introduced.
 */
public final class RoadCrewShadowIndex {

	public static final int SCHEMA_VERSION = 1;
	public static final String ROUTING_EFFECT_NONE = "NONE_SHADOW_ONLY";

	private final long generatedAtMillis;
	private final Bounds bounds;
	private final Map<String, Entry> entriesBySegmentId;

	private RoadCrewShadowIndex(long generatedAtMillis, Bounds bounds,
			Map<String, Entry> entriesBySegmentId) {
		this.generatedAtMillis = generatedAtMillis;
		this.bounds = bounds;
		this.entriesBySegmentId = Collections.unmodifiableMap(entriesBySegmentId);
	}

	public static RoadCrewShadowIndex create(int schemaVersion, long generatedAtMillis,
			String routingEffect, List<Entry> entries) {
		return create(schemaVersion, generatedAtMillis, routingEffect, Bounds.world(), entries);
	}

	public static RoadCrewShadowIndex create(int schemaVersion, long generatedAtMillis,
			String routingEffect, Bounds bounds, List<Entry> entries) {
		if (schemaVersion != SCHEMA_VERSION || generatedAtMillis <= 0
				|| !ROUTING_EFFECT_NONE.equals(routingEffect) || bounds == null || entries == null) {
			throw new IllegalArgumentException("Invalid RoadCrew shadow snapshot metadata");
		}
		Map<String, Entry> indexed = new LinkedHashMap<>();
		for (Entry entry : entries) {
			if (entry == null || indexed.putIfAbsent(entry.segmentId, entry) != null) {
				throw new IllegalArgumentException("Invalid or duplicate RoadCrew shadow segment");
			}
		}
		return new RoadCrewShadowIndex(generatedAtMillis, bounds, indexed);
	}

	public long getGeneratedAtMillis() {
		return generatedAtMillis;
	}

	public int size() {
		return entriesBySegmentId.size();
	}

	public Bounds getBounds() {
		return bounds;
	}

	public List<Entry> getEntries() {
		return Collections.unmodifiableList(new ArrayList<>(entriesBySegmentId.values()));
	}

	public Entry findExact(RoadCrewSegmentIdentity.SegmentKey key) {
		if (key == null) {
			return null;
		}
		return entriesBySegmentId.get(segmentId(key));
	}

	public int count(Level level) {
		int count = 0;
		for (Entry entry : entriesBySegmentId.values()) {
			if (entry.level == level) {
				count++;
			}
		}
		return count;
	}

	public boolean covers(RoadCrewSegmentIdentity.SegmentKey key) {
		return key != null && bounds.intersects(key);
	}

	public static String segmentId(RoadCrewSegmentIdentity.SegmentKey key) {
		if (key == null) {
			throw new IllegalArgumentException("RoadCrew segment key is required");
		}
		return key.getCanonicalId() + ":" + key.getGeometryFingerprint().toLowerCase(Locale.US);
	}

	public enum Level {
		COLLECTING,
		CANDIDATE,
		MATURE_SHADOW;

		public static Level parse(String value) {
			try {
				return Level.valueOf(value == null ? "" : value);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Unknown RoadCrew shadow level", e);
			}
		}
	}

	public static final class Bounds {
		private final double minLatitude;
		private final double maxLatitude;
		private final double minLongitude;
		private final double maxLongitude;

		public Bounds(double minLatitude, double maxLatitude,
				double minLongitude, double maxLongitude) {
			if (!Double.isFinite(minLatitude) || !Double.isFinite(maxLatitude)
					|| !Double.isFinite(minLongitude) || !Double.isFinite(maxLongitude)
					|| minLatitude < -90 || maxLatitude > 90
					|| minLongitude < -180 || maxLongitude > 180
					|| minLatitude >= maxLatitude || minLongitude >= maxLongitude) {
				throw new IllegalArgumentException("Invalid RoadCrew shadow bounds");
			}
			this.minLatitude = minLatitude;
			this.maxLatitude = maxLatitude;
			this.minLongitude = minLongitude;
			this.maxLongitude = maxLongitude;
		}

		private static Bounds world() {
			return new Bounds(-90, 90, -180, 180);
		}

		public double getMinLatitude() {
			return minLatitude;
		}

		public double getMaxLatitude() {
			return maxLatitude;
		}

		public double getMinLongitude() {
			return minLongitude;
		}

		public double getMaxLongitude() {
			return maxLongitude;
		}

		private boolean intersects(RoadCrewSegmentIdentity.SegmentKey key) {
			double segmentMinLatitude = Math.min(key.getFromLatitude(), key.getToLatitude());
			double segmentMaxLatitude = Math.max(key.getFromLatitude(), key.getToLatitude());
			double segmentMinLongitude = Math.min(key.getFromLongitude(), key.getToLongitude());
			double segmentMaxLongitude = Math.max(key.getFromLongitude(), key.getToLongitude());
			return segmentMaxLatitude >= minLatitude && segmentMinLatitude <= maxLatitude
					&& segmentMaxLongitude >= minLongitude && segmentMinLongitude <= maxLongitude;
		}
	}

	public static final class Entry {
		private final String segmentId;
		private final String canonicalId;
		private final String geometryFingerprint;
		private final Level level;
		private final double confidence;
		private final int passageCount;
		private final int distinctObserverCount;
		private final int activeDayCount;

		public Entry(String segmentId, String canonicalId, String geometryFingerprint,
				Level level, double confidence, int passageCount,
				int distinctObserverCount, int activeDayCount) {
			String normalizedFingerprint = geometryFingerprint == null
					? "" : geometryFingerprint.toLowerCase(Locale.US);
			if (segmentId == null || segmentId.isEmpty() || canonicalId == null || canonicalId.isEmpty()
					|| !normalizedFingerprint.matches("[0-9a-f]{32}")
					|| !segmentId.equals(canonicalId + ":" + normalizedFingerprint)
					|| level == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
					|| passageCount < 0 || distinctObserverCount < 0 || activeDayCount < 0) {
				throw new IllegalArgumentException("Invalid RoadCrew shadow entry");
			}
			this.segmentId = segmentId;
			this.canonicalId = canonicalId;
			this.geometryFingerprint = normalizedFingerprint;
			this.level = level;
			this.confidence = confidence;
			this.passageCount = passageCount;
			this.distinctObserverCount = distinctObserverCount;
			this.activeDayCount = activeDayCount;
		}

		public String getSegmentId() {
			return segmentId;
		}

		public String getCanonicalId() {
			return canonicalId;
		}

		public String getGeometryFingerprint() {
			return geometryFingerprint;
		}

		public Level getLevel() {
			return level;
		}

		public double getConfidence() {
			return confidence;
		}

		public int getPassageCount() {
			return passageCount;
		}

		public int getDistinctObserverCount() {
			return distinctObserverCount;
		}

		public int getActiveDayCount() {
			return activeDayCount;
		}
	}
}
