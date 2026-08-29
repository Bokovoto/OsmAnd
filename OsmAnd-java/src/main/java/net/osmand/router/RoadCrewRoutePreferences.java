package net.osmand.router;

import com.google.gson.Gson;
import net.osmand.binary.ObfConstants;
import net.osmand.binary.RouteDataObject;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** A bounded, directed ranking hint. It cannot grant access or change physical speed. */
public final class RoadCrewRoutePreferences {

	public static final String POLICY = "MATURE_VALIDATED_SOFT_V1";
	public static final long MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000;
	public static final double ORDINARY_COST_FACTOR = 1.05;
	private static final int MAX_PREFERENCES = 20_000;
	public static final RoadCrewRoutePreferences EMPTY = new RoadCrewRoutePreferences(Collections.emptyList());
	private final List<RoadCrewSegmentIdentity.SegmentKey> keys;
	private final Map<Long, List<RoadCrewSegmentIdentity.SegmentKey>> byWay = new HashMap<>();

	private RoadCrewRoutePreferences(List<RoadCrewSegmentIdentity.SegmentKey> keys) {
		this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
		for (RoadCrewSegmentIdentity.SegmentKey key : keys) {
			byWay.computeIfAbsent(key.getOsmWayId(), ignored -> new ArrayList<>()).add(key);
		}
	}

	public static RoadCrewRoutePreferences parse(Reader reader, long now) {
		Document doc = new Gson().fromJson(reader, Document.class);
		if (doc == null || !doc.ok || doc.schemaVersion != 1 || !Boolean.FALSE.equals(doc.truncated)
				|| !POLICY.equals(doc.routingPreferencePolicy) || doc.generatedAt <= 0
				|| doc.generatedAt > now || now - doc.generatedAt > MAX_AGE_MILLIS
				|| doc.routingPreferenceValidUntil < now || doc.segments == null || doc.segments.size() > MAX_PREFERENCES) {
			return EMPTY;
		}
		List<RoadCrewSegmentIdentity.SegmentKey> keys = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Segment s : doc.segments) {
			if (s == null || s.routingPreference == null || !s.routingPreference.eligible) {
				continue;
			}
			Preference p = s.routingPreference;
			if (!"MATURE_SHADOW".equals(s.shadowLevel) || !Double.isFinite(s.confidence)
					|| s.confidence < 0.75 || s.confidence > 1 || s.distinctObserverCount < 5
					|| s.passageCount < 8 || s.activeDayCount < 3 || p.suitableObserverCount < 3
					|| p.problemObserverCount != 0 || p.validUntil < now
					|| p.validUntil > doc.generatedAt + MAX_AGE_MILLIS
					|| s.lastObservedBucket <= 0 || s.lastObservedBucket > doc.generatedAt
					|| now - s.lastObservedBucket > MAX_AGE_MILLIS) {
				continue;
			}
			try {
				RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.key(1, s.osmWayId, s.region,
						s.fromLatitude, s.fromLongitude, s.toLatitude, s.toLongitude, s.geometryFingerprint, s.lengthMeters);
				if (key.getCanonicalId().equals(s.canonicalId) && RoadCrewShadowIndex.segmentId(key).equals(s.segmentId)
						&& seen.add(s.segmentId)) {
					keys.add(key);
				}
			} catch (IllegalArgumentException ignored) {
				// Invalid or changed identity never becomes a road-wide preference.
			}
		}
		return keys.isEmpty() ? EMPTY : new RoadCrewRoutePreferences(keys);
	}

	public boolean isEmpty() { return keys.isEmpty(); }
	public int size() { return keys.size(); }

	public RoadCrewRoutePreferences within(double minLat, double maxLat, double minLon, double maxLon) {
		List<RoadCrewSegmentIdentity.SegmentKey> selected = new ArrayList<>();
		for (RoadCrewSegmentIdentity.SegmentKey k : keys) {
			if (Math.max(k.getFromLatitude(), k.getToLatitude()) >= minLat
					&& Math.min(k.getFromLatitude(), k.getToLatitude()) <= maxLat
					&& Math.max(k.getFromLongitude(), k.getToLongitude()) >= minLon
					&& Math.min(k.getFromLongitude(), k.getToLongitude()) <= maxLon) {
				selected.add(k);
			}
		}
		return selected.isEmpty() ? EMPTY : new RoadCrewRoutePreferences(selected);
	}

	public Matcher newMatcher() { return new Matcher(); }

	/** Per-search cache; no routing objects or mutable state shared between calculations. */
	public final class Matcher {
		private final Map<RouteDataObject, Ranges> cache = new WeakHashMap<>();

		public double costFactor(RouteDataObject road, int from, int to) {
			if (isEmpty()) { return 1; }
			List<RoadCrewSegmentIdentity.SegmentKey> candidates = byWay.get(ObfConstants.getOsmObjectId(road));
			if (candidates == null) { return ORDINARY_COST_FACTOR; }
			Ranges ranges = cache.get(road);
			if (ranges == null || ranges.x != road.pointsX || ranges.y != road.pointsY) {
				ranges = new Ranges(road);
				for (RoadCrewSegmentIdentity.SegmentKey key : candidates) {
					RoadCrewSegmentIdentity.Resolution r = RoadCrewSegmentIdentity.resolve(key, Collections.singletonList(road));
					if (r.getStatus() == RoadCrewSegmentIdentity.Status.EXACT) {
						ranges.exact.add(r);
					}
				}
				cache.put(road, ranges);
			}
			for (RoadCrewSegmentIdentity.Resolution r : ranges.exact) {
				int start = r.getStartPointIndex(), end = r.getEndPointIndex();
				if ((start < end && from >= start && to <= end && from < to)
						|| (start > end && from <= start && to >= end && from > to)) {
					return 1;
				}
			}
			// All costs stay >= the original A* lower bound. Unobserved roads stay usable.
			return ORDINARY_COST_FACTOR;
		}
	}

	private static final class Ranges {
		final int[] x, y;
		final List<RoadCrewSegmentIdentity.Resolution> exact = new ArrayList<>();
		Ranges(RouteDataObject road) { x = road.pointsX; y = road.pointsY; }
	}
	private static final class Document {
		boolean ok;
		int schemaVersion;
		Boolean truncated;
		String routingPreferencePolicy;
		long generatedAt, routingPreferenceValidUntil;
		List<Segment> segments;
	}
	private static final class Segment {
		String segmentId, canonicalId, geometryFingerprint, region, shadowLevel;
		long osmWayId, lastObservedBucket;
		double fromLatitude, fromLongitude, toLatitude, toLongitude, lengthMeters, confidence;
		int passageCount, distinctObserverCount, activeDayCount;
		Preference routingPreference;
	}
	private static final class Preference {
		boolean eligible;
		int suitableObserverCount, problemObserverCount;
		long validUntil;
	}
}
