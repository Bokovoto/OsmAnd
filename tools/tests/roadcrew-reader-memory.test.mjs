import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = name => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/roadcrew/${name}.java`, import.meta.url), 'utf8');

test('restrictions filter shallow bounds before opening a private mutable reader', () => {
  const source = read('RoadCrewTruckRestrictionsProvider');
  assert.ok(source.indexOf('containsRouteDataInBounds(shallow, left, top, right, bottom)') < source.indexOf('resource.getReader('));
  assert.match(source, /ROADCREW_TRUCK_RESTRICTIONS, false/);
  assert.match(source, /buildSearchRouteRequest\(\s*left, right, top, bottom, null\)/);
});

test('observation uses the exact load bounds and map-version lookup opens no reader', () => {
  const source = read('RoadCrewMapObservationCoordinator');
  const version = source.slice(source.indexOf('private String currentMapVersion()'), source.indexOf('private boolean isCollectionContextActive()'));
  assert.doesNotMatch(version, /getReader\(|getRoutingReaders\(/);
  assert.match(source, /getRoutingReaders\(sample.latitude, sample.longitude, LOAD_RADIUS_METERS\)/);
  assert.match(source, /getRoutingReaders\(latitude, longitude, radiusMeters\)/);
  const readers = source.slice(source.indexOf('private BinaryMapIndexReader[] getRoutingReaders('));
  assert.ok(readers.indexOf('containsRouteDataNear(') < readers.indexOf('resource.getReader('));
  assert.match(readers, /ROADCREW_MAP_OBSERVATION, false/);
});
