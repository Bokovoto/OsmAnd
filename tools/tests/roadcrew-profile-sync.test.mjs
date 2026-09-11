import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const sync = readFileSync(join(root,
  'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewReportsSync.java'), 'utf8').replace(/\r\n/g, '\n');
const start = sync.indexOf('private static void syncDriverProfile(');
const profile = sync.slice(start, sync.indexOf('private static void syncPendingReports(', start));

test('profile POST is gated by its sent snapshot and only a positive ACK suppresses retries', () => {
  assert.match(profile, /String payload = body\.toString\(\);/);
  assert.match(profile, /long now = SystemClock\.elapsedRealtime\(\);/);
  assert.match(profile, /if \(!profileSyncState\.beginSync\(deviceId, payload, now\)\) \{\s*return;/);
  const gate = profile.indexOf('profileSyncState.beginSync(');
  const post = profile.indexOf('postJson("/v1/devices/profile", deviceId, body)');
  const ok = profile.indexOf('Boolean.TRUE.equals(response.opt("ok"))');
  const ack = profile.indexOf('profileSyncState.acknowledge(deviceId, payload, now)');
  assert.ok(gate >= 0 && post > gate && ok > post && ack > ok);
  assert.match(profile.slice(ok, ack), /throw new IOException/);
  assert.match(sync, /private static final RoadCrewProfileSyncState profileSyncState/);
  assert.match(sync, /sendHeartbeat\(app, deviceId\);\s*syncDriverProfile\(app, deviceId\);\s*syncPendingReports\(app, deviceId\);\s*fetchRemoteReports\(app, deviceId\);/);
  assert.match(sync, /AUTO_SYNC_INTERVAL_MILLIS = 60 \* 1000/);
});

// Real dependency-free production state, not a reimplementation of the gate.
test('profile ACK runtime: repair, failures, edits, identities and measured request reduction', () => {
  const tempRoot = resolve(tmpdir());
  const output = mkdtempSync(join(tempRoot, 'roadcrew-profile-sync-'));
  try {
    const compile = spawnSync('javac', ['-d', output,
      join(root, 'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewProfileSyncState.java'),
      join(root, 'tools/tests/java/RoadCrewProfileSyncStateTest.java')],
    { encoding: 'utf8', timeout: 30000 });
    assert.equal(compile.status, 0, `${compile.error ?? ''}\n${compile.stderr}`);
    const run = spawnSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.RoadCrewProfileSyncStateTest'],
      { encoding: 'utf8', timeout: 10000 });
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /opportunities=600 control_posts=600 treatment_posts=10 skipped=590/);
    assert.match(run.stdout, /profile sync runtime scenarios PASS/);
  } finally {
    assert.equal(dirname(resolve(output)), tempRoot);
    assert.ok(basename(output).startsWith('roadcrew-profile-sync-'));
    rmSync(output, { recursive: true, force: true });
  }
});
