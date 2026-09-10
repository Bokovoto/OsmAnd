import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; this does not simulate Android navigation.
test('Details Start starts road navigation, while transport Show only dismisses', () => {
  const source = readFileSync(new URL(
    '../../OsmAnd/src/net/osmand/plus/routepreparationmenu/ChooseRouteFragment.java', import.meta.url), 'utf8');
  const start = source.indexOf('public void onNavigationRequested() {');
  assert.notEqual(start, -1, 'Navigation callback must exist');
  const end = source.indexOf('\n\t@Override', start);
  assert.notEqual(end, -1, 'Following method boundary must exist');
  const callback = source.slice(start, end);
  assert.match(callback, /dismiss\(false\);\s*if \(!app\.getRoutingHelper\(\)\.isPublicTransportMode\(\)\) \{\s*mapActivity\.getMapActions\(\)\.startNavigation\(\);[^}]*\}/);
});
