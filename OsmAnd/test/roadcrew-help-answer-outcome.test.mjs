import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

// The author's "still need help" is a compare-and-set on the server (ROADMAP
// 238, S2), so its answer has outcomes, not just success. The phone must tell
// them apart - and must not take a reply that does not say what happened for
// proof that it happened. This runs the production enum itself, in jshell.

const path = new URL('../src/net/osmand/plus/roadcrew/HelpAnswerOutcome.java', import.meta.url);

function runProbe(probe, marker) {
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'],
    { input: probe, encoding: 'utf8', timeout: 40000 });
  assert.ifError(result.error);
  const output = `${result.stdout}\n${result.stderr}`;
  assert.equal(result.status, 0, output);
  assert.ok(output.includes(marker), output);
  assert.doesNotMatch(output, /PROBE_FAILED|Error:/, output);
}

test('every server reply maps to one outcome, and silence is never success', () => {
  assert.ok(existsSync(path), 'HelpAnswerOutcome.java must exist');
  const source = readFileSync(path, 'utf8').replace(/^package .*;\s*/m, '');
  runProbe(`
${source}
class Probe {
  static void check(boolean ok, String message) {
    if (!ok) { System.out.println("PROBE_FAILED " + message); throw new AssertionError(message); }
  }
  static void run() {
    check(HelpAnswerOutcome.from(200, "applied") == HelpAnswerOutcome.APPLIED, "applied");
    check(HelpAnswerOutcome.from(200, "already_active") == HelpAnswerOutcome.ALREADY_ACTIVE, "already active");
    check(HelpAnswerOutcome.from(409, "stale") == HelpAnswerOutcome.STALE, "stale");
    check(HelpAnswerOutcome.from(409, "no_longer_active") == HelpAnswerOutcome.NO_LONGER_ACTIVE, "closed");
    check(HelpAnswerOutcome.from(404, "") == HelpAnswerOutcome.NOT_FOUND, "missing");
    // A reply that does not say what happened is not proof that it did.
    check(HelpAnswerOutcome.from(200, "") == HelpAnswerOutcome.UNCONFIRMED, "bare 200");
    check(HelpAnswerOutcome.from(200, null) == HelpAnswerOutcome.UNCONFIRMED, "null result");
    check(HelpAnswerOutcome.from(409, "") == HelpAnswerOutcome.UNCONFIRMED, "bare 409");
    check(HelpAnswerOutcome.from(500, "applied") == HelpAnswerOutcome.UNCONFIRMED, "server error");
    check(HelpAnswerOutcome.APPLIED.requestIsActive() && HelpAnswerOutcome.ALREADY_ACTIVE.requestIsActive(), "active");
    check(!HelpAnswerOutcome.STALE.requestIsActive() && !HelpAnswerOutcome.UNCONFIRMED.requestIsActive(), "not active");
    check(HelpAnswerOutcome.NO_LONGER_ACTIVE.requestIsClosed() && HelpAnswerOutcome.NOT_FOUND.requestIsClosed(), "closed");
    check(!HelpAnswerOutcome.UNCONFIRMED.requestIsClosed() && !HelpAnswerOutcome.STALE.requestIsClosed(), "not closed");
    System.out.println("PROBE_OK");
  }
}
Probe.run();
`, 'PROBE_OK');
});
