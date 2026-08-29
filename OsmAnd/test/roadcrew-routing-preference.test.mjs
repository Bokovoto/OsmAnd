import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = path => readFileSync(new URL(`../src/net/osmand/plus/${path}`, import.meta.url), 'utf8');

test('community ranking requires Truck, consent and the current atomic snapshot', () => {
  const store = source('roadcrew/RoadCrewRoutePreferenceStore.kt');
  assert.match(store, /mode\.isDerivedRoutingFrom\(ApplicationMode\.TRUCK\)/);
  assert.match(store, /hasCommunityRoutingAccess\(app\)/);
  assert.match(store, /getShadowSnapshotFile\(app\)/);
  assert.match(store, /System\.currentTimeMillis\(\)/);
  assert.doesNotMatch(store, /\.bak|getShadowSnapshotBackup/);
});

test('routing preferences do not disable the renderer or change the ordinary fast path', () => {
  const provider = source('routing/RouteProvider.java');
  assert.match(provider, /calcGPXRoute \? RoadCrewRoutePreferences\.EMPTY/);
  assert.match(provider, /roadCrewOverlay\.isEmpty\(\) && !communityRanking/);
  assert.match(provider, /cf\.roadCrewPreferences = preferences/);
  const renderer = provider.indexOf('getRenderer().checkInitialized');
  const nativeRouting = provider.indexOf('if (communityRanking) {\n\t\t\tlib = null;');
  assert.ok(renderer >= 0 && nativeRouting > renderer);
});
