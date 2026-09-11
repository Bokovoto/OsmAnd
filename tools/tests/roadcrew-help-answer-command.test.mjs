import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; this does not simulate Android networking.
//
// Galin's decision 1 (ROADMAP 238): the author wakes a grey Help request every
// time it goes grey. The app refused the second answer locally - the answer was
// stored as a vote, and a vote is one per report and device, guarded twice
// (updateReportVote and RoadCrewReport.withVote). The answer is now its own
// command: sent at once, its outcome shown, the report refreshed from the
// server. The one-vote rule for everyone else is not touched.
const read = name => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/roadcrew/${name}`, import.meta.url), 'utf8')
  .replace(/\r\n/g, '\n');
const sync = read('RoadCrewReportsSync.java');
const layer = read('RoadCrewReportsLayer.java');
const repository = read('RoadCrewReportsRepository.java');
const report = read('RoadCrewReport.java');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} must exist`);
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  assert.fail(`${signature} has no closing brace`);
}

test('the panel answers through the command, not through a stored vote', () => {
  const panel = body(layer, 'private void addHelpPanelActions(');
  const wake = panel.slice(panel.indexOf('roadcrew_help_still_need_help'));
  assert.match(wake.slice(0, 200), /answerHelpClockFromPanel\(report\)/);
  assert.doesNotMatch(wake.slice(0, 200), /handleReportVote\(report, true\)/);
  assert.match(body(layer, 'private void answerHelpClockFromPanel('), /RoadCrewReportsSync\.answerHelpClock\(/);
});

test('the command reads the outcome and acts on it honestly', () => {
  const command = body(sync, 'public static HelpAnswerOutcome answerHelpClockBlocking(');
  assert.match(command, /body\.put\("vote", "CONFIRMED"\)/);
  assert.match(command, /if \(clock != null\) \{\s*body\.put\("clock"/, 'the clock is named when known');
  assert.match(command, /HelpAnswerOutcome\.from\(responseCode, result\)/);
  assert.match(command, /outcome\.requestIsActive\(\) && refreshAfter/, 'refresh only when asked and active');
  assert.match(command, /outcome\.requestIsClosed\(\)\) \{\s*RoadCrewReportsRepository\.removeReport/);
  assert.match(command, /return HelpAnswerOutcome\.UNCONFIRMED;/, 'a lost reply is unconfirmed, not done');
  assert.match(body(repository, 'public static synchronized void applyServerState('), /withLocalVote/);
});

test('everyone else still has one vote per report', () => {
  assert.match(body(repository, 'private static boolean updateReportVote('), /if \(report\.hasLocalVote\(\)\) \{\s*return false;/);
  assert.match(body(report, 'public RoadCrewReport withVote('), /if \(hasLocalVote\(\)\) \{\s*return this;/);
});
