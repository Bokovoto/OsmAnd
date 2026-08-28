import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

const read = path => readFileSync(new URL(`../src/net/osmand/plus/${path}`, import.meta.url), 'utf8');
const actions = read('views/MapActions.java');
const tracking = read('base/MapViewTrackingUtilities.java');
const hud = read('roadcrew/RoadCrewNeonHud.java');

function method(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, signature);
  const open = source.indexOf('{', start);
  let depth = 1;
  let end = open + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  assert.equal(depth, 0, signature);
  return source.slice(start, end).replaceAll('@NonNull ', '');
}

test('Start and Continue prepare orientation before following, not on failed Start', () => {
  const start = method(actions, 'public void startNavigation()');
  assert.match(start, /isFollowingMode\(\)\)\s*\{\s*prepareNavigationMap\(\);\s*switchToRouteFollowingLayout\(\);/);
  assert.match(start, /logEvent\("start_navigation"\);\s*prepareNavigationMap\(\);\s*mapTrackingUtilities.backToLocationImpl/);
  assert.equal(start.match(/prepareNavigationMap\(\)/g)?.length, 2);
  const failedStart = start.slice(start.indexOf('if (!targetHelper.checkPointToNavigateShort())'), start.indexOf('app.logEvent'));
  assert.doesNotMatch(failedStart, /prepareNavigationMap/);
  assert.ok(start.indexOf('settings.setApplicationMode') < start.indexOf('prepareNavigationMap'));
  assert.doesNotMatch(method(tracking, 'public void updateLocation(Location location)'), /setCompassMode|prepareNavigationMap/);
  assert.doesNotMatch(read('roadcrew/RoadCrewRoutePreview.kt'), /setCompassMode/);
  assert.match(read('roadcrew/RoadCrewRoutePreview.kt'), /!routing.isRoutePlanningMode/);
});

test('preview and navigation keep separate orientation state', () => {
  // Execute only the small production transition methods with state stubs.
  // This is not an Android compilation or a renderer/device test.
  const probe = `
enum CompassMode { NORTH_IS_UP, MOVEMENT_DIRECTION, COMPASS_DIRECTION, MANUALLY_ROTATED }
enum ApplicationMode {
  CAR, TRUCK, CUSTOM_TRUCK, PEDESTRIAN;
  boolean isDerivedRoutingFrom(ApplicationMode mode) {
    return this == mode || (this == TRUCK && mode == CAR) || (this == CUSTOM_TRUCK && mode == TRUCK);
  }
}
class Settings {
  ApplicationMode active = ApplicationMode.TRUCK;
  java.util.Map<ApplicationMode, CompassMode> modes = new java.util.EnumMap<>(ApplicationMode.class);
  CompassMode getCompassMode() { return modes.getOrDefault(active, CompassMode.NORTH_IS_UP); }
  void setCompassMode(CompassMode mode) { setCompassMode(mode, active); }
  void setCompassMode(CompassMode mode, ApplicationMode profile) { modes.put(profile, mode); }
  float getLastKnownMapElevation() { return 55f; }
}
class Routing {
  ApplicationMode mode = ApplicationMode.TRUCK;
  boolean planning = true, following;
  ApplicationMode getAppMode() { return mode; }
  boolean isRoutePlanningMode() { return planning; }
  boolean isFollowingMode() { return following; }
}
class Animation {
  float tilt;
  void startTilting(float angle, float duration) { tilt = angle; }
}
class OsmandMapTileView {
  boolean carView;
  float rotation = 45;
  Animation animation = new Animation();
  boolean isCarView() { return carView; }
  Animation getAnimatedDraggingThread() { return animation; }
  void setRotate(float value, boolean force) { rotation = value; }
}
class MapContainer {
  OsmandMapTileView view = new OsmandMapTileView();
  OsmandMapTileView getMapView() { return view; }
}
class App {
  Settings settings = new Settings();
  MapContainer map = new MapContainer();
  Settings getSettings() { return settings; }
  MapContainer getOsmandMap() { return map; }
}
class MapActivity {
  App app = new App();
  Routing routing = new Routing();
  App getApp() { return app; }
  Routing getRoutingHelper() { return routing; }
  OsmandMapTileView getMapView() { return app.map.view; }
  void refreshMap() {}
}
class NavigationProbe {
  App app;
  Settings settings;
  Routing routingHelper;
  NavigationProbe(MapActivity activity) { app = activity.app; settings = app.settings; routingHelper = activity.routing; }
  ${method(actions, 'private void prepareNavigationMap()')}
  void start() { prepareNavigationMap(); }
}
class TrackingProbe {
  Routing routingHelper;
  boolean routePlanningMode, followingMode, linked = true;
  int recenterCount;
  TrackingProbe(Routing routing) { routingHelper = routing; }
  void updateSettings() {}
  void backToLocationImpl() { linked = true; recenterCount++; }
  void setMapLinkedToLocation(boolean value) { linked = value; }
  ${method(tracking, 'public void switchRoutePlanningMode()')}
  ${method(tracking, 'public void prepareRoutePreview()')}
}
class HudProbe {
  ${method(hud, 'private static void resetMapNorth(')}
  static void north(MapActivity activity) { resetMapNorth(activity); }
}
class Checks {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void run() {
    for (ApplicationMode profile : new ApplicationMode[]{ApplicationMode.CAR, ApplicationMode.TRUCK, ApplicationMode.CUSTOM_TRUCK}) {
      for (CompassMode previous : CompassMode.values()) {
        MapActivity activity = new MapActivity();
        activity.routing.mode = profile;
        activity.app.settings.active = profile;
        activity.app.settings.setCompassMode(previous);
        HudProbe.north(activity);
        check(activity.getMapView().rotation == 0, "preview must be north-up");
        check(activity.app.settings.getCompassMode() == previous, "preview changed driving preference");
        TrackingProbe tracking = new TrackingProbe(activity.routing);
        tracking.prepareRoutePreview();
        check(!tracking.linked, "preview must detach GPS following");
        new NavigationProbe(activity).start();
        check(activity.app.settings.getCompassMode() == CompassMode.MOVEMENT_DIRECTION, "Start must use movement");
        check(activity.getMapView().animation.tilt == 55, "saved tilt lost");
        activity.routing.planning = false;
        activity.routing.following = true;
        tracking.switchRoutePlanningMode();
        check(tracking.linked && tracking.followingMode && !tracking.routePlanningMode, "Start failed before next GPS tick");
        HudProbe.north(activity);
        check(activity.app.settings.getCompassMode() == CompassMode.NORTH_IS_UP, "manual compass choice in navigation lost");
        activity.routing.planning = true;
        tracking.prepareRoutePreview();
        new NavigationProbe(activity).start();
        activity.routing.planning = false;
        tracking.switchRoutePlanningMode();
        check(tracking.recenterCount == 2 && activity.app.settings.getCompassMode() == CompassMode.MOVEMENT_DIRECTION, "Continue failed");
        activity.routing.following = false;
        tracking.switchRoutePlanningMode();
        check(!tracking.followingMode && tracking.recenterCount == 2, "cancel used stale following flag");
      }
    }
    MapActivity other = new MapActivity();
    other.routing.mode = ApplicationMode.PEDESTRIAN;
    other.app.settings.active = ApplicationMode.PEDESTRIAN;
    new NavigationProbe(other).start();
    check(other.app.settings.getCompassMode() == CompassMode.NORTH_IS_UP, "unrelated profile changed");
    other.routing.mode = ApplicationMode.TRUCK;
    other.app.settings.active = ApplicationMode.TRUCK;
    other.getMapView().carView = true;
    new NavigationProbe(other).start();
    check(other.app.settings.getCompassMode() == CompassMode.NORTH_IS_UP, "Android Auto policy changed");
  }
}
try { Checks.run(); System.out.println("NAVIGATION_ORIENTATION_OK"); } catch (Throwable error) { System.out.println("NAVIGATION_ORIENTATION_FAILED: " + error); }
/exit
`;
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'],
    { input: probe, encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  const output = `${result.stdout}\n${result.stderr}`;
  assert.equal(result.status, 0, output);
  assert.match(output, /NAVIGATION_ORIENTATION_OK/, output);
  assert.doesNotMatch(output, /NAVIGATION_ORIENTATION_FAILED|Error:/, output);
});
