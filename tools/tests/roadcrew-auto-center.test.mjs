import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 01.10.2026, from a driver: without navigation the map did not come
// back to the truck after zooming. Option 2: it does, as in navigation, while
// the truck moves - after the driver's own "auto-centre" seconds.

const source = name => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/${name}`, import.meta.url), 'utf8');

test('when the map comes back to the truck - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-auto-center-'));
  const code = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewAutoCenter.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-auto-center/AutoCenterTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'AutoCenterTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ auto-centre checks passed/);
  console.log(result.trim());
});

test('the map tracker uses it, both where the timer is set and where it fires', () => {
  const tracker = source('base/MapViewTrackingUtilities.java');
  assert.equal((tracker.match(/RoadCrewAutoCenter\.armed\(/g) ?? []).length, 2, 'setMapLinkedToLocation and resetBackToLocation');
  assert.match(tracker, /RoadCrewAutoCenter\.returnNow\(/);
  assert.match(tracker, /R\.string\.roadcrew_map_back_to_truck/);
  // No new setting and no hidden one: AUTO_FOLLOW_ROUTE is the driver's own.
  assert.match(tracker, /settings\.AUTO_FOLLOW_ROUTE\.get\(\)/);
});

test('the message, in Bulgarian and English', () => {
  const bg = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values-bg/roadcrew_strings.xml', import.meta.url), 'utf8');
  const en = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values/roadcrew_strings.xml', import.meta.url), 'utf8');
  assert.ok(bg.includes('<string name="roadcrew_map_back_to_truck">Картата се върна към камиона</string>'));
  assert.ok(en.includes('name="roadcrew_map_back_to_truck"'));
});
