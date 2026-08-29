// Runs the journal's actual SQLite schema/statements, not Android/Kotlin runtime code.
// Run: node --test OsmAnd/test/roadcrew-trip-journal-schema.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';

const source = readFileSync(new URL('../src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt', import.meta.url), 'utf8');
const schema = name => {
  const match = source.match(new RegExp(`private val ${name} = """([\\s\\S]*?)"""`));
  assert.ok(match, `Missing production schema ${name}`);
  return match[1];
};
const statement = sql => {
  assert.ok(source.includes(`"${sql}"`), `Not a production SQL literal: ${sql}`);
  return sql;
};
const exclude = statement("UPDATE sections SET included = 0 WHERE trip_id = ? AND state = 'STAGED'");
const include = statement("UPDATE sections SET included = 1 WHERE seq = ? AND trip_id = ? AND state = 'STAGED'");
const remove = statement('DELETE FROM sections WHERE seq = ?');
const confirm = statement("UPDATE sections SET state = 'CONFIRMED', included = 1, question = ? WHERE seq = ?");
const transfer = statement("UPDATE sections SET state = 'TRANSFERRED' WHERE seq = ? AND state = 'CONFIRMED'");
const reviewed = statement('UPDATE trips SET reviewed = 1 WHERE id = ?');

function database(t) {
  const db = new DatabaseSync(':memory:');
  db.exec(schema('TRIPS_SQL'));
  db.exec(schema('SECTIONS_SQL'));
  t.after(() => db.close());
  return db;
}
function trip(db, id) {
  db.prepare(statement('INSERT INTO trips(id, closed, reviewed, snooze_until) VALUES (?, 0, 0, 0)')).run(id);
}
function section(db, tripId, key, bucket = 900000) {
  return Number(db.prepare(`INSERT INTO sections
    (trip_id, observation_key, bucket, record, geometry, road_name)
    VALUES (?, ?, ?, '{}', '[[43,26],[43.001,26]]', '')`).run(tripId, key, bucket).lastInsertRowid);
}
const row = (db, id) => db.prepare('SELECT * FROM sections WHERE seq = ?').get(id);

test('captured sections default to local staged state and cannot transfer directly', t => {
  const db = database(t);
  trip(db, 'a');
  const id = section(db, 'a', 'road:bucket');
  assert.equal(row(db, id).state, 'STAGED');
  assert.equal(row(db, id).question, 0);
  assert.equal(db.prepare(transfer).run(id).changes, 0);
  assert.throws(() => db.prepare("UPDATE sections SET state = 'UPLOADED' WHERE seq = ?").run(id));
  assert.throws(() => db.prepare('UPDATE sections SET included = 2 WHERE seq = ?').run(id));
});

test('duplicate sections are rejected within a trip, but another trip can record the same bucket', t => {
  const db = database(t);
  trip(db, 'a'); trip(db, 'b');
  section(db, 'a', 'same');
  assert.throws(() => section(db, 'a', 'same'));
  section(db, 'b', 'same');
  assert.equal(db.prepare('SELECT COUNT(*) AS n FROM sections').get().n, 2);
});

test('draft selection is trip-scoped and cannot change confirmed sections', t => {
  const db = database(t);
  trip(db, 'a'); trip(db, 'b');
  const first = section(db, 'a', 'first');
  const second = section(db, 'a', 'second');
  const foreign = section(db, 'b', 'foreign');
  db.prepare(confirm).run(0, second);
  db.prepare(exclude).run('a');
  assert.equal(row(db, first).included, 0);
  assert.equal(row(db, second).included, 1);
  assert.equal(row(db, foreign).included, 1);
  assert.equal(db.prepare(include).run(first, 'b').changes, 0);
  db.prepare(include).run(first, 'a');
  assert.equal(row(db, first).included, 1);
  assert.equal(row(db, first).state, 'STAGED');
});

test('red deletion and green confirmation commit together; only confirmed rows transfer', t => {
  const db = database(t);
  trip(db, 'a'); trip(db, 'b');
  const red = section(db, 'a', 'car');
  const green = section(db, 'a', 'truck');
  const unreviewed = section(db, 'b', 'pending');
  db.exec('BEGIN');
  db.prepare(remove).run(red);
  db.prepare(confirm).run(1, green);
  db.prepare(reviewed).run('a');
  db.exec('COMMIT');
  assert.equal(row(db, red), undefined);
  assert.equal(row(db, green).state, 'CONFIRMED');
  assert.equal(row(db, green).question, 1);
  assert.equal(db.prepare(transfer).run(green).changes, 1);
  assert.equal(db.prepare(transfer).run(green).changes, 0);
  assert.equal(db.prepare(transfer).run(unreviewed).changes, 0);
  assert.equal(row(db, unreviewed).state, 'STAGED');
});

test('rollback preserves both excluded geometry and unconfirmed state after interrupted review', t => {
  const db = database(t);
  trip(db, 'a');
  const first = section(db, 'a', 'red');
  const second = section(db, 'a', 'green');
  db.exec('BEGIN');
  db.prepare(remove).run(first);
  db.prepare(confirm).run(1, second);
  db.prepare(reviewed).run('a');
  db.exec('ROLLBACK');
  assert.ok(row(db, first));
  assert.equal(row(db, second).state, 'STAGED');
  assert.equal(db.prepare("SELECT reviewed FROM trips WHERE id = 'a'").get().reviewed, 0);
});

test('cold restart closes trips without approving them or losing the red draft', t => {
  const db = database(t);
  trip(db, 'a');
  const id = section(db, 'a', 'red');
  db.prepare(exclude).run('a');
  db.exec(statement('UPDATE trips SET closed = 1 WHERE closed = 0'));
  assert.equal(db.prepare("SELECT closed FROM trips WHERE id = 'a'").get().closed, 1);
  assert.equal(row(db, id).state, 'STAGED');
  assert.equal(row(db, id).included, 0);
});

test('pruning removes expired sections and completed transfers, retaining live review evidence', t => {
  const db = database(t);
  trip(db, 'a');
  const old = section(db, 'a', 'old', 900000);
  const staged = section(db, 'a', 'new', 1800000);
  const done = section(db, 'a', 'done', 1800000);
  const question = section(db, 'a', 'question', 1800000);
  db.prepare(confirm).run(0, done);
  db.prepare(confirm).run(1, question);
  db.prepare(transfer).run(done); db.prepare(transfer).run(question);
  db.prepare(statement('DELETE FROM sections WHERE bucket < ?')).run(1800000);
  db.exec(statement("DELETE FROM sections WHERE state = 'TRANSFERRED' AND question = 0"));
  assert.equal(row(db, old), undefined);
  assert.equal(row(db, done), undefined);
  assert.equal(row(db, staged).state, 'STAGED');
  assert.equal(row(db, question).question, 1);
});

test('an unavailable explicitly requested check cannot starve other requested checks', t => {
  const db = database(t);
  trip(db, 'a');
  const old = section(db, 'a', 'yesterday', 900000);
  const first = section(db, 'a', 'start', 1800000);
  const middle = section(db, 'a', 'middle', 2700000);
  const last = section(db, 'a', 'end', 3600000);
  const staged = section(db, 'a', 'car-pending', 1800000);
  for (const id of [old, first, middle, last]) {
    db.prepare(confirm).run(1, id);
    db.prepare(transfer).run(id);
  }
  const next = db.prepare(schema('NEXT_QUESTION_SQL'));
  assert.equal(next.get(1800000, 3600000, 4500000).seq, first);
  db.prepare(statement('UPDATE sections SET retry_at = ? WHERE seq = ?')).run(4500000, first);
  assert.equal(next.get(1800000, 3600000, 4500000).seq, middle);
  db.prepare(statement('UPDATE sections SET question = 0 WHERE seq = ?')).run(middle);
  assert.equal(next.get(1800000, 3600000, 4500000).seq, last);
  assert.equal(row(db, staged).state, 'STAGED');
  assert.equal(next.get(1800000, 1700000, 4500000), undefined);
});

const closeCourse = statement('UPDATE trips SET closed = 1, auto_review = ?, ended_at = ? WHERE id = ?');
const presented = 'UPDATE trips SET prompted = 1 WHERE id = ? AND closed = 1';

test('automatic review selects each ended navigation, not free driving, active courses or yesterday', t => {
  const db = database(t);
  for (const id of ['active', 'free', 'yesterday', 'first', 'second']) {
    trip(db, id); section(db, id, id);
  }
  db.prepare(closeCourse).run(0, 3000, 'free');
  db.prepare(closeCourse).run(1, 1000, 'yesterday');
  db.prepare(closeCourse).run(1, 2100, 'first');
  const review = db.prepare(schema('REVIEW_SQL'));
  assert.equal(review.get('0', 2000).id, 'first');
  db.prepare(presented).run('first');
  assert.equal(review.get('0', 2000).id, 'first');
  db.prepare(reviewed).run('first');
  db.prepare(closeCourse).run(1, 3100, 'second');
  assert.equal(review.get('0', 2000).id, 'second');
  db.prepare(presented).run('second');
  assert.equal(review.get('0', 2000).id, 'second');
  // Manual review still offers any unconfirmed course and does not need an internet timestamp.
  assert.equal(review.get('1', 2000).id, 'second');
  db.prepare(reviewed).run('second');
  assert.equal(review.get('1', 2000).id, 'free');
});

test('an interrupted draft retains selections and remains pending until answered', t => {
  const db = database(t);
  trip(db, 'a'); const id = section(db, 'a', 'car');
  db.prepare(closeCourse).run(1, 2000, 'a');
  db.prepare(exclude).run('a');
  db.prepare(presented).run('a');
  db.prepare('UPDATE trips SET snooze_until = ? WHERE id = ?').run(9223372036854775807n, 'a');
  const review = db.prepare(schema('REVIEW_SQL'));
  assert.equal(review.get('0', 0).id, 'a');
  assert.equal(review.get('1', 0).id, 'a');
  assert.equal(row(db, id).included, 0);
  assert.equal(row(db, id).state, 'STAGED');
});

test('restriction checks are explicit, trip-scoped and cannot survive excluded car sections', t => {
  const db = database(t);
  trip(db, 'a'); trip(db, 'b');
  const truck = section(db, 'a', 'truck');
  const car = section(db, 'a', 'car');
  const foreign = section(db, 'b', 'foreign');
  const request = db.prepare(statement("UPDATE sections SET question = 1 WHERE seq = ? AND trip_id = ? AND state = 'STAGED' AND included = 1"));
  db.prepare(exclude).run('a'); db.prepare(include).run(truck, 'a');
  assert.equal(request.run(truck, 'a').changes, 1);
  assert.equal(request.run(car, 'a').changes, 0);
  assert.equal(request.run(foreign, 'a').changes, 0);
  db.prepare(confirm).run(0, foreign); db.prepare(transfer).run(foreign);
  assert.equal(row(db, foreign).question, 0);
  assert.equal(db.prepare(schema('NEXT_QUESTION_SQL')).get(0, 2000000, 3000000), undefined);
  assert.match(source, /val questions = questionIds\.toSet\(\)/);
  assert.doesNotMatch(source, /distinct\.(first|last)\(\)\.seq/);
});

test('v1 migration preserves pending courses but removes old automatically selected tiny questions', t => {
  const db = new DatabaseSync(':memory:'); t.after(() => db.close());
  db.exec(`CREATE TABLE trips (id TEXT PRIMARY KEY, closed INTEGER NOT NULL DEFAULT 0,
    reviewed INTEGER NOT NULL DEFAULT 0, snooze_until INTEGER NOT NULL DEFAULT 0)`);
  db.exec(schema('SECTIONS_SQL'));
  trip(db, 'a'); const id = section(db, 'a', 'preserve');
  db.prepare(confirm).run(1, id);
  for (const sql of source.matchAll(/db\.execSQL\("(ALTER TABLE trips [^"]+)"\)/g)) db.exec(sql[1]);
  db.exec(statement('UPDATE sections SET question = 0'));
  const data = db.prepare('SELECT * FROM trips').get();
  assert.equal(data.auto_review, 0); assert.equal(data.prompted, 0); assert.equal(data.ended_at, 0);
  assert.equal(row(db, id).state, 'CONFIRMED');
  assert.equal(row(db, id).question, 0);
  assert.equal(db.prepare(schema('REVIEW_SQL')).get('0', 0), undefined);
});
