import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// ROADMAP 326: with RCS1 off, the RCS1 outbox stays empty, and the scheduler
// returned before ever reaching uploadConfirmedDirect. Course 69b477c7 was
// confirmed and never sent. Source-contract checks, as for the other uploader
// rules; the scheduler runs on Android's executor and is not unit-testable here.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const UPLOADER = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapObservationUploader.java');
const COORDINATOR = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapObservationCoordinator.java');
const JOURNAL = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt');

const body = (source, start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));

test('a confirmed RCS2 row is scheduled even when the RCS1 outbox is empty', () => {
  const schedule = body(UPLOADER, 'static synchronized void schedule(', 'static void retryNow(');
  assert.match(schedule, /boolean directWaiting = RoadCrewTripJournal\.waitingCount\(app\) > 0;/);
  assert.match(schedule, /if \(snapshot\.isEmpty\(\) && !directWaiting\) \{\s*return;/);
  assert.doesNotMatch(schedule, /if \(snapshot\.isEmpty\(\)\) \{\s*return;/, 'an empty RCS1 queue alone must not stop it');
});

test('a failed or partial RCS2 send is retried on the normal cadence, not in a loop', () => {
  const schedule = body(UPLOADER, 'static synchronized void schedule(', 'static void retryNow(');
  assert.match(schedule, /lastDirectAttemptAtMillis \+ NORMAL_FLUSH_DELAY_MILLIS/);
  const run = body(UPLOADER, 'private static void runScheduled(', 'private static long uploadDelayMillis(');
  assert.match(run, /lastDirectAttemptAtMillis = System\.currentTimeMillis\(\);/);
  assert.match(run, /RoadCrewTripJournal\.waitingCount\(app\) > 0/, 'waiting rows keep the next run scheduled');
});

test('right after the driver confirms, the course is sent at once', () => {
  const transfer = body(COORDINATOR, 'void transferConfirmed() {', 'LOG.warn("Confirmed trip transfer deferred"');
  assert.match(transfer, /RoadCrewMapObservationUploader\.flushNow\(app, outbox\);/);
  assert.doesNotMatch(transfer, /RoadCrewMapObservationUploader\.schedule\(app, outbox\);/);
});

test('the waiting count is refreshed when rows are marked sent', () => {
  const mark = body(JOURNAL, 'fun markDirectTransferred(', 'fun navigationStarted(');
  assert.match(mark, /updateSummary\(db\)/, 'a stale count would keep rescheduling for rows already sent');
});
