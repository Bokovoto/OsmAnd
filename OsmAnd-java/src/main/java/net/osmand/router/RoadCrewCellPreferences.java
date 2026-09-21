package net.osmand.router;

import com.google.gson.Gson;

import net.osmand.binary.ObfConstants;
import net.osmand.binary.RouteDataObject;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The same bounded ranking hint as {@link RoadCrewRoutePreferences}, read from
 * the rcs2 cell tiles instead of the old segment list.
 *
 * What arrives is a road - a way and a canonical direction - with the stretches
 * of it that are proven, in metres. No coordinates: this phone already holds
 * the geometry, so it measures the road itself and a map edition that moved it
 * by three metres needs no reconciling with the server.
 *
 * The rule is unchanged and stays unchanged: a proven stretch costs 1, anything
 * else costs {@link RoadCrewRoutePreferences#ORDINARY_COST_FACTOR}. Nothing
 * here can forbid a road, grant access, or change a speed - it can only make
 * the router prefer tarmac other lorries have actually driven.
 *
 * Every stretch carries the moment it stops being proven. The phone drops it
 * then, by itself, offline: without that a preference the server has stopped
 * sending would live here for ever (ROADMAP 159).
 */
public final class RoadCrewCellPreferences {

	public static final String EVIDENCE_MODEL = "RCS2_CELLS";
	public static final String ROUTING_EFFECT = "PREFERENCE_ONLY";
	/** A tile older than this is not used at all, however fresh its contents claim to be. */
	public static final long MAX_TILE_AGE_MILLIS = RoadCrewRoutePreferences.MAX_AGE_MILLIS;
	private static final int MAX_ROADS = 20_000;
	private static final int MAX_RUNS_PER_ROAD = 512;
	/** GPS and geometry both wobble; a metre of slack costs nothing and avoids a false miss. */
	private static final double MEASURE_TOLERANCE_METERS = 1.0;

	public static final RoadCrewCellPreferences EMPTY =
			new RoadCrewCellPreferences(Collections.<Long, List<Run>>emptyMap());

	private final Map<Long, List<Run>> byWay;

	private RoadCrewCellPreferences(Map<Long, List<Run>> byWay) {
		this.byWay = Collections.unmodifiableMap(byWay);
	}

	/** A proven stretch of one road in one direction. */
	public static final class Run {
		private final boolean forward;
		private final double fromMeters;
		private final double toMeters;
		private final long expiresAt;

		private Run(boolean forward, double fromMeters, double toMeters, long expiresAt) {
			this.forward = forward;
			this.fromMeters = fromMeters;
			this.toMeters = toMeters;
			this.expiresAt = expiresAt;
		}

		public boolean isForward() { return forward; }
		public double getFromMeters() { return fromMeters; }
		public double getToMeters() { return toMeters; }
		public long getExpiresAt() { return expiresAt; }
	}

	public boolean isEmpty() { return byWay.isEmpty(); }

	public int size() {
		int total = 0;
		for (List<Run> runs : byWay.values()) { total += runs.size(); }
		return total;
	}

	public static RoadCrewCellPreferences parse(Reader reader, long now) {
		Document doc;
		try {
			doc = new Gson().fromJson(reader, Document.class);
		} catch (RuntimeException ignored) {
			return EMPTY;
		}
		if (doc == null || !doc.ok || doc.schemaVersion != 2
				|| !EVIDENCE_MODEL.equals(doc.evidenceModel)
				|| !ROUTING_EFFECT.equals(doc.routingEffect)
				|| !RoadCrewRoutePreferences.POLICY.equals(doc.routingPreferencePolicy)
				|| doc.generatedAt <= 0 || doc.generatedAt > now
				|| now - doc.generatedAt > MAX_TILE_AGE_MILLIS
				|| doc.roads == null || doc.roads.size() > MAX_ROADS) {
			return EMPTY;
		}
		Map<Long, List<Run>> byWay = new HashMap<>();
		for (Road road : doc.roads) {
			if (road == null || road.runs == null || road.runs.isEmpty()
					|| road.runs.size() > MAX_RUNS_PER_ROAD) {
				continue;
			}
			boolean forward = "F".equals(road.direction);
			if (!forward && !"R".equals(road.direction)) {
				continue;
			}
			long wayId;
			try {
				wayId = Long.parseLong(road.osmWayId);
			} catch (NumberFormatException ignored) {
				continue;
			}
			if (wayId <= 0) {
				continue;
			}
			List<Run> runs = new ArrayList<>();
			for (RunDto run : road.runs) {
				if (run == null || !(run.toMeters > run.fromMeters) || run.fromMeters < 0
						|| !isFinite(run.fromMeters) || !isFinite(run.toMeters)) {
					continue;
				}
				// Expired on arrival, or promising to outlive the window: neither
				// is used. The first is simply late, the second is not ours to
				// believe - the window is thirty days from evidence, not from a
				// number in a document.
				if (run.expiresAt <= now
						|| run.expiresAt > doc.generatedAt + MAX_TILE_AGE_MILLIS) {
					continue;
				}
				runs.add(new Run(forward, run.fromMeters, run.toMeters, run.expiresAt));
			}
			if (!runs.isEmpty()) {
				List<Run> existing = byWay.get(wayId);
				if (existing == null) {
					byWay.put(wayId, runs);
				} else {
					existing.addAll(runs);
				}
			}
		}
		return byWay.isEmpty() ? EMPTY : new RoadCrewCellPreferences(byWay);
	}

	/** Drops what has expired since the tile arrived; the phone needs no permission for that. */
	public RoadCrewCellPreferences fresh(long now) {
		Map<Long, List<Run>> kept = new HashMap<>();
		for (Map.Entry<Long, List<Run>> entry : byWay.entrySet()) {
			List<Run> runs = new ArrayList<>();
			for (Run run : entry.getValue()) {
				if (run.expiresAt > now) { runs.add(run); }
			}
			if (!runs.isEmpty()) { kept.put(entry.getKey(), runs); }
		}
		return kept.isEmpty() ? EMPTY : new RoadCrewCellPreferences(kept);
	}

	public Matcher newMatcher(long now) { return new Matcher(now); }

	/** Per-search cache; no routing objects or mutable state shared between calculations. */
	public final class Matcher {
		private final long now;
		private final Map<RouteDataObject, Measures> cache = new WeakHashMap<>();

		private Matcher(long now) { this.now = now; }

		/**
		 * Whether this side has anything to say at all.
		 *
		 * A matcher with no evidence must not answer 1 and be taken for "this
		 * road is proven": combined with the other side by a minimum, that
		 * silently made every road proven and the ranking vanished. Caught by
		 * the A* test on 21.09.
		 */
		public boolean hasEvidence() { return !byWay.isEmpty(); }

		/**
		 * 1 for a stretch other lorries have driven, the ordinary cost for
		 * everything else. Never below 1: the A* lower bound has to hold.
		 */
		public double costFactor(RouteDataObject road, int from, int to) {
			if (byWay.isEmpty() || road == null) { return 1; }
			List<Run> runs = byWay.get(ObfConstants.getOsmObjectId(road));
			if (runs == null || runs.isEmpty()) {
				return RoadCrewRoutePreferences.ORDINARY_COST_FACTOR;
			}
			Measures measures = measuresFor(road);
			if (measures == null || from < 0 || to < 0
					|| from >= measures.canonical.length || to >= measures.canonical.length
					|| from == to) {
				return RoadCrewRoutePreferences.ORDINARY_COST_FACTOR;
			}
			boolean rawForward = to > from;
			boolean forward = measures.reversed ? !rawForward : rawForward;
			double one = measures.canonical[from];
			double other = measures.canonical[to];
			double low = Math.min(one, other);
			double high = Math.max(one, other);
			for (Run run : runs) {
				if (run.forward != forward || run.expiresAt <= now) { continue; }
				if (low >= run.fromMeters - MEASURE_TOLERANCE_METERS
						&& high <= run.toMeters + MEASURE_TOLERANCE_METERS) {
					return 1;
				}
			}
			return RoadCrewRoutePreferences.ORDINARY_COST_FACTOR;
		}

		private Measures measuresFor(RouteDataObject road) {
			Measures measures = cache.get(road);
			if (measures != null) { return measures; }
			try {
				int points = road.getPointsLength();
				if (points < 2) { return null; }
				int[] xs = new int[points];
				int[] ys = new int[points];
				for (int index = 0; index < points; index++) {
					xs[index] = road.getPoint31XTile(index);
					ys[index] = road.getPoint31YTile(index);
				}
				RoadCrewWayCanonical.CanonicalWay canonical = RoadCrewWayCanonical.canonicalise(xs, ys);
				double[] raw = new double[points];
				for (int index = 1; index < points; index++) {
					raw[index] = raw[index - 1] + RoadCrewWayCanonical.distanceMeters(
							xs[index - 1], ys[index - 1], xs[index], ys[index]);
				}
				double[] canonicalMeasures = new double[points];
				for (int index = 0; index < points; index++) {
					// The same conversion the recording side uses, so the two
					// cannot disagree about where on a road something happened.
					canonicalMeasures[index] =
							RoadCrewWayCanonical.canonicalMeasure(raw[index], canonical);
				}
				measures = new Measures(canonicalMeasures, canonical.reversed);
				cache.put(road, measures);
				return measures;
			} catch (RuntimeException ignored) {
				// A way this code cannot measure simply gets the ordinary cost.
				return null;
			}
		}
	}

	private static final class Measures {
		private final double[] canonical;
		private final boolean reversed;

		private Measures(double[] canonical, boolean reversed) {
			this.canonical = canonical;
			this.reversed = reversed;
		}
	}

	private static boolean isFinite(double value) {
		return !Double.isNaN(value) && !Double.isInfinite(value);
	}

	private static final class Document {
		boolean ok;
		int schemaVersion;
		String evidenceModel;
		String routingEffect;
		String routingPreferencePolicy;
		long generatedAt;
		List<Road> roads;
	}

	private static final class Road {
		String osmWayId;
		String direction;
		List<RunDto> runs;
	}

	private static final class RunDto {
		double fromMeters;
		double toMeters;
		long expiresAt;
	}
}
