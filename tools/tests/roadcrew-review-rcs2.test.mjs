import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract checks. ROADMAP 315 (Galin, 25.09): the review shows what the
// driver confirms - the driven RCS2 stretch - not the whole RCS1 section. The
// geometry itself is tested in RoadCrewDirectObservationTest (JUnit).

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const JOURNAL = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt';
const CONTROLLER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewValidationController.java';
const REVIEW = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripReview.kt';

test('both review entry points read the course stretches', () => {
  const source = read(JOURNAL);
  assert.equal((source.match(/reviewTrip\(db, /g) || []).length, 2);
  const reader = source.slice(source.indexOf('private fun reviewTrip('));
  assert.match(reader, /FROM direct_sections WHERE trip_id = \? AND state = 'STAGED'/);
  assert.match(reader, /FROM way_descriptors[\s\S]{0,80}osm_way_id = \? AND algorithm = \? AND fingerprint = \?/,
    'the shape is the one the measures were taken on');
  assert.match(reader, /canonicalFingerprint\(way\) != fingerprint\) continue/, 'a mismatched shape is not drawn');
  assert.match(reader, /stretchLatLon\(way, from, to\)/, 'only the driven interval, never the whole way');
  assert.ok(reader.indexOf('meters += to - from') < reader.indexOf('way_descriptors'),
    'an undrawable stretch still counts: its answer is still sent');
});

test('the map draws the stretches, and the old sections only when there are none', () => {
  const source = read(CONTROLLER);
  const show = source.slice(source.indexOf('private QuadRect showTripOnMap('));
  assert.ok(show.indexOf('trip.direct') < show.indexOf('trip.rows'));
  assert.match(show, /journey\.isEmpty\(\) \? trip\.rows/);
});

test('the kilometres are the confirmed kilometres', () => {
  assert.match(read(REVIEW), /if \(trip\.direct\.isNotEmpty\(\)\) trip\.directMeters/);
});
