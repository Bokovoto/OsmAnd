import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 02.10.2026: the stationary cameras are in the phone's maps already
// (OpenStreetMap; 72 in Bulgaria_europe.obf) but nobody sees them - the icon
// only from zoom 15, no warning on the screen, the voice only on a route.
// Approved mockup: a sign, "Стационарна камера · 500 м", one voice warning,
// with or without a route. Germany and Switzerland: off, and the screen says
// why. France: only a danger zone (his choice), never the camera's place.

const roadcrew = new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/', import.meta.url);
const source = name => readFileSync(new URL(name, roadcrew), 'utf8');
const app = path => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/${path}`, import.meta.url), 'utf8');

test('which camera is ahead, and when to say it - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-cameras-'));
  const code = fileURLToPath(new URL('RoadCrewCameras.java', roadcrew));
  const harness = fileURLToPath(new URL('./roadcrew-cameras/CamerasTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.CamerasTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ camera checks passed/);
  console.log(result.trim());
});

test('the cameras come from the maps on the phone, with the country of each', () => {
  const store = source('RoadCrewCamerasSource.java');
  assert.match(store, /getAmenityRepositories\(\)/);
  assert.match(store, /"speed_camera"/);
  assert.match(store, /getRegions\(\)/, 'the country decides the rule');
  assert.match(store, /RoadCrewCameras\.ruleForRegions\(/);
  assert.doesNotMatch(store, /HttpURLConnection|API_BASE_URL/, 'no server: the maps are already on the phone');
});

test('the layer: with a route, on the road without one, and straight ahead as the last guess', () => {
  const layer = source('RoadCrewReportsLayer.java');
  assert.match(layer, /drawCameras\(canvas, tileBox\)/);
  assert.match(layer, /drawCameraWarning\(canvas, tileBox, /);
  assert.match(layer, /RoadCrewCameras\.nextAlong\(/);
  assert.match(layer, /getLastKnownRouteSegment\(\)/, 'without a route: the road the truck is on');
  assert.match(layer, /RoadCrewCameras\.roadAhead\(/);
  assert.match(layer, /RoadCrewCameras\.nextStraightAhead\(/);
  assert.match(layer, /cameraZones\.update\(/, 'France: the zone');
  assert.match(layer, /roadcrew_camera_off_germany/);
  assert.match(layer, /roadcrew_camera_off_switzerland/);
  assert.match(layer, /roadcrew_camera_zone_france/);
  // A French camera is never drawn: the sign would give its place away.
  const draw = layer.slice(layer.indexOf('private void drawCameras('), layer.indexOf('private void drawCameraSign('));
  assert.match(draw, /Rule\.WARN/);
});

test('the voice warning and the texts, in Bulgarian and English', () => {
  const voice = source('RoadCrewVoiceAlerts.java');
  assert.match(voice, /void checkCamera\(/);
  assert.match(voice, /"Стационарна камера след "/);
  assert.match(voice, /void checkCameraZone\(/);
  assert.match(voice, /"Опасна зона\."/);
  const bg = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values-bg/roadcrew_strings.xml', import.meta.url), 'utf8');
  const en = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values/roadcrew_strings.xml', import.meta.url), 'utf8');
  for (const name of ['roadcrew_camera_fixed_title', 'roadcrew_camera_fixed_ahead', 'roadcrew_camera_fixed_ahead_limit',
    'roadcrew_camera_fixed_limit', 'roadcrew_camera_fixed_source', 'roadcrew_camera_zone_ahead',
    'roadcrew_camera_off_germany', 'roadcrew_camera_off_switzerland', 'roadcrew_camera_zone_france']) {
    assert.ok(bg.includes(`name="${name}"`), `bg ${name}`);
    assert.ok(en.includes(`name="${name}"`), `en ${name}`);
  }
  assert.ok(bg.includes('>Стационарна камера · %1$s<'));
  assert.ok(bg.includes('Германия: предупреждението за камери е забранено по закон'));
});

test("OsmAnd's own camera warnings are off in RoadCrew: one warning, and none where it is forbidden", () => {
  const settings = app('settings/backend/OsmandSettings.java');
  assert.match(settings, /SPEAK_SPEED_CAMERA\.setModeDefaultValue\(ApplicationMode\.TRUCK, false\);/);
  const waypoints = app('helpers/WaypointHelper.java');
  assert.match(waypoints, /RoadCrewCameras\.replacesBuiltInAlarms\(app\.getPackageName\(\)\)/);
  // The map style is copied from OsmAnd's resources at build time: the copy
  // leaves out the small camera icon, which would show German cameras too.
  const build = readFileSync(new URL('../../OsmAnd-java/build.gradle', import.meta.url), 'utf8');
  const styles = build.slice(build.indexOf("tasks.register('collectRenderingStylesResources'"));
  assert.match(styles.slice(0, styles.indexOf('\n}')), /filter \{ String line -> line\.contains\('value="speed_camera" icon='\) \? null : line \}/);
  const widget = app('views/mapwidgets/widgets/AlarmWidget.java');
  assert.match(widget, /SHOW_CAMERAS\.get\(\) && !RoadCrewCameras\.replacesBuiltInAlarms\(app\.getPackageName\(\)\)/);
});
