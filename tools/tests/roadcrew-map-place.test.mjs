import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 01.10.2026: on the road the truck was hidden under the street name and
// the RoadCrew buttons. Two reasons, both fixed here: nothing told OsmAnd that
// the RoadCrew header and footer cover the map, and without navigation the
// truck was placed low, where navigation wants it.

const source = name => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/${name}`, import.meta.url), 'utf8');

test('where the truck stands - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-map-place-'));
  const code = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapPlacement.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-map-place/MapPlaceTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'MapPlaceTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ map place checks passed/);
  console.log(result.trim());
});

test('the map asks it before falling back to the setting', () => {
  const manager = source('helpers/MapDisplayPositionManager.java');
  assert.match(manager, /RoadCrewMapPlacement\.centreOnTheTruck\(/);
  // Only where the app itself chooses; a provider - the place card, the track
  // menu - still comes first, as it did before.
  assert.match(manager, /getPositionFromPreferences/);
});

test('the RoadCrew header and footer count as covering the map', () => {
  const hud = source('roadcrew/RoadCrewNeonHud.java');
  assert.match(hud, /getCoveredScreenRects/, 'the HUD reports its own bars');
  const info = source('views/layers/MapInfoLayer.java');
  assert.match(info, /RoadCrewNeonHud\.getCoveredScreenRects\(/,
    'the layer that reports the widget panels reports the RoadCrew bars too');
});

test('starting or ending navigation moves the truck to its place', () => {
  const tracker = source('base/MapViewTrackingUtilities.java');
  assert.match(tracker, /wasFollowingMode/);
});
