import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract checks. ROADMAP 315 (Galin, 25.09): the review shows what the
// driver confirms - the driven RCS2 stretch - not the whole RCS1 section. The
// geometry itself is tested in RoadCrewDirectObservationTest (JUnit).
// ROADMAP 317: and the answer covers exactly what was shown, nothing more.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const JOURNAL = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt';
const CONTROLLER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewValidationController.java';
const REVIEW = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripReview.kt';

const drawn = () => {
  const source = read(JOURNAL);
  return source.slice(source.indexOf('private fun drawnStretch('), source.indexOf('private fun reviewTrip('));
};

test('both review entry points read the course stretches', () => {
  const source = read(JOURNAL);
  assert.equal((source.match(/reviewTrip\(db, /g) || []).length, 2);
  const reader = source.slice(source.indexOf('private fun reviewTrip('));
  assert.match(reader, /FROM direct_sections WHERE trip_id = \? AND state = 'STAGED'/);
});

test('one function decides what is drawn, from the shape the measures were taken on', () => {
  const body = drawn();
  assert.match(body, /FROM way_descriptors[\s\S]{0,80}osm_way_id = \? AND algorithm = \? AND fingerprint = \?/);
  assert.match(body, /canonicalFingerprint\(way\) != fingerprint\) return null/, 'a mismatched shape is not drawn');
  assert.match(body, /stretchLatLon\(way, from, to\)/, 'only the driven interval, never the whole way');
});

test('the kilometres are the drawn kilometres', () => {
  const source = read(JOURNAL);
  const reader = source.slice(source.indexOf('private fun reviewTrip('));
  assert.match(reader, /drawnStretch\(db, observation\) \?: continue[\s\S]{0,300}meters \+= /,
    'an undrawn stretch is not in the number the driver confirms');
  assert.match(read(REVIEW), /if \(trip\.direct\.isNotEmpty\(\)\) trip\.directMeters/);
});

test('the answer is stamped only on a stretch the review drew', () => {
  const source = read(JOURNAL);
  const confirm = source.slice(source.indexOf('fun confirm('), source.indexOf('fun saveDraft('));
  const stamp = confirm.indexOf('.put("suitabilityConfirmed", true)');
  const gate = confirm.indexOf('drawnStretch(db, observation) == null) continue');
  assert.ok(gate > 0 && gate < stamp, 'the same function as the review, before the stamp');
});

test('the map draws the stretches, and the old sections only when there are none', () => {
  const source = read(CONTROLLER);
  const show = source.slice(source.indexOf('private QuadRect showTripOnMap('));
  assert.ok(show.indexOf('trip.direct') < show.indexOf('trip.rows'));
  assert.match(show, /journey\.isEmpty\(\) \? trip\.rows/);
});
