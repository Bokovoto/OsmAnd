package net.osmand.plus.roadcrew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stationary speed cameras from the phone's own maps (Galin, 02.10.2026): a sign
 * on the map, "Стационарна камера · 500 м" and one voice warning for a camera
 * ahead - with or without a route. Germany and Switzerland: none at all, the law
 * forbids the warning. France: only a danger zone, never the camera's place.
 */
public class CamerasTest {

	static int passed = 0;

	static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError(what);
		}
		passed++;
	}

	// A local grid in metres around a point on the Тракия motorway.
	static final double BASE_LAT = 42.2;
	static final double BASE_LON = 24.7;

	static double latOf(double northMeters) {
		return BASE_LAT + northMeters / 111_320.0;
	}

	static double lonOf(double eastMeters) {
		return BASE_LON + eastMeters / (111_320.0 * Math.cos(Math.toRadians(BASE_LAT)));
	}

	static RoadCrewCameras.Camera camera(String id, double east, double north, RoadCrewCameras.Rule rule) {
		return new RoadCrewCameras.Camera(id, latOf(north), lonOf(east), 0, rule);
	}

	static RoadCrewCameras.Camera camera(String id, double east, double north) {
		return camera(id, east, north, RoadCrewCameras.Rule.WARN);
	}

	/** A line through the given (east, north) corners, a point every 50 m. */
	static final class Path implements RoadCrewCameras.Line {
		final List<double[]> points = new ArrayList<>();
		int reads = 0;

		Path(double... corners) {
			for (int i = 0; i + 3 < corners.length; i += 2) {
				double e0 = corners[i], n0 = corners[i + 1], e1 = corners[i + 2], n1 = corners[i + 3];
				int steps = Math.max(1, (int) Math.round(Math.hypot(e1 - e0, n1 - n0) / 50.0));
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

	static RoadCrewCameras.Ahead along(List<RoadCrewCameras.Camera> cameras, double east, double north, Path line) {
		return RoadCrewCameras.nextAlong(cameras, RoadCrewCameras.Rule.WARN, latOf(north), lonOf(east), line,
				RoadCrewCameras.SHOW_WITHIN_METERS);
	}

	static boolean near(double actual, double expected) {
		return Math.abs(actual - expected) <= 3;
	}

	/** The road's points as two arrays, through the given (east, north) corners. */
	static double[][] road(double... corners) {
		Path path = new Path(corners);
		double[] lats = new double[path.size()];
		double[] lons = new double[path.size()];
		for (int i = 0; i < path.size(); i++) {
			lats[i] = path.points.get(i)[0];
			lons[i] = path.points.get(i)[1];
		}
		return new double[][]{lats, lons};
	}

	public static void main(String[] args) {
		List<RoadCrewCameras.Camera> one = Collections.singletonList(camera("osm-1", 0, 0));

		// Along a route or a road, driving north towards the camera.
		RoadCrewCameras.Ahead found = along(one, 0, -800, new Path(0, -750, 0, 500));
		check(found != null && found.camera.id.equals("osm-1"), "a camera ahead on the line is found");
		check(near(found.meters, 800), "800 m along the line, got " + found.meters);
		check(along(one, 0, -1100, new Path(0, -1050, 0, 500)) == null, "beyond 1 km: not yet");
		// Mapped beside the road at the pole, not on it: up to 25 m on the real roads.
		check(along(one, 25, -600, new Path(25, -550, 25, 500)) != null, "25 m aside: still this road");
		check(along(one, 45, -600, new Path(45, -550, 45, 500)) == null, "45 m aside: another road");
		check(along(one, 0, 20, new Path(0, 70, 0, 800)) == null, "passed: gone at once");
		found = along(one, -600, -300, new Path(-550, -300, 0, -300, 0, 500));
		check(found != null && near(found.meters, 900), "along the line, not straight: got "
				+ (found == null ? "none" : found.meters));
		List<RoadCrewCameras.Camera> two = Arrays.asList(camera("far", 0, 900), camera("near", 0, 300));
		found = along(two, 0, 0, new Path(0, 50, 0, 1500));
		check(found != null && found.camera.id.equals("near"), "the nearer camera first");
		check(along(Collections.emptyList(), 0, -500, new Path(0, -450, 0, 500)) == null, "no cameras: nothing");
		check(RoadCrewCameras.nextAlong(one, RoadCrewCameras.Rule.WARN, latOf(-500), lonOf(0), new Path(),
				RoadCrewCameras.SHOW_WITHIN_METERS) == null, "no line: nothing");
		// Only the cameras of the asked rule: one in no known country is never a warning.
		List<RoadCrewCameras.Camera> unknown = Collections.singletonList(camera("x", 0, 0, RoadCrewCameras.Rule.OFF));
		check(along(unknown, 0, -500, new Path(0, -450, 0, 500)) == null, "country unknown: never warned");
		List<RoadCrewCameras.Camera> french = Collections.singletonList(camera("fr", 0, 0, RoadCrewCameras.Rule.ZONE));
		check(along(french, 0, -500, new Path(0, -450, 0, 500)) == null, "France: no warning for the camera itself");
		check(RoadCrewCameras.nextAlong(french, RoadCrewCameras.Rule.ZONE, latOf(-3000), lonOf(0),
				new Path(0, -2950, 0, 500), RoadCrewCameras.ZONE_MOTORWAY_METERS) != null,
				"France: found for its zone, up to 4 km ahead");
		// It runs with every map frame: a 500 km route is read only as far as needed.
		Path longRoute = new Path(0, -700, 0, 500_000);
		check(along(one, 0, -700, longRoute) != null, "found on a long route");
		check(longRoute.reads < 120, "read " + longRoute.reads + " points of " + longRoute.size());

		// Without a route: the road the truck is on, in the direction it drives.
		double[][] eastWest = road(-2000, 0, 2000, 0);
		RoadCrewCameras.Line east = RoadCrewCameras.roadAhead(eastWest[0], eastWest[1], latOf(3), lonOf(-500), 88);
		List<RoadCrewCameras.Camera> onRoad = Collections.singletonList(camera("road", 300, 12));
		found = east == null ? null : RoadCrewCameras.nextAlong(onRoad, RoadCrewCameras.Rule.WARN, latOf(3), lonOf(-500),
				east, RoadCrewCameras.SHOW_WITHIN_METERS);
		check(found != null && near(found.meters, 800), "on the road, driving east: 800 m, got "
				+ (found == null ? "none" : found.meters));
		RoadCrewCameras.Line west = RoadCrewCameras.roadAhead(eastWest[0], eastWest[1], latOf(3), lonOf(-500), 268);
		check(west != null && RoadCrewCameras.nextAlong(onRoad, RoadCrewCameras.Rule.WARN, latOf(3), lonOf(-500), west,
				RoadCrewCameras.SHOW_WITHIN_METERS) == null, "driving west: the camera is behind");
		check(RoadCrewCameras.roadAhead(eastWest[0], eastWest[1], latOf(60), lonOf(-500), 90) == null,
				"60 m from the road: not on it");
		// A bend: the road turns north, the camera is round the corner.
		double[][] bend = road(-600, 0, 0, 0, 0, 900);
		RoadCrewCameras.Line roundTheBend = RoadCrewCameras.roadAhead(bend[0], bend[1], latOf(0), lonOf(-500), 90);
		List<RoadCrewCameras.Camera> afterBend = Collections.singletonList(camera("bend", 8, 400));
		found = roundTheBend == null ? null : RoadCrewCameras.nextAlong(afterBend, RoadCrewCameras.Rule.WARN, latOf(0),
				lonOf(-500), roundTheBend, RoadCrewCameras.SHOW_WITHIN_METERS);
		check(found != null && near(found.meters, 900), "round the bend, measured along the road: got "
				+ (found == null ? "none" : found.meters));

		// No road known either: straight ahead only, and not far.
		check(straight(one, 0, -400, 0) != null, "400 m straight ahead");
		check(straight(one, 0, -700, 0) == null, "700 m straight ahead: too far to guess the road");
		check(straight(one, -400, -400, 0) == null, "45 degrees aside: not ahead");
		check(straight(one, 25, -300, 0) != null, "25 m off the line of travel");
		check(straight(one, 0, 100, 0) == null, "behind");
		check(straight(unknown, 0, -300, 0) == null, "country unknown: never, straight ahead either");

		List<RoadCrewCameras.Camera> besideBend = Collections.singletonList(camera("other-road", 400, 0));
		Path knownBend = new Path(50, 0, 100, 0, 100, 1000);
		check(RoadCrewCameras.nextAhead(besideBend, RoadCrewCameras.Rule.WARN, latOf(0), lonOf(0),
				knownBend, 90, 1000) == null, "known bend is not contradicted by straight fallback");
		check(RoadCrewCameras.nextAhead(besideBend, RoadCrewCameras.Rule.WARN, latOf(0), lonOf(0),
				null, 90, 1000) != null, "unknown road retains the nearby-camera fallback");
		check(RoadCrewCameras.nextAhead(besideBend, RoadCrewCameras.Rule.WARN, latOf(0), lonOf(0),
				new Path(), 90, 1000) == null, "finished route does not become a straight guess");
		check(RoadCrewCameras.nextAhead(besideBend, RoadCrewCameras.Rule.WARN, latOf(0), lonOf(0),
				null, Double.NaN, 1000) == null, "no heading and no line means no directional guess");

		// Which countries: from the map's region names around the camera.
		// Germany and Switzerland warn like anywhere (Galin, 02.10.2026: the
		// driver turns them off himself); the country is known for the notice.
		List<String> bavaria = Arrays.asList("europe_germany_bayern", "europe_germany");
		check(RoadCrewCameras.ruleForRegions(bavaria) == RoadCrewCameras.Rule.WARN, "Germany: warn, the driver decides");
		check(RoadCrewCameras.countryForRegions(bavaria) == RoadCrewCameras.Country.GERMANY, "Germany is known");
		List<String> zurich = Arrays.asList("europe_switzerland", "europe_switzerland_zurich");
		check(RoadCrewCameras.ruleForRegions(zurich) == RoadCrewCameras.Rule.WARN, "Switzerland: warn, the driver decides");
		check(RoadCrewCameras.countryForRegions(zurich) == RoadCrewCameras.Country.SWITZERLAND, "Switzerland is known");
		List<String> paris = Arrays.asList("europe_france", "europe_france_ile-de-france");
		check(RoadCrewCameras.ruleForRegions(paris) == RoadCrewCameras.Rule.ZONE, "France: zone");
		check(RoadCrewCameras.countryForRegions(paris) == RoadCrewCameras.Country.FRANCE, "France is known");
		check(RoadCrewCameras.ruleForRegions(Collections.singletonList("europe_bulgaria"))
				== RoadCrewCameras.Rule.WARN, "Bulgaria: warn");
		check(RoadCrewCameras.countryForRegions(Collections.singletonList("europe_germanyx"))
				== RoadCrewCameras.Country.OTHER, "a name that only starts like Germany is not Germany");
		check(RoadCrewCameras.ruleForRegions(Collections.emptyList()) == RoadCrewCameras.Rule.OFF,
				"country unknown: off - it could be France");
		check(RoadCrewCameras.countryForRegions(Collections.emptyList()) == null, "country unknown: none");

		// The limit, when the map has one.
		check(RoadCrewCameras.parseLimit("90") == 90, "90");
		check(RoadCrewCameras.parseLimit("50 mph") == 80, "50 mph is 80 km/h");
		check(RoadCrewCameras.parseLimit("100;80") == 100, "the first of two");
		check(RoadCrewCameras.parseLimit("signals") == 0, "no number: none");
		check(RoadCrewCameras.parseLimit(null) == 0, "nothing: none");

		// France: a zone of a fixed length with the camera somewhere inside.
		check(RoadCrewCameras.zoneLength(true, 130) == 4000, "motorway: 4 km");
		check(RoadCrewCameras.zoneLength(false, 50) == 300, "in town: 300 m");
		check(RoadCrewCameras.zoneLength(false, 90) == 2000, "other roads: 2 km");
		check(RoadCrewCameras.zoneLength(false, 0) == 2000, "limit unknown: 2 km");
		double offsetA = RoadCrewCameras.zoneOffset("osm-111", 2000);
		double offsetB = RoadCrewCameras.zoneOffset("osm-222", 2000);
		check(offsetA >= 400 && offsetA <= 1600, "inside the middle of the zone: " + offsetA);
		check(offsetA == RoadCrewCameras.zoneOffset("osm-111", 2000), "the same camera, the same zone");
		check(offsetA != offsetB, "not the same place in every zone, or the zone would give the camera away");

		RoadCrewCameras.Camera fr = camera("osm-111", 0, 0, RoadCrewCameras.Rule.ZONE);
		RoadCrewCameras.ZoneTracker zones = new RoadCrewCameras.ZoneTracker();
		check(zones.update(new RoadCrewCameras.Ahead(fr, offsetA + 200), 2000, latOf(-offsetA - 200), lonOf(0)) == null,
				"before the zone");
		RoadCrewCameras.Zone zone = zones.update(new RoadCrewCameras.Ahead(fr, offsetA - 10), 2000,
				latOf(-offsetA + 10), lonOf(0));
		check(zone != null && zone.length == 2000, "in the zone");
		zones.update(new RoadCrewCameras.Ahead(fr, 20), 2000, latOf(-20), lonOf(0));
		double tail = 2000 - offsetA;
		check(zones.update(null, 2000, latOf(tail - 50), lonOf(0)) != null, "past the camera: the zone goes on");
		check(zones.update(null, 2000, latOf(tail + 50), lonOf(0)) == null, "the zone ends");
		RoadCrewCameras.ZoneTracker turned = new RoadCrewCameras.ZoneTracker();
		turned.update(new RoadCrewCameras.Ahead(fr, offsetA - 300), 2000, latOf(-offsetA + 300), lonOf(0));
		check(turned.update(null, 2000, latOf(-offsetA + 300), lonOf(200)) == null,
				"turned off before the camera: the zone ends");

		// One voice warning per camera and pass, from 500 m.
		RoadCrewCameras.Voice voice = new RoadCrewCameras.Voice();
		long now = 1_000_000L;
		check(!voice.shouldSpeak("osm-1", 900, now), "900 m: not yet");
		check(voice.shouldSpeak("osm-1", 480, now), "480 m: say it");
		voice.spoken("osm-1", now);
		check(!voice.shouldSpeak("osm-1", 200, now + 10_000), "said once for this pass");
		check(voice.shouldSpeak("osm-2", 450, now + 20_000), "another camera is said");
		check(voice.shouldSpeak("osm-1", 450, now + 31 * 60_000L), "the same camera on a later trip");

		RoadCrewCameras.ZoneTracker skippedFixes = new RoadCrewCameras.ZoneTracker();
		skippedFixes.update(new RoadCrewCameras.Ahead(fr, 100), 2000, latOf(-100), lonOf(0));
		check(skippedFixes.update(null, 2000, latOf(-100), lonOf(0)) != null,
				"stopped with no useful heading: keep the current zone");
		check(skippedFixes.update(null, 2000, latOf(20), lonOf(0)) != null,
				"a fix gap from 100 m before to 20 m after does not end the zone");
		check(skippedFixes.update(null, 2000, latOf(20), lonOf(tail * 0.6)) != null,
				"partway through curved tail");
		check(skippedFixes.update(null, 2000, latOf(20 + tail * 0.6), lonOf(tail * 0.6)) == null,
				"curved tail ends by distance travelled, not radius around the camera");
		System.out.println(passed + " camera checks passed");
	}

	static RoadCrewCameras.Ahead straight(List<RoadCrewCameras.Camera> cameras, double east, double north, double bearing) {
		return RoadCrewCameras.nextStraightAhead(cameras, RoadCrewCameras.Rule.WARN, latOf(north), lonOf(east), bearing);
	}
}
