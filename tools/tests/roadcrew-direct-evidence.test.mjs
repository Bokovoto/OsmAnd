import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract checks only. Galin, 21.09: rcs2 is what the map is filled
// from, so the directed observations must travel the live path instead of only
// the comparison stream - and they must travel on the driver's confirmation,
// like everything else the app uploads.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const JOURNAL = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt';
const COORDINATOR = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapObservationCoordinator.java';
const UPLOADER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapObservationUploader.java';
const SHADOW = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewShadowValidation.java';

test('the directed observations of a course wait in the journal', () => {
  const source = read(JOURNAL);
  assert.match(source, /CREATE TABLE direct_sections/,
    'they are kept beside the old ones until the old identity is retired');
  assert.match(source, /state TEXT NOT NULL DEFAULT 'STAGED' CHECK\(state IN \('STAGED','CONFIRMED','TRANSFERRED'\)\)/,
    'and go through the same three states, so nothing uploads before the yes');
  // Version 4 adds the way shapes the server asks for; 3 added these rows.
  assert.match(source, /null, 4\)/, 'the journal schema is version 4');
  assert.match(source, /fun captureDirect\(/);
  assert.match(source, /fun confirmedDirect\(/);
  assert.match(source, /fun markDirectTransferred\(/);
});

test("a discarded course leaves nothing behind in either identity", () => {
  const source = read(JOURNAL);
  const confirm = source.slice(source.indexOf('fun confirm('),
    source.indexOf('fun saveDraft('));
  assert.match(confirm, /if \(discardAll\) \{[\s\S]*DELETE FROM direct_sections WHERE trip_id/,
    'a course the driver rejected must not survive as directed evidence');
  assert.match(confirm, /UPDATE direct_sections SET state = 'CONFIRMED'/,
    'and a confirmed one carries its directed observations with it');
});

test('the directed segmentation runs for every phone, not only the comparison', () => {
  const source = read(COORDINATOR);
  const enable = source.slice(source.indexOf('private void enableComparison('),
    source.indexOf('private void captureDirectEvidence('));
  assert.doesNotMatch(enable, /if \(!RoadCrewShadowValidation\.isEnabled\(app\)\) \{\s*return;/,
    'it is what the map is filled from; it cannot depend on being in a programme');
  assert.match(enable, /captureDirectEvidence\(created, observations\)/,
    'its observations reach the journal');
  assert.match(enable, /if \(RoadCrewShadowValidation\.isEnabled\(app\)\)/,
    'and the comparison still gets its copy while it runs');
});

test('what is uploaded is what was stored, with a stable id', () => {
  assert.match(read(SHADOW), /public static String evidenceJson\(/,
    'one builder for both paths, so the two cannot drift');
  const source = read(COORDINATOR);
  assert.match(source, /String id = UUID\.randomUUID\(\)\.toString\(\);[\s\S]{0,200}evidenceJson\(observation, id\)/,
    'the id is fixed when the observation is stored, so a failed upload can name it again');
});

test('confirmed directed observations are uploaded to the live endpoint', () => {
  const source = read(UPLOADER);
  assert.match(source, /static void uploadConfirmedDirect\(/);
  const upload = source.slice(source.indexOf('static void uploadConfirmedDirect('),
    source.indexOf('private static Set<String> postDirectBatch('));
  assert.match(upload, /confirmedDirect\(/, 'only confirmed courses');
  assert.match(upload, /markDirectTransferred\(/, 'and they are crossed off only once accepted');
  const post = source.slice(source.indexOf('private static Set<String> postDirectBatch('));
  assert.match(post, /CHUNK_URL/, 'the live endpoint, not the comparison one');
  assert.match(post, /acceptedIds/, 'nothing is crossed off that the server did not acknowledge');
  // The run does both: the old queue and the confirmed directed rows.
  assert.match(source, /uploadAvailable\(app, outbox\);[\s\S]{0,300}uploadConfirmedDirect\(app\)/);
});

test('the phone answers for the shape of a way, so a free service need not', () => {
  // The server takes a way's length from this, and without the length no cell
  // can be placed. Asking OpenStreetMap for it instead is a public service
  // answering 504 today, and it will not carry ten thousand phones (21.09).
  const journal = read(JOURNAL);
  assert.match(journal, /CREATE TABLE way_descriptors/);
  assert.match(journal, /PRIMARY KEY\(osm_way_id, algorithm, fingerprint\)/,
    'one row per geometry: a thousand drives down one road store it once');
  assert.match(journal, /fun rememberWayShape\(/);
  assert.match(journal, /fun wayShapes\(/);

  const coordinator = read(COORDINATOR);
  assert.match(coordinator, /roadForOsmWay\(observation\.osmWayId\)/,
    'read while the road is still loaded - the only moment the phone has it');
  assert.match(coordinator, /getPoint31XTile\(index\)/,
    'the map file\'s own coordinates, which is what the server recomputes from');

  const uploader = read(UPLOADER);
  assert.match(uploader, /uploadRequestedDescriptors\(app, parsed\.optJSONArray\("needGeometryDescriptors"\)\)/,
    'sent when the server asks, not with every observation');
  assert.match(uploader, /way-geometry/, 'to the endpoint that verifies them');
});
