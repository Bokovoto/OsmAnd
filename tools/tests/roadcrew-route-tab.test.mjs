import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; real fragment lifecycle still needs a device test.
test('neon Route tab delegates to the standard state-aware navigation action', () => {
  const source = readFileSync(new URL(
    '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewNeonHud.java', import.meta.url), 'utf8');
  const start = source.indexOf('R.string.roadcrew_neon_nav_route, v -> {');
  assert.notEqual(start, -1, 'Route tab callback must exist');
  const end = source.indexOf('});', start);
  assert.notEqual(end, -1, 'Route tab callback must terminate');
  const callback = source.slice(start, end);
  assert.match(callback, /activity\.getMapActions\(\)\.doRoute\(\);/);
  assert.doesNotMatch(callback, /enterRoutePlanningMode|recalculateRoute/);
});
