package net.osmand.plus.roadcrew;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stationary weigh stations (WIM): which one the route reaches next, and when
 * to say it. Plain Java, no Android - tools/tests/roadcrew-weigh-stations.test.mjs.
 *
 * Galin, 29.09.2026: a layer of its own, always shown, never expiring and never
 * voted on. The navigation warns only when a station is on the route - there is
 * no direction to check, every station weighs both directions under one toll
 * frame. The list comes from the server (RoadCrewWeighStationsStore).
 */
final class RoadCrewWeighStations {

	/**
	 * How far from the route a station still counts as on it. Checked against
	 * OpenStreetMap on 29.09.2026: every station lies 0-7 m from its road, the
	 * other carriageway 11-21 m, the nearest other road 49 m or more (a field
	 * lane at Богатово; 70 m at the other nine).
	 */
	static final double ROUTE_CORRIDOR_METERS = 30;
	/** "Стационарен кантар · 1,2 км" from this far along the route. */
	static final double SHOW_WITHIN_METERS = 2000;
	/** "Стационарен кантар след 500 метра" - once per pass. */
	static final double SPEAK_WITHIN_METERS = 500;
	/** The same station is said again only on a later trip past it. */
	static final long SPEAK_AGAIN_AFTER_MILLIS = 30 * 60 * 1000L;

	private static final double METERS_PER_DEGREE = 111_320.0;

	private RoadCrewWeighStations() {
	}

	static final class Station {
		final String id;
		final String name;
		final double lat;
		final double lon;

		Station(String id, String name, double lat, double lon) {
			this.id = id;
			this.name = name;
			this.lat = lat;
			this.lon = lon;
		}
	}

	/** A station on the route ahead and how far along the route it is. */
	static final class Ahead {
		final Station station;
		final double meters;

		Ahead(Station station, double meters) {
			this.station = station;
			this.meters = meters;
		}
	}

	/** The rest of the route, from the next route point on. */
	interface Route {
		int size();

		double lat(int index);

		double lon(int index);
	}

	/**
	 * The nearest station on the route within SHOW_WITHIN_METERS of the truck,
	 * or null. The first leg runs from the truck to the next route point, and a
	 * station behind the truck on it is already passed. Only the next 2 km of
	 * the route are read: this runs with every map frame in navigation.
	 */
	static Ahead nextOnRoute(List<Station> stations, double lat, double lon, Route route) {
		if (stations.isEmpty() || route.size() == 0) {
			return null;
		}
		// Per station, the point where the route passes closest to it: a route
		// point just before the station is also within the corridor, and would
		// put the station up to 30 m early.
		double[] closest = new double[stations.size()];
		double[] metersAt = new double[stations.size()];
		Arrays.fill(closest, Double.MAX_VALUE);
		double fromLat = lat;
		double fromLon = lon;
		double along = 0;
		for (int i = 0; i < route.size() && along <= SHOW_WITHIN_METERS; i++) {
			double toLat = route.lat(i);
			double toLon = route.lon(i);
			double kx = METERS_PER_DEGREE * Math.cos(Math.toRadians(fromLat));
			double dx = (toLon - fromLon) * kx;
			double dy = (toLat - fromLat) * METERS_PER_DEGREE;
			double length = Math.hypot(dx, dy);
			if (length <= 0) {
				continue;
			}
			for (int s = 0; s < stations.size(); s++) {
				Station station = stations.get(s);
				double sx = (station.lon - fromLon) * kx;
				double sy = (station.lat - fromLat) * METERS_PER_DEGREE;
				double t = (sx * dx + sy * dy) / (length * length);
				if (i == 0 && t < 0) {
					continue;
				}
				t = Math.max(0, Math.min(1, t));
				double offRoute = Math.hypot(sx - t * dx, sy - t * dy);
				if (offRoute <= ROUTE_CORRIDOR_METERS && offRoute < closest[s]) {
					closest[s] = offRoute;
					metersAt[s] = along + t * length;
				}
			}
			along += length;
			fromLat = toLat;
			fromLon = toLon;
		}
		Ahead best = null;
		for (int s = 0; s < stations.size(); s++) {
			if (closest[s] != Double.MAX_VALUE && metersAt[s] <= SHOW_WITHIN_METERS
					&& (best == null || metersAt[s] < best.meters)) {
				best = new Ahead(stations.get(s), metersAt[s]);
			}
		}
		return best;
	}

	/** One voice warning per station and pass. */
	static final class Voice {
		private final Map<String, Long> spokenAt = new HashMap<>();

		boolean shouldSpeak(Ahead ahead, long now) {
			if (ahead == null || ahead.meters > SPEAK_WITHIN_METERS) {
				return false;
			}
			Long last = spokenAt.get(ahead.station.id);
			return last == null || now - last >= SPEAK_AGAIN_AFTER_MILLIS;
		}

		void spoken(Station station, long now) {
			spokenAt.put(station.id, now);
		}
	}
}
