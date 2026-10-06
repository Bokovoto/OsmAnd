import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Galin, 06.10.2026, after RoadCrew's crash report offered itself to OsmAnd:
// "трябва да изпращат репорт за бъг на galin.b.vasilev1@gmail.com а не на
// OsmAnd". OsmAnd does not make RoadCrew; a RoadCrew report reaches nobody there.
// Source contract only: no Android runtime is simulated here.

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const feedback = read('../../OsmAnd/src/net/osmand/plus/feedback/FeedbackHelper.java');
const section = (start, end) => feedback.slice(feedback.indexOf(start), feedback.indexOf(end, feedback.indexOf(start) + 1));

test('RoadCrew sends its crash reports and support mail to Galin', () => {
  assert.match(feedback, /ROADCREW_EMAIL = "galin\.b\.vasilev1@gmail\.com"/);
  const crash = section('private void sendCrashLog(@NonNull List<File> files)', 'public void sendSupportEmail(@NonNull String screenName)');
  assert.match(crash, /roadCrew \? ROADCREW_EMAIL : "crash@osmand\.net"/);
  assert.match(crash, /roadCrew \? "RoadCrew bug" : "OsmAnd bug"/);
  const support = section('public void sendSupportEmail(@NonNull String screenName, @Nullable String additional)', 'public String getDeviceInfo()');
  assert.match(support, /roadCrew \? ROADCREW_EMAIL : "support@osmand\.net"/);
  assert.match(feedback, /RoadCrewReportsLayer\.isEnabled\(app\)/, 'only in RoadCrew; OsmAnd keeps its own addresses');
});

test('the crash notice names RoadCrew, in Bulgarian too', () => {
  const bg = read('../../OsmAnd/src/nightlyFree/res/values-bg/roadcrew_strings.xml');
  const en = read('../../OsmAnd/src/nightlyFree/res/values/roadcrew_strings.xml');
  for (const strings of [bg, en]) {
    const line = strings.match(/name="last_launch_crashed">([^<]+)</);
    assert.ok(line, 'last_launch_crashed is overridden');
    assert.match(line[1], /RoadCrew/);
    assert.doesNotMatch(line[1], /OsmAnd/);
  }
});
