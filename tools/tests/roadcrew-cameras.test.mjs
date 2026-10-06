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
// with or without a route. France: only a danger zone (his choice), never the
// camera's place. Then, the same day: the cameras on by default everywhere -
// Germany and Switzerland too - "ако шофьора прецени че трябва да спазва
// закона да си ги изключва": the two switches he already has turn them off,
// and on entering those countries the screen says the law and where they are.

const roadcrew = new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/', import.meta.url);
// Line ends as the checkout made them; the checks below look for "\n".
const lf = text => text.replace(/\r\n/g, '\n');
const source = name => lf(readFileSync(new URL(name, roadcrew), 'utf8'));
const app = path => lf(readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/${path}`, import.meta.url), 'utf8'));
const between = (text, start, end) => text.slice(text.indexOf(start), text.indexOf(end, text.indexOf(start) + 1));

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
  // Read before the regions are loaded, a French camera would get no rule.
  assert.match(between(store, 'private static List<RoadCrewCameras.Camera> read(', '\n\t}\n'), /isInitialized\(\)/);
  assert.doesNotMatch(store, /HttpURLConnection|API_BASE_URL/, 'no server: the maps are already on the phone');
});

test('the layer: with a route, on the road without one, and straight ahead as the last guess', () => {
  const layer = source('RoadCrewReportsLayer.java');
  const controller = source('RoadCrewRoadAlerts.java');
  assert.match(layer, /drawCameras\(canvas, tileBox\)/);
  assert.match(layer, /drawCameraWarning\(canvas, tileBox, /);
  assert.match(controller, /RoadCrewCameras\.nextAhead\(/);
  assert.match(controller, /getLastKnownRouteSegment\(location\)/, 'uses the current service fix, not the hidden map location');
  assert.match(controller, /RoadCrewCameras\.roadAhead\(/);
  assert.match(source('RoadCrewCameras.java'), /knownLine != null[^]*?return nextAlong/);
  assert.match(controller, /cameraZones\.update\(/, 'France: the zone');
  assert.match(layer, /roadcrew_camera_law_germany/);
  assert.match(layer, /roadcrew_camera_law_switzerland/);
  assert.match(layer, /roadcrew_camera_zone_france/);
  // A French camera is never drawn: the sign would give its place away.
  assert.match(between(layer, 'private void drawCameras(', 'private void drawCameraSign('), /Rule\.WARN/);
});

test("the driver's switches: the screen one hides the signs and the warning, the voice one the voice", () => {
  const layer = source('RoadCrewReportsLayer.java');
  const onScreen = between(layer, 'private boolean camerasOnScreen(', '\n\t}\n');
  assert.match(onScreen, /SHOW_ROUTING_ALARMS\.get\(\)/, 'the screen alerts as a whole');
  assert.match(onScreen, /SHOW_CAMERAS\.get\(\)/, 'and its cameras switch');
  assert.match(between(layer, 'private void drawCameras(', 'private void drawCameraSign('), /camerasOnScreen\(\)/);
  assert.match(between(layer, 'private void drawCameraWarning(', 'private void drawCameraNotice('), /camerasOnScreen\(\)/);
  assert.match(between(layer, 'private RoadCrewCameras.Camera findTappedCamera(', '\n\t}\n'), /camerasOnScreen\(\)/);
  const voice = source('RoadCrewVoiceAlerts.java');
  assert.match(between(voice, 'void checkCamera(', '\n\t}\n'), /SPEAK_SPEED_CAMERA\.get\(\)/);
  assert.match(between(voice, 'void checkCameraZone(', '\n\t}\n'), /SPEAK_SPEED_CAMERA\.get\(\)/);
});

test('both switches on by default, in the truck and the car profile', () => {
  const settings = app('settings/backend/OsmandSettings.java');
  assert.match(settings, /public final CommonPreference<Boolean> SHOW_CAMERAS = new BooleanPreference\(this, "show_cameras", false\)/,
    'OsmAnd itself keeps them off');
  const block = between(settings, '// Galin, 02.10.2026: the cameras on by default', '\n\t\t}\n');
  for (const [name, mode] of [['SHOW_CAMERAS', 'TRUCK'], ['SHOW_CAMERAS', 'CAR'], ['SPEAK_SPEED_CAMERA', 'CAR']]) {
    assert.match(block, new RegExp(`${name}\\.setModeDefaultValue\\(ApplicationMode\\.${mode}, true\\);`), `${name} ${mode}`);
  }
  assert.doesNotMatch(block, /\.set\(|\.setModeValue\(/, 'a default only: a switch the driver turned off stays off');
  // SPEAK_SPEED_CAMERA for the truck: on in the truck voice block already.
  assert.match(settings, /SPEAK_SPEED_CAMERA\.setModeDefaultValue\(ApplicationMode\.TRUCK, true\);/);
});

test('the texts, in Bulgarian and English', () => {
  const voice = source('RoadCrewVoiceAlerts.java');
  // No accent mark: the voice drops a word that carries one - Galin heard
  // only "наблизо" (06.10.2026); measured in roadcrew-voice-marks.test.mjs.
  assert.match(voice, /"Камера наблизо\."/);
  assert.match(voice, /cameraVoice\.toSpeak\(/, 'two warnings: at 1 km and at 500 m');
  assert.match(voice, /return "камера";/, 'the same word as the report');
  assert.doesNotMatch(voice, /"Стационарна камера след "/, 'POI coordinates do not establish controlled carriageway');
  assert.match(voice, /"Опасна зона\."/);
  const bg = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values-bg/roadcrew_strings.xml', import.meta.url), 'utf8');
  const en = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values/roadcrew_strings.xml', import.meta.url), 'utf8');
  for (const name of ['roadcrew_camera_fixed_title', 'roadcrew_camera_fixed_ahead', 'roadcrew_camera_fixed_ahead_limit',
    'roadcrew_camera_fixed_limit', 'roadcrew_camera_fixed_source', 'roadcrew_camera_zone_ahead',
    'roadcrew_camera_law_germany', 'roadcrew_camera_law_switzerland', 'roadcrew_camera_zone_france']) {
    assert.ok(bg.includes(`name="${name}"`), `bg ${name}`);
    assert.ok(en.includes(`name="${name}"`), `en ${name}`);
  }
  assert.ok(!bg.includes('roadcrew_camera_off_'), 'nothing says they are switched off for him any more');
  assert.ok(bg.includes('>Камера наблизо · %1$s<'));
  // Where the two switches are, by the names the settings show.
  const strings = readFileSync(new URL('../../OsmAnd/res/values-bg/strings.xml', import.meta.url), 'utf8');
  const name = key => strings.match(new RegExp(`name="${key}">([^<]+)<`))[1];
  const germany = bg.match(/name="roadcrew_camera_law_germany">([^<]+)</)[1];
  for (const key of ['voice_announces', 'screen_alerts', 'speak_cameras']) {
    assert.ok(germany.includes(name(key)), `the notice names "${name(key)}"`);
  }
});

test("OsmAnd's own camera warnings stay off in RoadCrew: one warning, never two", () => {
  const waypoints = app('helpers/WaypointHelper.java');
  assert.match(waypoints, /RoadCrewCameras\.replacesBuiltInAlarms\(app\.getPackageName\(\)\)/);
  // The map style is copied from OsmAnd's resources at build time: the copy
  // leaves out OsmAnd's small camera icon, next to RoadCrew's sign.
  const build = readFileSync(new URL('../../OsmAnd-java/build.gradle', import.meta.url), 'utf8');
  const styles = build.slice(build.indexOf("tasks.register('collectRenderingStylesResources'"));
  assert.match(styles.slice(0, styles.indexOf('\n}')), /filter \{ String line -> line\.contains\('value="speed_camera" icon='\) \? null : line \}/);
  const widget = app('views/mapwidgets/widgets/AlarmWidget.java');
  assert.match(widget, /SHOW_CAMERAS\.get\(\) && !RoadCrewCameras\.replacesBuiltInAlarms\(app\.getPackageName\(\)\)/);
});
