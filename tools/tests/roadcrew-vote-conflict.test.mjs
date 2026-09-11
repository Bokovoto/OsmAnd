import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; this does not simulate Android networking.
//
// A 409 on a vote meant success to the app, whatever it said. "Device already
// voted" is success; "report is no longer active" is not - yet it was recorded
// as delivered, so a vote on a closed request looked sent. Simply failing on it
// would be worse: syncNow catches the exception for the whole sync, so one
// closed report would stop every later pending report and the remote fetch,
// forever. The invariant: only a duplicate is success; a closed report drops
// its own local copy and the sync carries on.
const source = readFileSync(new URL(
  '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewReportsSync.java', import.meta.url), 'utf8')
  .replace(/\r\n/g, '\n');

function body(signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} must exist`);
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  assert.fail(`${signature} has no closing brace`);
}

test('a 409 on a vote is success only when the server says duplicate', () => {
  const post = body('private static JSONObject postJson(@NonNull String path, @NonNull String deviceId, @NonNull JSONObject body,\n\t\t\tboolean allowDuplicateVote)');
  const conflict = post.slice(post.indexOf('HTTP_CONFLICT'));
  assert.match(conflict, /isDuplicateVote\(responseBody\)/, 'the conflict must be read, not assumed');
  assert.match(conflict, /throw new ReportNoLongerActiveException\(/, 'any other conflict is not success');
  assert.match(body('private static boolean isDuplicateVote('), /optBoolean\("duplicate"/);
});

test('a closed report drops its own copy and the sync carries on', () => {
  const pending = body('private static void syncPendingReports(');
  assert.match(pending, /catch \(ReportNoLongerActiveException/, 'caught per report, inside the loop');
  const handler = pending.slice(pending.indexOf('catch (ReportNoLongerActiveException'));
  assert.match(handler, /RoadCrewReportsRepository\.removeReport\(app, report\.getId\(\)\)/);
  assert.match(handler, /continue;/, 'the loop goes on to the next report');
});
