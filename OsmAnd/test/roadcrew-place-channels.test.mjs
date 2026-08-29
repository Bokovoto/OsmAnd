import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const api = read('../src/net/osmand/plus/roadcrew/RoadCrewPlacesApi.java');
const controller = read('../src/net/osmand/plus/roadcrew/RoadCrewPlacesController.java');
const layer = read('../src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java');
const menu = read('../src/net/osmand/plus/roadcrew/RoadCrewReportButton.java');
const bgStrings = read('../src/nightlyFree/res/values-bg/roadcrew_strings.xml');
const enStrings = read('../src/nightlyFree/res/values/roadcrew_strings.xml');

test('place channels are reachable from both menus and the map layer', () => {
  assert.equal((menu.match(/RoadCrewReportsLayer::showPlaceChannels/g) || []).length, 2);
  assert.match(layer, /placesController\.draw\(canvas, tileBox\)/);
  assert.match(layer, /placesController\.findTapped\(point, tileBox\)/);
  assert.match(layer, /placesController\.showPlace\(place\)/);
});

test('existing offline parking POIs become idempotent OSM-backed channels', () => {
  assert.match(controller, /PoiUIFilter\.STD_PREFIX \+ "parking"/);
  assert.match(controller, /createPlace\(app, "PARKING", name, amenity\.getLocation\(\),\s*sourceId\.isEmpty\(\) \? "ROADCREW" : "OSM", sourceId/);
  assert.match(api, /post\("\/v1\/places", deviceId\(app\), body\)/);
  assert.match(api, /body\.put\("sourceType", sourceType\)/);
  assert.match(api, /body\.put\("sourceId", sourceId\)/);
});

test('place information, parking reviews and confirmation votes use authenticated endpoints', () => {
  assert.match(api, /"\/v1\/places\/" \+ encode\(placeId\) \+ "\/messages"/);
  assert.match(api, /"\/v1\/places\/" \+ encode\(placeId\) \+ "\/reviews"/);
  assert.match(api, /"\/v1\/place-messages\/" \+ encode\(messageId\) \+ "\/votes"/);
  assert.match(api, /setRequestProperty\("X-RoadCrew-Device-Id", deviceId\)/);
  assert.match(controller, /location\.hasSpeed\(\) && location\.getSpeed\(\) > MOVING_SPEED_LIMIT_METERS_PER_SECOND/);
});

test('Bulgarian and English resources cover queues, parking ratings and verified visits', () => {
  for (const strings of [bgStrings, enStrings]) {
    for (const name of [
      'roadcrew_places_title',
      'roadcrew_place_category_queue',
      'roadcrew_place_category_parking',
      'roadcrew_place_verified_visit',
      'roadcrew_place_rate_parking',
      'roadcrew_place_score_security',
      'roadcrew_place_theft_checkbox',
      'roadcrew_place_stop_to_post'
    ]) {
      assert.match(strings, new RegExp(`name="${name}"`));
    }
  }
});
