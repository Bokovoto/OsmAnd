package net.osmand.plus.roadcrew;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.util.MapUtils;
import java.util.List;

/** Application-owned warnings; map drawing consumes snapshots, never drives speech. */
public final class RoadCrewRoadAlerts {
	private static volatile RoadCrewRoadAlerts instance;
	private static final float CAMERA_MIN_HEADING_SPEED_MPS = 2.0f;
	private final OsmandApplication app;
	private final RoadCrewVoiceAlerts voiceAlerts;
	private volatile State state = new State();
	private long lastTime = Long.MIN_VALUE;
	private double lastLat;
	private double lastLon;

	static final class State {
		RoadCrewWeighStations.Ahead station;
		CameraState camera = new CameraState();
		RoadCrewCameras.Country country;
	}

	private RoadCrewRoadAlerts(OsmandApplication app) {
		this.app = app;
		voiceAlerts = new RoadCrewVoiceAlerts(app);
	}

	private static RoadCrewRoadAlerts get(OsmandApplication app) {
		if (instance == null) {
			instance = new RoadCrewRoadAlerts(app);
		}
		return instance;
	}

	static State snapshot() {
		RoadCrewRoadAlerts current = instance;
		return current == null ? new State() : current.state;
	}

	/** Both location-provider paths call this after routing has consumed the fix. */
	public static void onLocation(OsmandApplication app, @Nullable Location location) {
		if (!RoadCrewReportsLayer.isEnabled(app)) {
			return;
		}
		Location copy = location == null ? null : new Location(location);
		app.runInUIThread(() -> get(app).update(copy));
	}

	private void update(@Nullable Location location) {
		if (location == null) {
			state = new State();
			lastTime = Long.MIN_VALUE;
			return;
		}
		if (location.getTime() == lastTime && location.getLatitude() == lastLat
				&& location.getLongitude() == lastLon) {
			return;
		}
		lastTime = location.getTime();
		lastLat = location.getLatitude();
		lastLon = location.getLongitude();
		State next = new State();
		RoadCrewWeighStationsStore.refreshPeriodically(app);
		next.station = findWeighStationAhead(location);
		next.camera = findCameraAhead(location);
		next.country = RoadCrewCamerasSource.truckCountry(app, lastLat, lastLon);
		state = next;
		voiceAlerts.checkWeighStation(next.station);
		voiceAlerts.checkCamera(next.camera.ahead);
		voiceAlerts.checkCameraZone(next.camera.zone);
		voiceAlerts.check(RoadCrewReportsRepository.getVisibleReports(app), location);
	}

	/** France: the danger zone the truck is in, if any. */
	private final RoadCrewCameras.ZoneTracker cameraZones = new RoadCrewCameras.ZoneTracker();
	/** The points of the road the truck is on, read once per road. */
	@Nullable
	private RouteDataObject cameraRoad;
	private double[] cameraRoadLats = new double[0];
	private double[] cameraRoadLons = new double[0];

	/** Snapshot for the last location: a nearby camera, or France's zone. */
	static final class CameraState {
		@Nullable
		RoadCrewCameras.Ahead ahead;
		@Nullable
		RoadCrewCameras.Zone zone;
	}

	@NonNull
	private CameraState findCameraAhead(Location location) {
		CameraState state = new CameraState();
		if (location == null) {
			return state;
		}
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		List<RoadCrewCameras.Camera> cameras = RoadCrewCamerasSource.aroundTruck(app, lat, lon);
		RouteDataObject road = app.getLocationProvider().getLastKnownRouteSegment(location);
		double zoneLength = cameraZoneLength(road, location);
		RoadCrewCameras.Ahead zoneAhead = null;
		if (!cameras.isEmpty()) {
			boolean heading = location.hasBearing() && location.hasSpeed()
					&& location.getSpeed() >= CAMERA_MIN_HEADING_SPEED_MPS;
			RoutingHelper routingHelper = app.getRoutingHelper();
			boolean onRoute = routingHelper.isRouteCalculated() && routingHelper.isFollowingMode();
			RoadCrewCameras.Line line = null;
			if (onRoute) {
				line = routeLine(routingHelper.getRoute().getRouteLocations());
			} else if (heading && road != null) {
				line = roadLine(road, lat, lon, location.getBearing());
			}
			double bearing = heading ? location.getBearing() : Double.NaN;
			state.ahead = RoadCrewCameras.nextAhead(cameras, RoadCrewCameras.Rule.WARN, lat, lon, line,
					bearing, RoadCrewCameras.SHOW_WITHIN_METERS);
			zoneAhead = RoadCrewCameras.nextAhead(cameras, RoadCrewCameras.Rule.ZONE, lat, lon, line,
					bearing, zoneLength);
		}
		state.zone = cameraZones.update(zoneAhead, zoneLength, lat, lon);
		return state;
	}

	@NonNull
	private static RoadCrewCameras.Line routeLine(@NonNull List<Location> route) {
		return new RoadCrewCameras.Line() {
			@Override
			public int size() {
				return route.size();
			}

			@Override
			public double lat(int index) {
				return route.get(index).getLatitude();
			}

			@Override
			public double lon(int index) {
				return route.get(index).getLongitude();
			}
		};
	}

	@Nullable
	private RoadCrewCameras.Line roadLine(@NonNull RouteDataObject road, double lat, double lon, double bearing) {
		if (road != cameraRoad) {
			int count = road.getPointsLength();
			double[] lats = new double[count];
			double[] lons = new double[count];
			for (int i = 0; i < count; i++) {
				lats[i] = MapUtils.get31LatitudeY(road.getPoint31YTile(i));
				lons[i] = MapUtils.get31LongitudeX(road.getPoint31XTile(i));
			}
			cameraRoadLats = lats;
			cameraRoadLons = lons;
			cameraRoad = road;
		}
		return RoadCrewCameras.roadAhead(cameraRoadLats, cameraRoadLons, lat, lon, bearing);
	}

	/** France: the zone's length by the road the truck is on. */
	private static double cameraZoneLength(@Nullable RouteDataObject road, @NonNull Location location) {
		if (road == null) {
			return RoadCrewCameras.zoneLength(false, 0);
		}
		String highway = road.getHighway();
		boolean motorway = highway != null && highway.startsWith("motorway");
		float speed = road.getMaximumSpeed(road.bearingVsRouteDirection(location));
		int limitKmh = speed > 0 && speed < RouteDataObject.NONE_MAX_SPEED ? Math.round(speed * 3.6f) : 0;
		return RoadCrewCameras.zoneLength(motorway, limitKmh);
	}

	@Nullable
	private RoadCrewWeighStations.Ahead findWeighStationAhead(Location location) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		if (!routingHelper.isRouteCalculated() || !routingHelper.isFollowingMode()) {
			return null;
		}
		List<RoadCrewWeighStations.Station> stations = RoadCrewWeighStationsStore.get(app);
		if (stations.isEmpty() || location == null) {
			return null;
		}
		List<Location> route = routingHelper.getRoute().getRouteLocations();
		return RoadCrewWeighStations.nextOnRoute(stations, location.getLatitude(), location.getLongitude(),
				new RoadCrewWeighStations.Route() {
					@Override
					public int size() {
						return route.size();
					}

					@Override
					public double lat(int index) {
						return route.get(index).getLatitude();
					}

					@Override
					public double lon(int index) {
						return route.get(index).getLongitude();
					}
				});
	}

}
