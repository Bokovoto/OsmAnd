import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');

function method(source, signature) {
  const at = source.indexOf(signature); assert.notEqual(at, -1, signature);
  const start = source.indexOf('{', at);
  let end = start + 1, depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  assert.equal(depth, 0); return source.slice(at, end);
}

test('a final diagnostics request cannot be lost behind a running upload', () => {
  const queue = read('../../OsmAnd-java/src/main/java/net/osmand/router/RoadCrewFinalDiagnosticsQueue.java')
    .replace(/^package .*;\s*/m, '');
  const input = `${queue}
class Checks {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void run() {
    RoadCrewFinalDiagnosticsQueue state = new RoadCrewFinalDiagnosticsQueue();
    check(state.requestRun(null), "ordinary upload did not start");
    check(!state.requestRun("course-A-final"), "concurrent final request started a second worker");
    check("course-A-final".equals(state.pollFinalSnapshot()), "course A final snapshot was lost");
    check(!state.requestRun("course-B-final"), "running worker was not preserved");
    check(!state.finishIfIdle(), "worker stopped while course B was queued");
    check("course-B-final".equals(state.pollFinalSnapshot()), "course B replaced course A");
    check(state.finishIfIdle(), "worker did not return to idle");
    check(state.requestRun(null), "a later ordinary upload could not start");
    check(state.finishIfIdle(), "empty later upload did not return to idle");
  }
}
try { Checks.run(); System.out.println("FINAL_DIAGNOSTICS_QUEUE_OK"); }
catch (Throwable e) { System.out.println("FINAL_DIAGNOSTICS_QUEUE_FAILED " + e); }
/exit
`;
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'], { input, encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /FINAL_DIAGNOSTICS_QUEUE_OK/);
  assert.doesNotMatch(result.stdout + result.stderr, /Error:|FINAL_DIAGNOSTICS_QUEUE_FAILED/);
});

test('the uploader captures and drains final snapshots after observation batches', () => {
  const uploader = read('../src/net/osmand/plus/roadcrew/RoadCrewShadowUploader.java');
  const finalRequest = method(uploader, 'static void scheduleWithFinalDiagnostics(');
  assert.match(finalRequest, /RoadCrewShadowValidation\.diagnosticsJson\(\)/);

  const run = method(uploader, 'private static void runUpload(');
  assert.ok(run.indexOf('upload(app, outbox)') < run.indexOf('pollFinalSnapshot()'));
  assert.doesNotMatch(run, /sentSomething|!sent/);

  const diagnosticsOnly = method(uploader, 'private static void postDiagnosticsOnly(');
  assert.match(diagnosticsOnly, /String diagnostics/);
  assert.doesNotMatch(diagnosticsOnly, /RoadCrewShadowValidation\.diagnosticsJson\(\)/);
});
