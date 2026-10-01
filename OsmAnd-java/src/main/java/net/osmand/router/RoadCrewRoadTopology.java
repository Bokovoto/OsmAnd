package net.osmand.router;

import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the map proves about the stretch between two points of one drive -
 * ROADMAP 330, Galin, 26.09: "if between the point where GPS disappears and the
 * point where it reappears there is no branch, count it as driven; if there is,
 * leave it - other trucks will confirm that segment."
 *
 * A branch is a node where another road a vehicle could take meets the path,
 * however minor and whichever way it is signed: one-way or not, a truck
 * standing in the gap proves nothing about which way it went. A node where one
 * way simply goes on as the next - a tunnel or a bridge drawn as its own way -
 * is not a branch. Two ways cross only where they share a node, exactly as the
 * router connects them, so a bridge over the tunnel is no branch either.
 *
 * Everything is in the map file's own terms: raw point order, raw measures.
 * Anything that cannot be checked - a load that failed, a way that loops, a
 * path that never arrives - proves nothing.
 */
public final class RoadCrewRoadTopology {

	/** Loads every road around a point; null when it could not load them all. */
	public interface RoadSource {
		List<RouteDataObject> around(double latitude, double longitude, double radiusMeters)
				throws IOException;
	}

	/** Roads no vehicle can turn into: the only ones that are never a branch. */
	static final Set<String> NOT_FOR_VEHICLES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"footway", "path", "cycleway", "pedestrian", "steps", "bridleway", "corridor", "platform")));
	/** How far one extra load reaches around a node the path needs. */
	static final double LOAD_RADIUS_METERS = 600;
	/** Enough for some thirty kilometres of path. */
	static final int MAX_LOADS_PER_QUESTION = 25;
	static final int MAX_WAYS = 200;
	static final double MAX_PATH_METERS = 60_000;

	/** One way crossed from end to end; forward is along its raw point order. */
	public static final class Step {
		public final RouteDataObject road;
		public final boolean forward;

		Step(RouteDataObject road, boolean forward) {
			this.road = road;
			this.forward = forward;
		}
	}

	private static final class Touch {
		final RouteDataObject road;
		final int index;

		Touch(RouteDataObject road, int index) {
			this.road = road;
			this.index = index;
		}
	}

	/** Roads indexed by the nodes they pass through, and the areas they cover. */
	private static final class Layer {
		final List<RoadCrewObfSegmentLoader.Bounds31> areas = new ArrayList<>();
		final Map<Long, List<Touch>> nodes = new HashMap<>();
		final Set<Long> ids = new HashSet<>();

		void add(Iterable<RouteDataObject> roads, Set<Long> skip) {
			for (RouteDataObject road : roads) {
				if (road == null || road.pointsX == null || road.pointsY == null
						|| road.getPointsLength() < 2 || skip.contains(road.getId())
						|| !ids.add(road.getId())) {
					continue;
				}
				for (int index = 0; index < road.getPointsLength(); index++) {
					nodes.computeIfAbsent(key(road.getPoint31XTile(index), road.getPoint31YTile(index)),
							ignored -> new ArrayList<>(2)).add(new Touch(road, index));
				}
			}
		}

		boolean covers(int x, int y) {
			for (RoadCrewObfSegmentLoader.Bounds31 area : areas) {
				if (area.contains(x, y)) {
					return true;
				}
			}
			return false;
		}
	}

	private final RoadSource source;
	/** The roads the matcher holds; indexed only when first asked. */
	private List<RouteDataObject> baseRoads = Collections.emptyList();
	private RoadCrewObfSegmentLoader.Bounds31 baseArea;
	private Layer base;
	/** Loaded for one question and dropped after it. */
	private Layer extra;
	private int loads;

	public RoadCrewRoadTopology(RoadSource source) {
		this.source = source;
	}

	/**
	 * The roads now in memory and the area they were loaded for, as the loader
	 * was asked: every road with a node inside that area is among them. A NaN
	 * centre means the area is unknown and every node must be loaded again.
	 */
	public synchronized void replace(Iterable<RouteDataObject> roads, double latitude,
			double longitude, double radiusMeters) {
		List<RouteDataObject> copy = new ArrayList<>();
		if (roads != null) {
			for (RouteDataObject road : roads) {
				copy.add(road);
			}
		}
		baseRoads = copy;
		baseArea = Double.isFinite(latitude) && Double.isFinite(longitude)
				&& Double.isFinite(radiusMeters) && radiusMeters > 0
				? RoadCrewObfSegmentLoader.Bounds31.around(latitude, longitude, radiusMeters) : null;
		base = null;
	}

	public synchronized void clear() {
		replace(null, Double.NaN, Double.NaN, 0);
	}

	/**
	 * R1: the two ways share a node that every position - {latitude, longitude}
	 * - lies within {@code radiusMeters} of.
	 */
	public static boolean meetAt(RouteDataObject a, RouteDataObject b, List<double[]> positions,
			double radiusMeters) {
		return sharedNode(a, b, positions, radiusMeters) != null;
	}

	/**
	 * R1, and where: the first node the two ways share that every position lies
	 * within {@code radiusMeters} of, as {its point index on a, its point index
	 * on b} - or null when there is none.
	 */
	public static int[] sharedNode(RouteDataObject a, RouteDataObject b, List<double[]> positions,
			double radiusMeters) {
		List<int[]> nodes = sharedNodes(a, b, positions, radiusMeters);
		return nodes.isEmpty() ? null : nodes.get(0);
	}

	/**
	 * Every node the two ways share that every position lies within
	 * {@code radiusMeters} of, in a's point order. The junction can be any node
	 * of either way, not only an end - and two ways can share more than one
	 * near a turn (ROADMAP 347), so which of them the truck turned at is for
	 * the caller to decide from where it was before and after.
	 */
	public static List<int[]> sharedNodes(RouteDataObject a, RouteDataObject b,
			List<double[]> positions, double radiusMeters) {
		List<int[]> nodes = new ArrayList<>();
		if (a == null || b == null || positions == null || positions.isEmpty()
				|| a.pointsX == null || b.pointsX == null) {
			return nodes;
		}
		for (int i = 0; i < a.getPointsLength(); i++) {
			for (int j = 0; j < b.getPointsLength(); j++) {
				if (a.getPoint31XTile(i) != b.getPoint31XTile(j)
						|| a.getPoint31YTile(i) != b.getPoint31YTile(j)) {
					continue;
				}
				double latitude = MapUtils.get31LatitudeY(a.getPoint31YTile(i));
				double longitude = MapUtils.get31LongitudeX(a.getPoint31XTile(i));
				boolean all = true;
				for (double[] position : positions) {
					if (position == null || position.length < 2
							|| !(MapUtils.getDistance(position[0], position[1], latitude, longitude)
								<= radiusMeters)) {
						all = false;
						break;
					}
				}
				if (all) {
					nodes.add(new int[] {i, j});
				}
			}
		}
		return nodes;
	}

	/**
	 * R2: the only path from one point to the other when nothing branches off it.
	 *
	 * @return the ways strictly between the two, in travel order - empty for one
	 *         way or two that meet - or null when the path has a branch, cannot
	 *         be followed, or cannot be checked
	 */
	public synchronized List<Step> withoutBranch(RouteDataObject from, boolean fromForward,
			double fromMeasure, RouteDataObject to, boolean toForward, double toMeasure)
			throws IOException {
		extra = null;
		loads = 0;
		try {
			return walk(from, fromForward, fromMeasure, to, toForward, toMeasure);
		} finally {
			extra = null;
		}
	}

	private List<Step> walk(RouteDataObject from, boolean fromForward, double fromMeasure,
			RouteDataObject to, boolean toForward, double toMeasure) throws IOException {
		if (!walkable(from) || !walkable(to)
				|| !Double.isFinite(fromMeasure) || !Double.isFinite(toMeasure)) {
			return null;
		}
		if (from.getId() == to.getId()) {
			if (fromForward != toForward) {
				return null;
			}
			double[] measures = measures(from);
			double low = Math.min(fromMeasure, toMeasure);
			double high = Math.max(fromMeasure, toMeasure);
			for (int index = 0; index < measures.length; index++) {
				if (measures[index] >= low && measures[index] <= high && branchAt(from, index)) {
					return null;
				}
			}
			return Collections.emptyList();
		}

		List<Step> steps = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		visited.add(from.getId());
		RouteDataObject road = from;
		boolean forward = fromForward;
		double measure = fromMeasure;
		boolean entered = false;
		double walked = 0;
		while (true) {
			double[] measures = measures(road);
			int last = measures.length - 1;
			int end = forward ? last : 0;
			int entry = forward ? 0 : last;
			for (int index = 0; index <= last; index++) {
				if (index == end || entered && index == entry) {
					continue;
				}
				boolean ahead = forward ? measures[index] >= measure : measures[index] <= measure;
				if (ahead && branchAt(road, index)) {
					return null;
				}
			}
			walked += forward ? measures[last] - measure : measure;

			// The end of this way: exactly one other road may carry on from it, and
			// only from one of its own ends - otherwise the road goes two ways.
			int x = road.getPoint31XTile(end);
			int y = road.getPoint31YTile(end);
			if (!ensureCovered(x, y)) {
				return null;
			}
			Touch next = null;
			for (Touch touch : touches(x, y)) {
				if (touch.road.getId() == road.getId() && touch.index == end
						|| !forVehicles(touch.road)) {
					continue;
				}
				if (next != null) {
					return null;
				}
				next = touch;
			}
			if (next == null || !walkable(next.road)) {
				return null;
			}
			int nextLast = next.road.getPointsLength() - 1;
			if (next.index != 0 && next.index != nextLast) {
				return null;
			}
			boolean nextForward = next.index == 0;

			if (next.road.getId() == to.getId()) {
				if (nextForward != toForward) {
					return null;
				}
				double[] toMeasures = measures(to);
				int toEntry = nextForward ? 0 : toMeasures.length - 1;
				for (int index = 0; index < toMeasures.length; index++) {
					if (index == toEntry) {
						continue;
					}
					boolean before = nextForward ? toMeasures[index] <= toMeasure
							: toMeasures[index] >= toMeasure;
					if (before && branchAt(to, index)) {
						return null;
					}
				}
				return steps;
			}
			if (!visited.add(next.road.getId())) {
				return null;
			}
			steps.add(new Step(next.road, nextForward));
			double[] nextMeasures = measures(next.road);
			if (steps.size() > MAX_WAYS || walked + nextMeasures[nextLast] > MAX_PATH_METERS) {
				return null;
			}
			road = next.road;
			forward = nextForward;
			measure = nextForward ? 0 : nextMeasures[nextLast];
			entered = true;
		}
	}

	/** Does any road a vehicle could take meet this node of {@code road}? Unknown is yes. */
	private boolean branchAt(RouteDataObject road, int index) throws IOException {
		int x = road.getPoint31XTile(index);
		int y = road.getPoint31YTile(index);
		if (!ensureCovered(x, y)) {
			return true;
		}
		for (Touch touch : touches(x, y)) {
			if (touch.road.getId() == road.getId() && touch.index == index) {
				continue;
			}
			if (forVehicles(touch.road)) {
				return true;
			}
		}
		return false;
	}

	private boolean ensureCovered(int x, int y) throws IOException {
		if (baseArea != null && baseArea.contains(x, y) || extra != null && extra.covers(x, y)) {
			return true;
		}
		if (source == null || loads >= MAX_LOADS_PER_QUESTION) {
			return false;
		}
		loads++;
		double latitude = MapUtils.get31LatitudeY(y);
		double longitude = MapUtils.get31LongitudeX(x);
		List<RouteDataObject> roads = source.around(latitude, longitude, LOAD_RADIUS_METERS);
		if (roads == null) {
			return false;
		}
		if (extra == null) {
			extra = new Layer();
		}
		extra.areas.add(RoadCrewObfSegmentLoader.Bounds31.around(latitude, longitude, LOAD_RADIUS_METERS));
		extra.add(roads, base().ids);
		return extra.covers(x, y);
	}

	private List<Touch> touches(int x, int y) {
		long key = key(x, y);
		List<Touch> found = new ArrayList<>();
		List<Touch> inBase = base().nodes.get(key);
		if (inBase != null) {
			found.addAll(inBase);
		}
		List<Touch> inExtra = extra == null ? null : extra.nodes.get(key);
		if (inExtra != null) {
			found.addAll(inExtra);
		}
		return found;
	}

	private Layer base() {
		if (base == null) {
			base = new Layer();
			if (baseArea != null) {
				base.areas.add(baseArea);
			}
			base.add(baseRoads, Collections.<Long>emptySet());
		}
		return base;
	}

	static boolean forVehicles(RouteDataObject road) {
		String highway;
		try {
			highway = road.getHighway();
		} catch (RuntimeException e) {
			highway = null;
		}
		return highway == null || !NOT_FOR_VEHICLES.contains(highway);
	}

	/** Open, with a shape: a way that closes on itself has no end to walk to. */
	private static boolean walkable(RouteDataObject road) {
		if (road == null || road.pointsX == null || road.pointsY == null || road.getPointsLength() < 2) {
			return false;
		}
		int last = road.getPointsLength() - 1;
		return road.getPoint31XTile(0) != road.getPoint31XTile(last)
				|| road.getPoint31YTile(0) != road.getPoint31YTile(last);
	}

	/** Cumulative distance along the raw point order, as the directed pipeline measures it. */
	static double[] measures(RouteDataObject road) {
		double[] measures = new double[road.getPointsLength()];
		for (int index = 1; index < measures.length; index++) {
			measures[index] = measures[index - 1] + RoadCrewWayCanonical.distanceMeters(
					road.getPoint31XTile(index - 1), road.getPoint31YTile(index - 1),
					road.getPoint31XTile(index), road.getPoint31YTile(index));
		}
		return measures;
	}

	private static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xffffffffL);
	}
}
