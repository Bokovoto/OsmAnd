import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract checks only. Galin, 21.09, after a real drive: the panel with
// the confirm buttons vanished while he was moving the map, the course stayed
// drawn with no buttons anywhere, and the drive could not be confirmed until he
// force-closed the app. Separately, the course was shown whole and then lost as
// the map turned north.
//
// These guard both intents. The real behaviour still needs a device test.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const CONTROLLER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewValidationController.java';
const LAYER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java';

test('a panel the driver is reading is not taken away by a route calculation', () => {
  const source = read(CONTROLLER);
  const start = source.indexOf('private boolean mustCloseOpenPanel()');
  assert.notEqual(start, -1,
    'closing an open panel must be its own decision, not the offering gate');
  const body = source.slice(start, source.indexOf('private boolean updateSafety()', start));
  assert.doesNotMatch(body, /isRouteBeingCalculated/,
    'moving the map can start a calculation; that must not withdraw the buttons');
  assert.doesNotMatch(body, /hasNavigationSession|isPauseNavigation/,
    'a paused or pending session is not a reason to take the confirmation away');
  // What genuinely rules a panel out.
  assert.match(body, /isFollowingMode/, 'navigation actually starting does close it');
  assert.match(body, /isFinishing\(\)|isDestroyed\(\)/, 'so does the map going away');
  assert.match(body, /RoadCrewMapObservationConsent\.isEnabled/, 'and consent withdrawn');
  assert.match(source, /if \(isShowing\(\) && mustCloseOpenPanel\(\)\) \{ dialog\.dismiss\(\); \}/,
    'the tick must use that decision, not the offering gate');
});

test('a drive is never left drawn with no way to answer it', () => {
  assert.match(read(LAYER), /static boolean hasTripReviewJourney\(\)/,
    'the layer must be able to say whether it is drawing a drive');
  const source = read(CONTROLLER);
  const tick = source.slice(source.indexOf('private void tick()'),
    source.indexOf('private boolean mustCloseOpenPanel()'));
  assert.match(tick, /!isShowing\(\) && RoadCrewReportsLayer\.hasTripReviewJourney\(\)/,
    'a drawing without a panel must be noticed');
  assert.match(tick, /RoadCrewReportsLayer\.setTripReviewJourney\(null\)/,
    'and cleared, so the map is not left with a course that has no buttons');
});

test('north first, then the whole course in the window', () => {
  const source = read(CONTROLLER);
  const north = source.indexOf('private void faceNorth(MapActivity activity)');
  assert.notEqual(north, -1, 'facing north is its own step now');
  const northBody = source.slice(north, source.indexOf('private void fitMapToTrip(', north));
  assert.match(northBody, /setRotate\(0, false\)/,
    'not forced: an already-north map must not start an animation that spoils the fit');
  const fit = source.slice(source.indexOf('private void fitMapToTrip('),
    source.indexOf('private void show(MapActivity activity'));
  assert.doesNotMatch(fit, /setRotate/,
    'the fit must not turn the map; the second pass used to start the rotation over');
  assert.match(fit, /fitRectToMap\(/);
  // Order and timing: the rotation is animated, so the fit waits for it.
  const schedule = source.slice(source.indexOf('if (tripBounds != null) {'),
    source.indexOf('if (tripBounds != null) {') + 900);
  const northAt = schedule.indexOf('faceNorth');
  const fitAt = schedule.indexOf('fitMapToTrip');
  assert.ok(northAt !== -1 && fitAt !== -1 && northAt < fitAt,
    'north is asked for before any fit');
  const delays = [...schedule.matchAll(/postDelayed\(.*?, (\d+)\);/g)].map(m => Number(m[1]));
  assert.ok(delays.length >= 2 && Math.min(...delays) >= 500,
    `the first fit must come after the turn, found delays ${delays.join(', ')}`);
});
