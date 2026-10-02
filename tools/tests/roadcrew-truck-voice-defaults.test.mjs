import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL(
  '../../OsmAnd/src/net/osmand/plus/settings/backend/OsmandSettings.java', import.meta.url), 'utf8');

// Source contracts only: no Android runtime or device behavior is simulated.
test('RoadCrew truck overrides only the three differing voice defaults', () => {
  const block = source.match(/if \(ROADCREW_BUILD\) \{\s*\/\/ Truck voice defaults[^\n]*\n([\s\S]*?)\n\t\t\}/);
  assert.ok(block, 'RoadCrew-only truck voice default block must exist');
  const calls = [...block[1].matchAll(/(\w+)\.setModeDefaultValue\(ApplicationMode\.(\w+), (true|false)\);/g)]
    .map((m) => [m[1], m[2], m[3]]);
  // Cameras: off since 02.10.2026 - RoadCrew speaks them itself, by each
  // country's law (roadcrew-cameras.test.mjs); OsmAnd's voice would also
  // speak them in Germany, where the warning is forbidden.
  assert.deepEqual(calls, [
    ['SPEAK_STREET_NAMES', 'TRUCK', 'false'],
    ['SPEAK_PEDESTRIAN', 'TRUCK', 'false'],
    ['SPEAK_SPEED_CAMERA', 'TRUCK', 'false'],
  ]);
  assert.doesNotMatch(block[1], /\.set\(|\.setModeValue\(|\.reset\w*\(|\.edit\(/,
    'Defaults must not overwrite explicit saved preferences');
  assert.doesNotMatch(block[1], /SPEED_CAMERAS_UNINSTALLED|VOICE_MUTE|VOICE_PROVIDER/);
});

test('Remaining screenshot settings and non-truck defaults are preserved', () => {
  for (const [name, key, value] of [
    ['TURN_BY_TURN_DIRECTIONS', 'turn_by_turn_directions', true],
    ['SPEAK_EXIT_NUMBER_NAMES', 'exit_number_names', true],
    ['SPEAK_TRAFFIC_WARNINGS', 'speak_traffic_warnings', true],
    ['SPEAK_TUNNELS', 'speak_tunnels', false],
    ['SPEAK_STREET_NAMES', 'speak_street_names', true],
    ['SPEAK_SPEED_CAMERA', 'speak_cameras', false],
  ]) {
    assert.ok(source.includes(`${name} = new BooleanPreference(this, "${key}", ${value})`), name);
  }
  assert.ok(source.includes('SPEAK_PEDESTRIAN.setModeDefaultValue(ApplicationMode.CAR, true);'));
});
