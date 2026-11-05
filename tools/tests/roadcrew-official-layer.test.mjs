import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const code = name => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/roadcrew/${name}`, import.meta.url), 'utf8');

test('official data transport reads our versioned public layer without credentials or upstream requests', () => {
  const store = code('RoadCrewOfficialStore.kt');
  assert.match(store, /RoadCrewEndpoints.API_BASE_URL \+ "\/map-data\/official\/"/);
  assert.doesNotMatch(store, /traffic\.api\.bg|Authorization|deviceId|installationSecret/);
  assert.match(store, /instanceFollowRedirects = false/);
  assert.match(store, /out.size\(\) \+ count <= maxBytes/);
});
test('cache and network are off the drawing thread, with one bounded worker and last-good preservation', () => {
  const store = code('RoadCrewOfficialStore.kt');
  assert.match(store, /newSingleThreadExecutor/); assert.match(store, /busy.compareAndSet\(false, true\)/);
  assert.match(store, /AtomicFile/); assert.match(store, /disk.failWrite/);
  assert.match(store, /Manifest moved backwards/);
  assert.match(store, /nextManifest = SystemClock.elapsedRealtime\(\) \+ 5 \* 60_000/);
  assert.match(store, /connection\?\.disconnect\(\); executor.shutdownNow\(\)/);
});
test('reports retain tap priority; lifecycle closes the official worker', () => {
  const layer = code('RoadCrewReportsLayer.java');
  assert.match(layer, /officialController.draw\(canvas, tileBox\)/);
  assert.match(layer, /report == null && station == null && camera == null && officialController != null/);
  assert.match(layer, /officialController.close\(\); officialController = null/);
});
test('display expires offline, exposes source date and staleness, and never changes routing', () => {
  const controller = code('RoadCrewOfficialController.kt');
  assert.match(controller, /!f.active\(now\)/); assert.match(controller, /f.stale\(now\) \|\| store.failed/);
  assert.match(controller, /roadcrew_official_source_time/); assert.match(controller, /f.descriptions.joinToString/);
  assert.doesNotMatch(controller, /RoutingHelper|calculateRoute|recalculateRoute|BinaryRoutePlanner/);
});
test('explicit global visibility switch controls both drawing and tapping', () => {
  assert.equal((code('RoadCrewOfficialController.kt').match(/!RoadCrewSettings.showOfficial\(app\).get\(\)/g) ?? []).length, 2);
  assert.match(code('RoadCrewSettings.java'), /registerBooleanPreference\("roadcrew_show_official_ria", true\)[\s\S]*?makeGlobal\(\)/);
  assert.match(code('RoadCrewDriverProfileDialog.java'), /showOfficial\(app\).set\(showOfficial.isChecked\(\)\)/);
});
