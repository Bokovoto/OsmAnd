import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 29.09.2026: stationary weigh stations (WIM) as a layer of their own -
// always shown, never expiring, no voting; "Стационарен кантар · 500 м" in
// navigation and a voice warning when a station is on the route.

const roadcrew = new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/', import.meta.url);
const source = name => readFileSync(new URL(name, roadcrew), 'utf8');

test('which station the route reaches next, and when to say it - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-weigh-stations-'));
  const code = fileURLToPath(new URL('RoadCrewWeighStations.java', roadcrew));
  const harness = fileURLToPath(new URL('./roadcrew-weigh-stations/WeighStationsTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.WeighStationsTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ weigh station checks passed/);
  console.log(result.trim());
});

test('the list comes from the server and is kept for offline use', () => {
  const store = source('RoadCrewWeighStationsStore.java');
  assert.match(store, /"\/v1\/weigh-stations"/);
  assert.match(store, /RoadCrewEndpoints\.API_BASE_URL/);
  // A failed refresh keeps the last list; nothing is ever removed on an error.
  assert.match(store, /getSharedPreferences\(/);
});

test('the layer draws them always, with no switch, and warns only on the route', () => {
  const layer = source('RoadCrewReportsLayer.java');
  assert.match(layer, /drawWeighStations\(canvas, tileBox\)/);
  assert.match(layer, /drawWeighStationWarning\(canvas, tileBox, stationAhead\)/);
  assert.match(source('RoadCrewRoadAlerts.java'), /isFollowingMode\(\)/);
  assert.equal(/weigh_station.*(enabled|visible|toggle)/i.test(source('RoadCrewSettings.java')), false,
    'no setting to hide them (Galin: always shown)');
  // Not a report: no votes, no proximity prompt, no push.
  const reports = source('RoadCrewReportsRepository.java');
  assert.equal(reports.includes('WeighStation'), false);
});

test('the voice warning and the texts, in Bulgarian and English', () => {
  const voice = source('RoadCrewVoiceAlerts.java');
  assert.match(voice, /void checkWeighStation\(/);
  assert.match(voice, /"Стационарен кантар след "/);
  const bg = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values-bg/roadcrew_strings.xml', import.meta.url), 'utf8');
  const en = readFileSync(new URL('../../OsmAnd/src/nightlyFree/res/values/roadcrew_strings.xml', import.meta.url), 'utf8');
  for (const name of ['roadcrew_weigh_station_fixed_title', 'roadcrew_weigh_station_fixed_ahead',
    'roadcrew_weigh_station_fixed_place', 'roadcrew_weigh_station_fixed_about', 'roadcrew_weigh_station_fixed_source']) {
    assert.ok(bg.includes(`name="${name}"`), `bg ${name}`);
    assert.ok(en.includes(`name="${name}"`), `en ${name}`);
  }
  assert.ok(bg.includes('>Стационарен кантар · %1$s<'));
  assert.ok(bg.includes('Национално тол управление (BG TOLL)'));
});
