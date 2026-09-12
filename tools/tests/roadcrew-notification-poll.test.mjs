import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// Galin, 2026-09-12: the notifications poll every 20 s only for a phone taking
// part in a Help; every other phone every 2 minutes (push covers Help nearby,
// chat messages and the author's notice). Staging cost test: 180 of about 330
// requests per phone-hour were this poll.

const root = fileURLToPath(new URL('../../', import.meta.url));
const layer = readFileSync(join(root,
  'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java'), 'utf8').replace(/\r\n/g, '\n');
const method = (name, next) => {
  const start = layer.indexOf(`private ${name}`);
  assert.ok(start >= 0, `missing ${name}`);
  return layer.slice(start, layer.indexOf('\n\t}\n', start) + 3);
};

test('the layer asks the policy, and only a Help participant keeps the 20 s poll', () => {
  const check = method('void checkHelpNotifications()');
  assert.match(check, /RoadCrewNotificationPollPolicy\.shouldCheck\(now, lastNotificationCheckMillis, isTakingPartInHelp\(\)\)/);
  const participant = method('boolean isTakingPartInHelp()');
  assert.match(participant, /openHelpReportIds\.isEmpty\(\)/);
  assert.match(participant, /openDirectChatRoomIds\.isEmpty\(\)/);
  assert.match(participant, /RoadCrewReportType\.HELP/);
  assert.match(participant, /isReportAuthor\(report\)/);
  assert.match(participant, /joinedHelpReportIds\.contains\(report\.getId\(\)\)/);
  assert.match(layer, /private final Set<String> joinedHelpReportIds = new HashSet<>\(\);/);
  // Joining a Help from the notice, the map or the details makes the phone a participant.
  const join = method('void joinAndOpenHelpChat(');
  assert.match(join, /joinedHelpReportIds\.add\(reportId\);\s*showHelpChatDialog\(mapActivity, reportId\);/);
  const fromReport = method('void openHelpChatFromReport(');
  assert.match(fromReport, /joinedHelpReportIds\.add\(reportId\);\s*getMapView\(\)\.refreshMap\(\);/);
});

// Real dependency-free production policy, not a reimplementation.
test('notification poll runtime: 180 checks an hour for a participant, 30 for a bystander', () => {
  const tempRoot = resolve(tmpdir());
  const output = mkdtempSync(join(tempRoot, 'roadcrew-notification-poll-'));
  try {
    const compile = spawnSync('javac', ['-d', output,
      join(root, 'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewNotificationPollPolicy.java'),
      join(root, 'tools/tests/java/RoadCrewNotificationPollPolicyTest.java')],
    { encoding: 'utf8', timeout: 30000 });
    assert.equal(compile.status, 0, `${compile.error ?? ''}\n${compile.stderr}`);
    const run = spawnSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.RoadCrewNotificationPollPolicyTest'],
      { encoding: 'utf8', timeout: 10000 });
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /one_hour participant_checks=180 bystander_checks=30/);
    assert.match(run.stdout, /notification poll policy scenarios PASS/);
  } finally {
    assert.equal(dirname(resolve(output)), tempRoot);
    assert.ok(basename(output).startsWith('roadcrew-notification-poll-'));
    rmSync(output, { recursive: true, force: true });
  }
});
