import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// Drive test 2026-09-12: Galin closed his Help with "Приключи", the app said it
// was closed, and the server never heard of it - the Help stayed open for every
// driver. resolveHelpReport took the silent path for a Help held under its local
// id. Now the target is chosen by RoadCrewHelpResolveTarget and success is only
// ever reported after the server's answer (or for a Help that was never sent).

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = (name) => readFileSync(join(root, `OsmAnd/src/net/osmand/plus/roadcrew/${name}`), 'utf8')
  .replace(/\r\n/g, '\n');
const sync = read('RoadCrewReportsSync.java');
const repository = read('RoadCrewReportsRepository.java');
const body = (source, signature) => {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, `missing ${signature}`);
  return source.slice(start, source.indexOf('\n\t}\n', start) + 3);
};

test('resolve: success only after the server answered, or for a Help that was never sent', () => {
  const resolveHelp = body(sync, 'public static void resolveHelpReport(');
  assert.match(resolveHelp, /RoadCrewReportsRepository\.findHelpResolveTarget\(app, report\)/);
  assert.match(resolveHelp, /Kind\.UNKNOWN[\s\S]*?callback\.onError/);
  const successes = resolveHelp.split('callback::onSuccess').length - 1;
  assert.equal(successes, 2, 'one after LOCAL_ONLY, one after the POST');
  const localOnly = resolveHelp.indexOf('Kind.LOCAL_ONLY');
  const post = resolveHelp.indexOf('postJson("/v1/help-requests/"');
  const lastSuccess = resolveHelp.lastIndexOf('callback::onSuccess');
  assert.ok(localOnly >= 0 && post > localOnly && lastSuccess > post, 'the server path reports success after the POST');
  assert.doesNotMatch(resolveHelp, /findSyncedReportIdMatching/);
});

test('the sync records which server id a local report became, and the lookup uses it', () => {
  const marked = body(repository, 'public static synchronized boolean markReportSynced(');
  assert.match(marked, /rememberSyncedId\(app, reportId, syncedReportId\)/);
  const target = body(repository, 'static synchronized RoadCrewHelpResolveTarget findHelpResolveTarget(');
  assert.match(target, /RoadCrewHelpResolveTarget\.choose\(/);
  assert.match(target, /syncedIdFor\(app, /);
  assert.match(target, /RoadCrewReportSyncState\.PENDING_CREATE/);
  assert.match(target, /RoadCrewHelpResolveTarget\.onlyOwnHelpNear\(/);
});

// Real dependency-free production class, not a reimplementation.
test('help resolve target runtime scenarios', () => {
  const tempRoot = resolve(tmpdir());
  const output = mkdtempSync(join(tempRoot, 'roadcrew-help-resolve-'));
  try {
    const compile = spawnSync('javac', ['-d', output,
      join(root, 'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewHelpResolveTarget.java'),
      join(root, 'tools/tests/java/RoadCrewHelpResolveTargetTest.java')],
    { encoding: 'utf8', timeout: 30000 });
    assert.equal(compile.status, 0, `${compile.error ?? ''}\n${compile.stderr}`);
    const run = spawnSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.RoadCrewHelpResolveTargetTest'],
      { encoding: 'utf8', timeout: 10000 });
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /help resolve target scenarios PASS/);
  } finally {
    assert.equal(dirname(resolve(output)), tempRoot);
    assert.ok(basename(output).startsWith('roadcrew-help-resolve-'));
    rmSync(output, { recursive: true, force: true });
  }
});
