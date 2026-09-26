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
const CONTROLLER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewValidationController.java';

test('only the explicit whole-course suitability action stamps staged direct rows', () => {
  const source = read(JOURNAL);
  const confirm = source.slice(source.indexOf('fun confirm('), source.indexOf('fun saveDraft('));
  assert.match(confirm, /suitabilityConfirmed: Boolean/);
  assert.match(confirm, /require\(!suitabilityConfirmed \|\| \(!discardAll && selected\.size == rows\.size\)\)/);
  const transaction = confirm.slice(confirm.indexOf('transaction(db)'));
  assert.match(transaction, /if \(suitabilityConfirmed\)/);
  assert.match(transaction, /SELECT seq, json FROM direct_sections WHERE trip_id = \? AND state = 'STAGED'/);
  assert.match(transaction, /\.put\("suitabilityConfirmed", true\)/);
  assert.match(transaction, /UPDATE direct_sections SET json = \? WHERE seq = \? AND state = 'STAGED'/);
  assert.doesNotMatch(transaction, /randomUUID/);
  const reader = source.slice(source.indexOf('fun confirmedDirect('), source.indexOf('fun markDirectTransferred('));
  assert.doesNotMatch(reader, /suitabilityConfirmed/,
    'old already-confirmed rows must not be rewritten when read for upload');
  assert.match(read(CONTROLLER), /questionIds\(\), true, discard, !discard, saved/);
  assert.match(read(COORDINATOR), /journal\.confirm\(tripId, included, questions, discard, suitabilityConfirmed\)/);
});

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
  assert.match(source, /String id = UUID\.randomUUID\(\)\.toString\(\);[\s\S]{0,200}evidenceJson\(observation, comparisonGroupId, id\)/,
    'the id is fixed when the observation is stored, so a failed upload can name it again');
});

// ROADMAP 314: the journal stored no course id, and confirm() demanded one, so
// every suitability confirmation rolled back and the review came back (24.09).
test('journal evidence carries the course its fix sequences are numbered in', () => {
  const shadow = read(SHADOW);
  const builder = shadow.slice(shadow.indexOf('public static String evidenceJson('));
  assert.match(builder, /^public static String evidenceJson\(@NonNull RoadCrewDirectObservation observation,\s*@Nullable String comparisonGroupId, @NonNull String id\)/);
  assert.match(builder.slice(0, builder.indexOf('}')), /directJson\(observation, comparisonGroupId, id\)/,
    'not null: without it the server can only credit full cells, never a turn');
});

test('a legacy row without a course id cannot make the review loop', () => {
  const source = read(JOURNAL);
  const confirm = source.slice(source.indexOf('fun confirm('), source.indexOf('fun saveDraft('));
  assert.doesNotMatch(confirm, /Directed observation has no course identity/,
    'a missing course id must not roll back the whole review');
  const transaction = confirm.slice(confirm.indexOf('transaction(db)'));
  assert.match(transaction, /optString\("comparisonGroupId"\)\.isBlank\(\)[\s\S]{0,200}continue/,
    'such a row is confirmed for passability but not stamped: the server rejects the flag without a course');
  assert.doesNotMatch(transaction, /put\("comparisonGroupId"/,
    'no course id is invented for an old row');
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

test('the course is still open when the directed observations arrive', () => {
  // Found with the live path silent while the comparison stream filled: the
  // course was closed first and the accumulator emptied after, so the last
  // stretches of every drive had no course to attach to and were dropped
  // (21.09). The comparison stream never noticed - it needs no course.
  const coordinator = read(COORDINATOR);
  assert.match(coordinator, /private void flushDirectPipeline\(\)/);
  for (const closing of ['navigationFinished\(\)', 'collectionPaused\(\)']) {
    const at = coordinator.search(new RegExp(closing));
    assert.notEqual(at, -1, `${closing} must exist`);
    const before = coordinator.slice(Math.max(0, at - 400), at);
    assert.match(before, /flushDirectPipeline\(\);/,
      `the accumulator must be emptied before ${closing}`);
  }
  const pipeline = readFileSync(new URL(
    '../../OsmAnd-java/src/main/java/net/osmand/router/RoadCrewObservationPipeline.java',
    import.meta.url), 'utf8');
  assert.match(pipeline, /public synchronized void flushDirect\(\)/);
});

test('the phone reports where the chain breaks, without being asked', () => {
  // A whole day went on guessing why the live path was empty, because every
  // step failed quietly. These counters ride to the server with the ordinary
  // diagnostics, so the next silence names its own cause (21.09).
  const coordinator = read(COORDINATOR);
  assert.match(coordinator, /evidence_journal_stored/);
  assert.match(coordinator, /evidence_journal_no_course/,
    'an observation with no course to attach to must be counted, not dropped in silence');
  const uploader = read(UPLOADER);
  assert.match(uploader, /evidence_upload_attempted/);
  assert.match(uploader, /evidence_uploaded/);
  assert.match(uploader, /evidence_upload_failed/);
  assert.match(read(JOURNAL), /fun captureDirect\(id: String, bucket: Long, json: String, at: Long, mayOpenCourse: Boolean\): Boolean/,
    'the journal has to say whether it stored, or the counter would be a guess');
});

// ROADMAP 330, Galin, 26.09: what a truck cannot prove is not recorded.
test('the phone records only a proven passage and says so on the wire', () => {
  const coordinator = read(COORDINATOR);
  const enable = coordinator.slice(coordinator.indexOf('private void enableComparison('),
    coordinator.indexOf('private void flushDirectPipeline('));
  assert.match(enable, /Config\.PROVEN_330/, 'the production pipeline runs the proof rule');
  assert.doesNotMatch(enable, /Config\.EXPERIMENT_321/);
  assert.match(enable, /enableDirectPipeline\([\s\S]*?this::loadRoadsAround\)/,
    'and may load the roads a tunnel runs through');
  assert.match(coordinator, /currentPipeline\.replaceRoads\(loaded\.getRouteObjects\(\),\s*sample\.latitude, sample\.longitude, LOAD_RADIUS_METERS\)/,
    'the map questions know which area is already in memory');
  const loader = coordinator.slice(coordinator.indexOf('private List<RouteDataObject> loadRoadsAround('));
  assert.match(loader.slice(0, 900), /isTruncated\(\) \|\| loaded\.isCancelled\(\)[\s\S]*?return null/,
    'a partial load proves nothing');

  const shadow = read(SHADOW);
  const json = shadow.slice(shadow.indexOf('private static JSONObject directJson(@NonNull RoadCrewDirectObservation observation,\n\t\t\t@Nullable String comparisonGroupId, @NonNull String id)'));
  assert.match(json, /json\.put\("passageIndex", observation\.passageIndex\)/);
  assert.match(json, /json\.put\("joinsPrevious", observation\.joinsPrevious\)/);
  assert.match(json, /json\.put\("bridged", true\)/);
});

test('a truck standing without a bearing still has GPS', () => {
  const coordinator = read(COORDINATOR);
  const update = coordinator.slice(coordinator.indexOf('public void updateLocation('),
    coordinator.indexOf('private void scheduleDrain('));
  assert.doesNotMatch(update, /\|\| !location\.hasSpeed\(\) \|\| !location\.hasBearing\(\)/,
    'a fix without speed or bearing is no longer thrown away before the pipeline');
  assert.match(update, /boolean positionOnly = !location\.hasSpeed\(\) \|\| !location\.hasBearing\(\)/);
  assert.match(update, /prev != null && !prev\.positionOnly && next\.positionOnly \? prev : next/,
    'and it never displaces a full fix still waiting to be matched');
  const process = coordinator.slice(coordinator.indexOf('private void process('),
    coordinator.indexOf('private RoadCrewObservationPipeline ensurePipeline('));
  assert.match(process, /if \(sample\.positionOnly\) \{[^}]*\.acceptPosition\([\s\S]*?return;\s*\}/,
    'it only keeps the record of GPS present: never matched, never recorded');
});
