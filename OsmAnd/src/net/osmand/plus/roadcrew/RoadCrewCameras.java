package net.osmand.plus.roadcrew;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stationary speed cameras: which one is ahead, and when to say it. Plain Java,
 * no Android - tools/tests/roadcrew-cameras.test.mjs.
 *
 * Galin, 02.10.2026: the cameras are in the phone's maps already (OpenStreetMap)
 * but nobody saw them. The approved mockup: a sign on the map, "Стационарна
 * камера · 500 м" and one voice warning, with or without a route. France: only
 * a danger zone that does not give the camera's place away (his choice).
 * Germany and Switzerland forbid the driver the warning; there the cameras work
 * as anywhere and the driver turns them off himself (his decision the same
 * day) - the screen tells him the law on entering. The cameras come from
 * RoadCrewCamerasSource.
 */
public final class RoadCrewCameras {

	/**
	 * How far from the line of driving a camera still counts as on it: mapped
	 * beside the road, at the pole, it stands up to 25 m off the road's line.
	 */
	static final double CORRIDOR_METERS = 30;
	/** "Стационарна камера · 800 м" from this far along the road. */
	static final double SHOW_WITHIN_METERS = 1000;
	/** "Стационарна камера след 500 метра" - once per pass. */
	static final double SPEAK_WITHIN_METERS = 500;
	/** With no route and no known road: only this far, straight ahead. */
	static final double STRAIGHT_AHEAD_METERS = 600;
	/** Farther than this from the road the map gives, the truck is not on it. */
	static final double ON_ROAD_METERS = 40;
	/** The same camera is said again only on a later trip past it. */
	static final long SPEAK_AGAIN_AFTER_MILLIS = 30 * 60 * 1000L;
	/** France: the zones' lengths - motorway, other roads, in town. */
	static final double ZONE_MOTORWAY_METERS = 4000;
	static final double ZONE_ROAD_METERS = 2000;
	static final double ZONE_TOWN_METERS = 300;
	/** Last seen this close, a camera that is gone from ahead was passed. */
	static final double PASSED_WITHIN_METERS = 80;

	private static final double METERS_PER_DEGREE = 111_320.0;
	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";

	private RoadCrewCameras() {
	}

	/** How a camera is shown, by its country. */
	enum Rule {
		/** The sign, the distance and the voice. */
		WARN,
		/** A country not known - it could be France. */
		OFF,
		/** France: a danger zone only. */
		ZONE
	}

	static final class Camera {
		final String id;
		final double lat;
		final double lon;
		/** The limit the camera enforces when the map has it; 0 when not. */
		final int limitKmh;
		final Rule rule;

		Camera(String id, double lat, double lon, int limitKmh, Rule rule) {
			this.id = id;
			this.lat = lat;
			this.lon = lon;
			this.limitKmh = limitKmh;
			this.rule = rule;
		}
	}

	/** A camera ahead and how far along the road it is. */
	static final class Ahead {
		final Camera camera;
		final double meters;

		Ahead(Camera camera, double meters) {
			this.camera = camera;
			this.meters = meters;
		}
	}

	/** The way ahead in driving order - the route, or the road the truck is on. */
	interface Line {
		int size();

		double lat(int index);

		double lon(int index);
	}

	/**
	 * RoadCrew warns for the cameras itself - more of them, and France only as
	 * a zone - so OsmAnd's own camera warnings stay off in it: one warning, never
	 * two. OsmAnd's two switches for cameras drive RoadCrew's warning instead.
	 */
	public static boolean replacesBuiltInAlarms(String packageName) {
		return ROADCREW_PACKAGE.equals(packageName);
	}

	/** The countries whose law the screen tells on entering. */
	enum Country {
		GERMANY,
		SWITZERLAND,
		FRANCE,
		OTHER
	}

	/**
	 * The country from the names of the map regions a point lies in
	 * ("europe_germany", "europe_germany_bayern"...); null when none is known.
	 */
	static Country countryForRegions(Collection<String> regionNames) {
		if (regionNames.isEmpty()) {
			return null;
		}
		for (String name : regionNames) {
			if (inCountry(name, "europe_germany")) {
				return Country.GERMANY;
			}
			if (inCountry(name, "europe_switzerland")) {
				return Country.SWITZERLAND;
			}
		}
		for (String name : regionNames) {
			if (inCountry(name, "europe_france")) {
				return Country.FRANCE;
			}
		}
		return Country.OTHER;
	}

	/**
	 * France: the zone. Not known: OFF - it could be a French camera, whose
	 * place must not be shown. Everywhere else, Germany and Switzerland too:
	 * the warning, and the driver's switches decide.
	 */
	static Rule ruleOf(Country country) {
		if (country == null) {
			return Rule.OFF;
		}
		return country == Country.FRANCE ? Rule.ZONE : Rule.WARN;
	}

	static Rule ruleForRegions(Collection<String> regionNames) {
		return ruleOf(countryForRegions(regionNames));
	}

	private static boolean inCountry(String regionName, String country) {
		return regionName != null && (regionName.equals(country) || regionName.startsWith(country + "_"));
	}

	/** The map's maxspeed ("90", "50 mph", "100;80") in km/h; 0 when it is no number. */
	static int parseLimit(String value) {
		if (value == null) {
			return 0;
		}
		String text = value.trim();
		int end = 0;
		while (end < text.length() && end < 3 && Character.isDigit(text.charAt(end))) {
			end++;
		}
		if (end == 0) {
			return 0;
		}
		int number = Integer.parseInt(text.substring(0, end));
		if (text.substring(end).trim().startsWith("mph")) {
			number = (int) Math.round(number * 1.609344);
		}
		return number <= 200 ? number : 0;
	}

	/**
	 * The nearest camera of the rule within withinMeters along the line. The
	 * first leg runs from the truck to the line's first point, and a camera
	 * behind the truck on it is already passed. Only the line's first
	 * withinMeters are read: this runs with every map frame.
	 */
	static Ahead nextAlong(List<Camera> cameras, Rule rule, double lat, double lon, Line line, double withinMeters) {
		if (cameras.isEmpty() || line.size() == 0) {
			return null;
		}
		// Along the road is never shorter than in a straight line.
		List<Camera> reachable = new ArrayList<>();
		for (Camera camera : cameras) {
			if (camera.rule == rule && straightMeters(lat, lon, camera.lat, camera.lon) <= withinMeters + CORRIDOR_METERS) {
				reachable.add(camera);
			}
		}
		if (reachable.isEmpty()) {
			return null;
		}
		// Per camera, the point where the line passes closest to it.
		double[] closest = new double[reachable.size()];
		double[] metersAt = new double[reachable.size()];
		Arrays.fill(closest, Double.MAX_VALUE);
		double fromLat = lat;
		double fromLon = lon;
		double along = 0;
		for (int i = 0; i < line.size() && along <= withinMeters; i++) {
			double toLat = line.lat(i);
			double toLon = line.lon(i);
			double kx = METERS_PER_DEGREE * Math.cos(Math.toRadians(fromLat));
			double dx = (toLon - fromLon) * kx;
			double dy = (toLat - fromLat) * METERS_PER_DEGREE;
			double length = Math.hypot(dx, dy);
			if (length <= 0) {
				continue;
			}
			for (int c = 0; c < reachable.size(); c++) {
				Camera camera = reachable.get(c);
				double cx = (camera.lon - fromLon) * kx;
				double cy = (camera.lat - fromLat) * METERS_PER_DEGREE;
				double t = (cx * dx + cy * dy) / (length * length);
				if (i == 0 && t < 0) {
					continue;
				}
				t = Math.max(0, Math.min(1, t));
				double aside = Math.hypot(cx - t * dx, cy - t * dy);
				if (aside <= CORRIDOR_METERS && aside < closest[c]) {
					closest[c] = aside;
					metersAt[c] = along + t * length;
				}
			}
			along += length;
			fromLat = toLat;
			fromLon = toLon;
		}
		Ahead best = null;
		for (int c = 0; c < reachable.size(); c++) {
			if (closest[c] != Double.MAX_VALUE && metersAt[c] <= withinMeters
					&& (best == null || metersAt[c] < best.meters)) {
				best = new Ahead(reachable.get(c), metersAt[c]);
			}
		}
		return best;
	}

	/**
	 * The road the truck is on (its points as the map gives them) as a line
	 * ahead: from the road point after the truck, in the direction it drives.
	 * Null when the truck is not on this road.
	 */
	static Line roadAhead(double[] lats, double[] lons, double lat, double lon, double bearingDegrees) {
		int count = Math.min(lats.length, lons.length);
		if (count < 2) {
			return null;
		}
		double kx = METERS_PER_DEGREE * Math.cos(Math.toRadians(lat));
		int nearest = -1;
		double nearestMeters = Double.MAX_VALUE;
		double nearestT = 0;
		for (int i = 0; i + 1 < count; i++) {
			double ax = (lons[i] - lon) * kx;
			double ay = (lats[i] - lat) * METERS_PER_DEGREE;
			double dx = (lons[i + 1] - lons[i]) * kx;
			double dy = (lats[i + 1] - lats[i]) * METERS_PER_DEGREE;
			double lengthSquared = dx * dx + dy * dy;
			double t = lengthSquared <= 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / lengthSquared));
			double meters = Math.hypot(ax + t * dx, ay + t * dy);
			if (meters < nearestMeters) {
				nearestMeters = meters;
				nearest = i;
				nearestT = t;
			}
		}
		if (nearest < 0 || nearestMeters > ON_ROAD_METERS) {
			return null;
		}
		double segmentBearing = Math.toDegrees(Math.atan2((lons[nearest + 1] - lons[nearest]) * kx,
				(lats[nearest + 1] - lats[nearest]) * METERS_PER_DEGREE));
		boolean forward = Math.abs(normalizeDegrees(segmentBearing - bearingDegrees)) <= 90;
		// The first road point strictly ahead: standing on a point, the line
		// starts at the one after it, not with a step sideways onto the road.
		int start;
		if (forward) {
			start = nearestT >= 0.999 ? nearest + 2 : nearest + 1;
		} else {
			start = nearestT <= 0.001 ? nearest - 1 : nearest;
		}
		int step = forward ? 1 : -1;
		int size = forward ? Math.max(0, count - start) : Math.max(0, start + 1);
		return new Line() {
			@Override
			public int size() {
				return size;
			}

			@Override
			public double lat(int index) {
				return lats[start + step * index];
			}

			@Override
			public double lon(int index) {
				return lons[start + step * index];
			}
		};
	}

	/**
	 * No route and no road known: the nearest camera of the rule straight
	 * ahead, within STRAIGHT_AHEAD_METERS and CORRIDOR_METERS of the line of
	 * travel - a guess too uncertain to make any farther.
	 */
	static Ahead nextStraightAhead(List<Camera> cameras, Rule rule, double lat, double lon, double bearingDegrees) {
		double radians = Math.toRadians(bearingDegrees);
		double ux = Math.sin(radians);
		double uy = Math.cos(radians);
		double kx = METERS_PER_DEGREE * Math.cos(Math.toRadians(lat));
		Ahead best = null;
		for (Camera camera : cameras) {
			if (camera.rule != rule) {
				continue;
			}
			double x = (camera.lon - lon) * kx;
			double y = (camera.lat - lat) * METERS_PER_DEGREE;
			double along = x * ux + y * uy;
			double aside = Math.abs(x * uy - y * ux);
			if (along > 0 && along <= STRAIGHT_AHEAD_METERS && aside <= CORRIDOR_METERS
					&& (best == null || along < best.meters)) {
				best = new Ahead(camera, along);
			}
		}
		return best;
	}

	/** France: the zone's length by the road - motorway, in town (50 km/h or less), other. */
	static double zoneLength(boolean motorway, int roadLimitKmh) {
		if (motorway) {
			return ZONE_MOTORWAY_METERS;
		}
		if (roadLimitKmh > 0 && roadLimitKmh <= 50) {
			return ZONE_TOWN_METERS;
		}
		return ZONE_ROAD_METERS;
	}

	/**
	 * How far into its zone the camera stands: 20-80 % of the length, the same
	 * every time for the same camera and different between cameras - a zone
	 * that always ended at the camera would give its place away.
	 */
	static double zoneOffset(String cameraId, double length) {
		int hash = cameraId.hashCode();
		hash ^= hash >>> 16;
		hash *= 0x45d9f3b;
		hash ^= hash >>> 16;
		double unit = (hash & 0x7fffffff) / (double) Integer.MAX_VALUE;
		return length * (0.2 + 0.6 * unit);
	}

	/** A danger zone the truck is in. Its camera is never shown. */
	static final class Zone {
		final Camera camera;
		final double length;

		Zone(Camera camera, double length) {
			this.camera = camera;
			this.length = length;
		}
	}

	/**
	 * The zone the truck is in: from zoneOffset before the camera, on past it
	 * for the rest of the zone's length. A camera that disappears from ahead
	 * while still far was not passed - the truck turned off - and its zone ends.
	 */
	static final class ZoneTracker {
		private Camera camera;
		private double length;
		private double offset;
		private double lastSeenMeters;

		Zone update(Ahead ahead, double zoneLength, double lat, double lon) {
			if (ahead != null) {
				double aheadOffset = zoneOffset(ahead.camera.id, zoneLength);
				if (ahead.meters <= aheadOffset) {
					camera = ahead.camera;
					length = zoneLength;
					offset = aheadOffset;
					lastSeenMeters = ahead.meters;
					return new Zone(camera, length);
				}
				if (camera != null && camera.id.equals(ahead.camera.id)) {
					camera = null;
					return null;
				}
			}
			return tail(lat, lon);
		}

		private Zone tail(double lat, double lon) {
			if (camera == null) {
				return null;
			}
			if (lastSeenMeters > PASSED_WITHIN_METERS
					|| straightMeters(lat, lon, camera.lat, camera.lon) > length - offset) {
				camera = null;
				return null;
			}
			return new Zone(camera, length);
		}
	}

	/** One voice warning per camera and pass. */
	static final class Voice {
		private final Map<String, Long> spokenAt = new HashMap<>();

		boolean shouldSpeak(String cameraId, double meters, long now) {
			if (meters > SPEAK_WITHIN_METERS) {
				return false;
			}
			Long last = spokenAt.get(cameraId);
			return last == null || now - last >= SPEAK_AGAIN_AFTER_MILLIS;
		}

		void spoken(String cameraId, long now) {
			spokenAt.put(cameraId, now);
		}
	}

	private static double straightMeters(double lat1, double lon1, double lat2, double lon2) {
		double kx = METERS_PER_DEGREE * Math.cos(Math.toRadians(lat1));
		return Math.hypot((lon2 - lon1) * kx, (lat2 - lat1) * METERS_PER_DEGREE);
	}

	private static double normalizeDegrees(double degrees) {
		double normalized = (degrees + 540.0) % 360.0 - 180.0;
		return normalized == -180.0 ? 180.0 : normalized;
	}
}
