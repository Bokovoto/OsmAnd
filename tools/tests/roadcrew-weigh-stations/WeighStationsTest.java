package net.osmand.plus.roadcrew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stationary weigh stations (Galin, 29.09.2026): the navigation warns only when
 * a station is on the route ahead - "Стационарен кантар · 500 м" from 2 km, and
 * one voice warning from 500 m. Every station weighs both directions.
 */
public class WeighStationsTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	// Кабиле, on the A1: a real station as the origin of a local grid in metres.
	static final double BASE_LAT = 42.54067;
	static final double BASE_LON = 26.484135;

	static double latOf(double northMeters) {
		return BASE_LAT + northMeters / 111_320.0;
	}

	static double lonOf(double eastMeters) {
		return BASE_LON + eastMeters / (111_320.0 * Math.cos(Math.toRadians(BASE_LAT)));
	}

	static RoadCrewWeighStations.Station station(String id, double east, double north) {
		return new RoadCrewWeighStations.Station(id, id, latOf(north), lonOf(east));
	}

	/** A route through the given (east, north) corners, a point every 50 m. */
	static final class Path implements RoadCrewWeighStations.Route {
		final List<double[]> points = new ArrayList<>();
		int reads = 0;

		Path(double... corners) {
			this(50.0, corners);
		}

		/** Named, not an overload: Path(0, -1450, ...) would pick an int spacing. */
		static Path every(double spacing, double... corners) {
			return new Path(spacing, corners);
		}

		private Path(double spacing, double[] corners) {
			for (int i = 0; i + 3 < corners.length; i += 2) {
				double e0 = corners[i], n0 = corners[i + 1], e1 = corners[i + 2], n1 = corners[i + 3];
				int steps = Math.max(1, (int) Math.round(Math.hypot(e1 - e0, n1 - n0) / spacing));
				for (int s = i == 0 ? 0 : 1; s <= steps; s++) {
					double t = (double) s / steps;
					points.add(new double[]{latOf(n0 + t * (n1 - n0)), lonOf(e0 + t * (e1 - e0))});
				}
			}
		}

		@Override
		public int size() {
			return points.size();
		}

		@Override
		public double lat(int index) {
			reads++;
			return points.get(index)[0];
		}

		@Override
		public double lon(int index) {
			return points.get(index)[1];
		}
	}

	static RoadCrewWeighStations.Ahead ahead(List<RoadCrewWeighStations.Station> stations,
			double east, double north, Path route) {
		return RoadCrewWeighStations.nextOnRoute(stations, latOf(north), lonOf(east), route);
	}

	static boolean near(double actual, double expected) {
		return Math.abs(actual - expected) <= 3;
	}

	public static void main(String[] args) {
		List<RoadCrewWeighStations.Station> kabile = Collections.singletonList(station("bgtoll-20131", 0, 0));

		// Driving north towards the station on the route.
		RoadCrewWeighStations.Ahead found = ahead(kabile, 0, -1500, new Path(0, -1450, 0, 500));
		check(found != null && found.station.id.equals("bgtoll-20131"), "a station on the route ahead is found");
		check(near(found.meters, 1500), "1.5 km along the route, got " + found.meters);
		check(ahead(kabile, 0, -2100, new Path(0, -2050, 0, 500)) == null, "beyond 2 km: not yet");
		// Route points every 5 m, as on a winding road: the distance is to the
		// point where the route passes the station, not to the first point that
		// comes within 30 m of it.
		found = ahead(kabile, 0, -1500, Path.every(5, 0, -1495, 0, 500));
		check(found != null && near(found.meters, 1500), "dense route: 1.5 km, got " + (found == null ? "none" : found.meters));
		found = ahead(kabile, 0, -400, new Path(0, -350, 0, 500));
		check(found != null && near(found.meters, 400), "close: the distance keeps counting down");

		// The route follows the other carriageway, up to 21 m away on the real roads.
		check(ahead(kabile, 25, -800, new Path(25, -750, 25, 500)) != null, "25 m aside: still this road");
		// The nearest other road at any of the ten places: a field lane 49 m
		// from Богатово.
		check(ahead(kabile, 49, -800, new Path(49, -750, 49, 500)) == null, "49 m aside: another road");

		// Just past it: within 30 m, but behind.
		check(ahead(kabile, 0, 20, new Path(0, 70, 0, 800)) == null, "passed: gone at once");
		check(ahead(kabile, 0, 1, new Path(0, 51, 0, 800)) == null, "a metre past: gone");

		// Along the route, not in a straight line: 800 m east, then 600 m north.
		found = ahead(kabile, -800, -600, new Path(-750, -600, 0, -600, 0, 500));
		check(found != null && near(found.meters, 1400), "along the route, got " + (found == null ? "none" : found.meters));
		// A route that only passes 150 m below the station, going east: not on it.
		check(ahead(kabile, -1000, -150, new Path(-950, -150, 1000, -150)) == null, "passing by is not passing over");

		// Two stations ahead (Волуяк and Илиянци are 2 km apart): the nearer one.
		List<RoadCrewWeighStations.Station> two = Arrays.asList(station("far", 0, 1500), station("near", 0, 500));
		found = ahead(two, 0, 0, new Path(0, 50, 0, 2500));
		check(found != null && found.station.id.equals("near") && near(found.meters, 500), "the nearer station first");

		check(ahead(Collections.emptyList(), 0, -500, new Path(0, -450, 0, 500)) == null, "no stations: nothing");
		check(RoadCrewWeighStations.nextOnRoute(kabile, latOf(-500), lonOf(0), new Path()) == null, "no route: nothing");

		// It runs with every map frame in navigation: a 500 km route is read only
		// as far as the next 2 km.
		Path long_ = new Path(0, -1000, 0, 500_000);
		check(ahead(kabile, 0, -1000, long_) != null, "found on a long route");
		check(long_.reads < 200, "read " + long_.reads + " route points of " + long_.size());

		// One voice warning per pass, from 500 m.
		RoadCrewWeighStations.Voice voice = new RoadCrewWeighStations.Voice();
		long now = 1_000_000L;
		RoadCrewWeighStations.Station s = kabile.get(0);
		check(!voice.shouldSpeak(new RoadCrewWeighStations.Ahead(s, 1200), now), "1.2 km: not yet");
		check(voice.shouldSpeak(new RoadCrewWeighStations.Ahead(s, 480), now), "480 m: say it");
		voice.spoken(s, now);
		check(!voice.shouldSpeak(new RoadCrewWeighStations.Ahead(s, 300), now + 10_000), "said once for this pass");
		check(voice.shouldSpeak(new RoadCrewWeighStations.Ahead(station("other", 0, 0), 450), now + 20_000),
				"another station is said");
		check(!voice.shouldSpeak(null, now), "nothing ahead: silence");
		check(voice.shouldSpeak(new RoadCrewWeighStations.Ahead(s, 450), now + 31 * 60_000L),
				"the same station on a later trip is said again");

		System.out.println(passed + " weigh station checks passed");
	}
}
