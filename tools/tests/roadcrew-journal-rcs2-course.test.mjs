import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';

// ROADMAP 323 (Galin, 25.09): RCS1 stops on the phone and RCS2 carries the
// course alone. The journal's own SQL is executed here against a real SQLite
// database built from the schema in the Kotlin source - not matched as text.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const JOURNAL = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt';
const COORDINATOR = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapObservationCoordinator.java';
const REVIEW = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripReview.kt';

const source = read(JOURNAL);
const sql = (name) => {
  const match = source.match(new RegExp(`private val ${name} = """([\\s\\S]*?)"""`));
  assert.ok(match, `${name} is a named statement in the journal`);
  return match[1];
};
const statements = (name) => sql(name).split(';').map((s) => s.trim()).filter(Boolean);

function database() {
  const db = new DatabaseSync(':memory:');
  for (const table of ['TRIPS_SQL', 'SECTIONS_SQL', 'DIRECT_SECTIONS_SQL', 'WAY_DESCRIPTORS_SQL']) {
    db.exec(sql(table));
  }
  return db;
}

const trip = (db, id, { closed = 1, reviewed = 0, auto = 1, endedAt = 1000 } = {}) =>
  db.prepare('INSERT INTO trips(id, closed, reviewed, snooze_until, auto_review, ended_at) VALUES (?, ?, ?, 0, ?, ?)')
    .run(id, closed, reviewed, auto, endedAt);
const direct = (db, tripId, observation, { state = 'STAGED', bucket = 900_000 } = {}) =>
  db.prepare('INSERT INTO direct_sections(trip_id, observation_id, bucket, json, state) VALUES (?, ?, ?, ?, ?)')
    .run(tripId, observation, bucket, '{}', state);

test('a course with only RCS2 rows is offered for review, listed and counted', () => {
  const db = database();
  trip(db, 'rcs2-only');
  direct(db, 'rcs2-only', 'a');
  direct(db, 'rcs2-only', 'b');

  assert.equal(db.prepare(sql('REVIEW_SQL')).get('1', '0')?.id, 'rcs2-only');
  const listed = db.prepare(sql('PENDING_TRIPS_SQL')).all(20);
  assert.equal(listed.length, 1);
  assert.equal(Object.values(listed[0])[2], 2, 'the count is RCS2 rows');
  assert.equal(Object.values(db.prepare(sql('PENDING_COUNT_SQL')).get())[0], 1);
  assert.ok(db.prepare(sql('REVIEW_TRIP_SQL')).get('rcs2-only'), 'reviewable by id');
});

test('a course whose RCS2 rows are already confirmed is not offered again', () => {
  const db = database();
  trip(db, 'done');
  direct(db, 'done', 'a', { state: 'CONFIRMED' });
  assert.equal(db.prepare(sql('REVIEW_SQL')).get('1', '0'), undefined);
  assert.equal(db.prepare(sql('PENDING_TRIPS_SQL')).all(20).length, 0);
  assert.equal(db.prepare(sql('REVIEW_TRIP_SQL')).get('done'), undefined);
});

test('prune keeps an RCS2 course, and removes empty trips and orphaned or stale RCS2 rows', () => {
  const db = database();
  const cutoff = 5_000_000;
  trip(db, 'kept');
  direct(db, 'kept', 'fresh', { bucket: cutoff + 1 });
  trip(db, 'empty');
  direct(db, 'gone-trip', 'orphan', { bucket: cutoff + 1 });
  trip(db, 'stale');
  direct(db, 'stale', 'old', { bucket: cutoff - 1 });
  direct(db, 'kept', 'sent', { state: 'TRANSFERRED', bucket: cutoff + 1 });
  // ROADMAP 324: the previous journal deleted a trip once its RCS1 rows were
  // sent; its confirmed RCS2 rows still wait for upload and must survive.
  direct(db, 'deleted-by-old-version', 'waiting', { state: 'CONFIRMED', bucket: cutoff - 1 });

  for (const statement of statements('PRUNE_SQL')) {
    const run = db.prepare(statement);
    statement.includes('?') ? run.run(cutoff) : run.run();
  }
  const trips = db.prepare('SELECT id FROM trips ORDER BY id').all().map((row) => row.id);
  const rows = db.prepare('SELECT observation_id FROM direct_sections ORDER BY observation_id').all()
    .map((row) => row.observation_id);
  assert.deepEqual(trips, ['kept']);
  assert.deepEqual(rows, ['fresh', 'waiting'], 'a confirmed row leaves only once it is sent');
});

test('revoking consent deletes the RCS2 data too, not only RCS1', () => {
  const db = database();
  trip(db, 't');
  direct(db, 't', 'a');
  db.prepare("INSERT INTO way_descriptors VALUES ('1', 1, 'f', 'm', '{}', 0)").run();
  for (const table of statements('REVOKED_TABLES')) {
    db.prepare(`DELETE FROM ${table}`).run();
  }
  for (const table of ['trips', 'sections', 'direct_sections', 'way_descriptors']) {
    assert.equal(Object.values(db.prepare(`SELECT COUNT(*) FROM ${table}`).get())[0], 0, table);
  }
  assert.match(source, /statements\(REVOKED_TABLES\)|REVOKED_TABLES\.split/,
    'the same list is what the journal deletes on revocation and on clear()');
});

test('RCS2 opens the course itself; RCS1 no longer does on this phone', () => {
  const capture = source.slice(source.indexOf('fun captureDirect('), source.indexOf('class WayDescriptor('));
  assert.match(capture, /if \(mayOpenCourse\) openTrip\(db, at\)/,
    'the first RCS2 stretch opens the course instead of being dropped');
  const coordinator = read(COORDINATOR);
  // ROADMAP 325: the guard against a car course is on the fix, not on the
  // emission - a one-stretch course is emitted by the flush after navigation.
  const process = coordinator.slice(coordinator.indexOf('private void process('));
  assert.match(process.slice(0, 400), /!enabled\s*\|\| !isCollectionContextActive\(\) \|\| !isTruckProfileActive\(\)\) \{ return; \}/,
    'only truck-recording fixes ever reach the pipeline');
  const direct = coordinator.slice(coordinator.indexOf('private void captureDirectEvidence('));
  assert.match(direct, /boolean mayOpenCourse = enabled;/);
  assert.doesNotMatch(direct.slice(0, 800), /isCollectionContextActive/,
    'the context at emission is not the context the fixes were recorded in');
  const end = coordinator.slice(coordinator.indexOf('private synchronized void endNavigationSession('));
  assert.ok(end.indexOf('flushDirectPipeline();') < end.indexOf('navigationFinished();'),
    'the last stretch lands before the course closes and is offered');
  assert.doesNotMatch(coordinator, /RoadCrewTripJournal\.get\(app\)\.capture\(/, 'no RCS1 journal capture');
  assert.doesNotMatch(coordinator, /captureLegacy\(/, 'no RCS1 shadow copy');
});

test('confirm and the review panel work for a course without RCS1 rows', () => {
  const confirm = source.slice(source.indexOf('fun confirm('), source.indexOf('fun saveDraft('));
  assert.doesNotMatch(confirm, /check\(rows\.isNotEmpty\(\) && activeTrip != trip\)/);
  assert.match(confirm, /stagedDirect > 0/);
  const review = read(REVIEW);
  assert.doesNotMatch(review, /rows\.first\(\)\.record/, 'the time range must not need an RCS1 row');
  assert.match(review, /trip\.startedAt/);
  assert.match(review, /if \(rows\.isNotEmpty\(\)\) map\.post/);
});
