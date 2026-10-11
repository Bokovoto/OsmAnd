import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 09.10.2026 (ROADMAP 385): "Всички да стоят три часа от поставянето като
// всяко още е там да рестартира брояча". The server has done so since that
// evening, and the phone takes the server's expiresAt on every sync. A report
// not yet sent - posted with no signal - still lived by its type on the phone:
// police and DAI one hour, danger two, help four, weigh station six, mobile
// camera a whole day. An unsent report that runs out is dropped, never sent, so
// an hour without signal lost a police report, and a camera could still go out
// a day late as a fresh one.

const ROADCREW = new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/', import.meta.url);
const read = name => readFileSync(new URL(name, ROADCREW), 'utf8').replace(/\r\n/g, '\n');

test('a report not yet sent lives three hours, whatever its type', () => {
  assert.doesNotMatch(read('RoadCrewReportType.java'), /[Ll]ifetime/,
    'no lifetime per type left to fall back on');
  assert.match(read('RoadCrewReport.java'), /createdAtMillis \+ RoadCrewReportLifetime\.MILLIS,/,
    'a report posted on the phone');
  assert.match(read('RoadCrewReportsRepository.java'),
    /optLong\("expiresAtMillis", createdAtMillis \+ RoadCrewReportLifetime\.MILLIS\)/,
    'one stored without an expiry');
  assert.match(read('RoadCrewReportLifetime.java'), /public static final long MILLIS = 3 \* 60 \* 60 \* 1000L;/,
    "the server's REPORT_LIFETIME_MS");
});

test('nothing in RoadCrew still asks a type how long it lives', () => {
  const asking = readdirSync(ROADCREW).filter(name => /\.(java|kt)$/.test(name))
    .filter(name => /getDefaultLifetimeMillis/.test(read(name)));
  assert.deepEqual(asking, []);
});

// The second half of ROADMAP 385's note: the voice measured "old" from the
// posting, so a post another driver had just seen could be announced as
// "стар маркиран пост". Old means not seen for a while.
test('an old marked post is one nobody has seen for a while - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-report-lifetime-'));
  const file = path => fileURLToPath(new URL(path, import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output,
    fileURLToPath(new URL('RoadCrewReportLifetime.java', ROADCREW)),
    file('./roadcrew-report-lifetime/ReportLifetimeTest.java')], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'ReportLifetimeTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ report lifetime checks passed/);
  console.log(result.trim());
});

test('the voice asks that rule, and nothing else', () => {
  const voice = read('RoadCrewVoiceAlerts.java');
  const start = voice.indexOf('private boolean isUncertain(');
  assert.notEqual(start, -1);
  const rule = voice.slice(start, voice.indexOf('\n\t}\n', start));
  assert.match(rule, /RoadCrewReportLifetime\.isOld\(/);
  assert.doesNotMatch(voice, /OLD_REPORT_LIFETIME_RATIO|OLD_REPORT_MIN_AGE_MILLIS/, 'one rule, in one place');
});
