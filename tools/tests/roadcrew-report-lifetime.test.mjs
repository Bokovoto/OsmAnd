import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
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
