import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract checks only. Galin, 19.09: the trip review showed the drive on
// the dialog's own blank canvas, with almost nothing around it; he asked for the
// drive over the maps he has downloaded. These guard that intent - the real look
// still needs a device test, which has not been possible while the phone is busy.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const LAYER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java';
const CONTROLLER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewValidationController.java';
const REVIEW = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripReview.kt';

test('the reviewed drive is drawn by the map layer, on the map itself', () => {
  const source = read(LAYER);
  assert.match(source, /static void setTripReviewJourney\(/,
    'the layer must accept the reviewed trip');
  const start = source.indexOf('private void drawTripReviewJourney(');
  assert.notEqual(start, -1, 'the layer must draw the reviewed trip');
  const body = source.slice(start, source.indexOf('\n\tprivate void checkVoiceAlerts', start));
  // Projected through the map's own tile box - that is what puts it on the map
  // rather than on a canvas with its own invented scale.
  assert.match(body, /tileBox\.getPixXFromLatLon\(/);
  assert.match(body, /tileBox\.getPixYFromLatLon\(/);
});

test('a section without a real line is skipped, never drawn as a guessed straight line', () => {
  const source = read(LAYER);
  const start = source.indexOf('private void drawTripReviewJourney(');
  const body = source.slice(start, source.indexOf('\n\tprivate void checkVoiceAlerts', start));
  assert.match(body, /section\.length < 4/, 'two points are the minimum for a line');
  assert.match(body, /continue;/);
});

test('the journey is drawn before the zoom gate, since a whole trip is seen zoomed out', () => {
  const source = read(LAYER);
  const draw = source.indexOf('drawTripReviewJourney(canvas, tileBox);');
  const gate = source.indexOf('if (tileBox.getZoom() < MIN_ZOOM)');
  assert.notEqual(draw, -1);
  assert.notEqual(gate, -1);
  assert.ok(draw < gate, 'the trip must be drawn before the reports zoom gate returns');
});

test('opening a review hands the trip to the map and closing it takes the trip back off', () => {
  const source = read(CONTROLLER);
  assert.match(source, /showTripOnMap\(activity, trip\);/);
  assert.match(source, /RoadCrewReportsLayer\.setTripReviewJourney\(null\);/,
    'the drive must not stay on the map after the review closes');
  const start = source.indexOf('private QuadRect showTripOnMap(');
  assert.notEqual(start, -1);
  const body = source.slice(start, source.indexOf('\n\tprivate void fitMapToTrip(', start));
  // Unreadable geometry is left out rather than approximated.
  assert.match(body, /points\.length\(\) < 2/);
  assert.match(body, /catch \(JSONException/);
});

test('the map is moved to the trip only after the panel is up, and again after that', () => {
  const source = read(CONTROLLER);
  const show = source.indexOf('dialog.show();');
  assert.notEqual(show, -1);
  const after = source.slice(show, show + 600);
  // Fitting before the panel existed let the app re-centre on the driver and
  // leave the drive off screen (Galin, 19.09).
  const fits = after.match(/fitMapToTrip\(activity, tripBounds\)/g) || [];
  assert.ok(fits.length >= 2, `the fit must be repeated, found ${fits.length}`);
  const body = source.slice(source.indexOf('private void fitMapToTrip('));
  assert.match(body, /fitRectToMap\(/, 'the map must move so the whole trip is in view');
  assert.match(body, /setRotate\(0, true\)/, 'north up');
  assert.match(body, /setMapLinkedToLocation\(false\)/, 'and not snapping back to the driver');
});

test('the review panel no longer carries its own map, and lets the map behind stay readable', () => {
  const source = read(REVIEW);
  assert.doesNotMatch(source, /content\.addView\(map,/,
    'the blank-canvas map must not be inside the panel any more');
  assert.match(source, /RoadCrewUi\.createMapDialog\(activity, content\)/,
    'the panel must be the low-dim bottom one, not the full-screen dialog');
});

test('the map panel neither dims nor freezes the map behind it', () => {
  const source = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewUi.java');
  const start = source.indexOf('static AlertDialog createMapDialog(');
  assert.notEqual(start, -1);
  const body = source.slice(start, source.indexOf('\n\t@NonNull', start + 10));
  assert.match(body, /setGravity\(Gravity\.BOTTOM\)/);
  const dim = body.match(/dimAmount = ([0-9.]+)f/);
  assert.ok(dim, 'the map panel must set its own dim');
  assert.ok(Number(dim[1]) <= 0.2, `the map must stay visible behind the panel, got ${dim[1]}`);
  // Galin, 19.09: visible but untouchable made it "a picture, not a map" -
  // he could not pan, zoom or turn it to find his bearings on a long drive.
  assert.match(body, /FLAG_NOT_TOUCH_MODAL/,
    'touches outside the panel must reach the map, or it is only a picture');
});
