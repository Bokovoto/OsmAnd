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

// Galin, 11.10.2026, on a report that goes out an hour or two late: "ако един
// телефон няма интернет два часа това реално е доста време за толкова динамична
// лайф карта ... ако няма интернет до 5-10 минути не трябва да праща нищо
// защото най-много да го бърка работите". So an unsent report waits ten
// minutes, not three hours. A call for help keeps its three hours: the driver
// still needs the help when the signal comes back (asked of Galin).
test('a report not yet sent waits ten minutes to go out, a call for help three hours', () => {
  assert.doesNotMatch(read('RoadCrewReportType.java'), /[Ll]ifetime/,
    'no lifetime per type left to fall back on');
  assert.match(read('RoadCrewReport.java'),
    /RoadCrewReportLifetime\.unsentExpiry\(createdAtMillis, type == RoadCrewReportType\.HELP\),/,
    'a report posted on the phone disappears when it can no longer go out');
  assert.match(read('RoadCrewReportsRepository.java'),
    /optLong\("expiresAtMillis", createdAtMillis \+ RoadCrewReportLifetime\.MILLIS\)/,
    'one stored without an expiry - the sync decides whether it may still go out');
  const lifetime = read('RoadCrewReportLifetime.java');
  assert.match(lifetime, /public static final long MILLIS = 3 \* 60 \* 60 \* 1000L;/, "the server's REPORT_LIFETIME_MS");
  assert.match(lifetime, /public static final long SEND_WINDOW_MILLIS = 10 \* 60 \* 1000L;/);
});

test('the sync drops a report that could not go out in time, before sending it', () => {
  const sync = read('RoadCrewReportsSync.java');
  const start = sync.indexOf('private static void syncPendingReports(');
  assert.notEqual(start, -1);
  const pending = sync.slice(start, sync.indexOf('\n\t}\n', start));
  const gate = pending.indexOf('RoadCrewReportLifetime.maySend(report.getCreatedAtMillis(), now,');
  assert.notEqual(gate, -1, 'every unsent report is asked, including one stored by an older version');
  assert.ok(gate < pending.indexOf('createRemoteReport('), 'before it is sent');
  assert.match(pending.slice(gate, pending.indexOf('createRemoteReport(')),
    /RoadCrewReportsRepository\.removeReport\(app, report\.getId\(\)\);\s*continue;/, 'dropped, not sent late');
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

// Galin, 11.10.2026, asked whether the minutes on a report's map label
// ("Полиция - 132 мин") should count from the last "още е там": "да". The
// details ("Подаден: преди 132 мин") keep the posting - that is what they say.
test('the map label counts its minutes from the last sighting', () => {
  const layer = read('RoadCrewReportsLayer.java');
  const start = layer.indexOf('private LabelLayout calculateLabelLayout(');
  assert.notEqual(start, -1);
  const label = layer.slice(start, layer.indexOf('\n\t}\n', start));
  assert.match(label, /RoadCrewReportLifetime\.lastSeenAt\(report\.getCreatedAtMillis\(\), report\.getExpiresAtMillis\(\)\)/);
  const details = layer.slice(layer.indexOf('private String createReportDetailsMessage('));
  assert.match(details.slice(0, details.indexOf('\n\t}\n')), /roadcrew_report_details_reported, formatReportedAge\(report\)/,
    'the details still say when it was posted');
});

test('the voice asks that rule, and nothing else', () => {
  const voice = read('RoadCrewVoiceAlerts.java');
  const start = voice.indexOf('private boolean isUncertain(');
  assert.notEqual(start, -1);
  const rule = voice.slice(start, voice.indexOf('\n\t}\n', start));
  assert.match(rule, /RoadCrewReportLifetime\.isOld\(/);
  assert.doesNotMatch(voice, /OLD_REPORT_LIFETIME_RATIO|OLD_REPORT_MIN_AGE_MILLIS/, 'one rule, in one place');
});
