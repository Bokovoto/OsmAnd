import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 381. Galin, 09.10.2026, approving the mockup: "Анализ" beside
// "Изпрати" on every downloaded file of the card screen, then the file's
// violations, the last 28 days and the days, by the phone's clock.

const root = new URL('../../OsmAnd/', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8').replace(/\r\n/g, '\n');
const tacho = 'src/net/osmand/plus/roadcrew/tacho/';
const between = (text, start, end) => text.slice(text.indexOf(start), text.indexOf(end, text.indexOf(start) + 1));

test('the 28 days, the violations in them, the next download, the countries - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-analysis-'));
  const sources = ['RoadCrewTachoActivities.java', 'RoadCrewTachoViolations.java', 'RoadCrewTachoAnalysis.java', 'RoadCrewTachoNations.java', 'RoadCrewTachoDates.java']
    .map(name => fileURLToPath(new URL(tacho + name, root)));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-analysis/AnalysisTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, ...sources, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.tacho.AnalysisTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ analysis checks passed/);
  console.log(result.trim());
});

test('"Анализ" sits beside "Изпрати" on every downloaded file and opens that file', () => {
  const card = read(tacho + 'RoadCrewTachoCardActivity.java');
  const rows = between(card, 'private void renderHistory()', '\n\t}\n');
  assert.match(rows, /roadcrew_tacho_ic_chart/, 'the chart icon');
  assert.ok(rows.indexOf('roadcrew_tacho_ic_chart') < rows.indexOf('roadcrew_tacho_ic_send'), 'before the send button');
  assert.match(rows, /RoadCrewTachoAnalysisActivity\.open\(this, file\.uri, file\.takenAt\)/, 'opens this file');
  const manifest = read('AndroidManifest-nightlyFree.xml');
  for (const name of ['RoadCrewTachoAnalysisActivity', 'RoadCrewTachoDayActivity']) {
    const entry = manifest.match(new RegExp(`<activity[^>]*${name}[^>]*>`));
    assert.ok(entry, `${name} declared`);
    assert.match(entry[0], /android:exported="false"/, `${name} is not open to other apps`);
  }
});

test('every rule and category has its words, Bulgarian and English; the screen says it is a guide', () => {
  const bg = read('res/values-bg/roadcrew_tacho_strings.xml');
  const en = read('res/values/roadcrew_tacho_strings.xml');
  const keys = ['roadcrew_tacho_analysis', 'roadcrew_tacho_analysis_note',
    'roadcrew_tacho_rule_break', 'roadcrew_tacho_rule_daily_driving', 'roadcrew_tacho_rule_weekly_driving',
    'roadcrew_tacho_rule_fortnight_driving', 'roadcrew_tacho_rule_daily_rest', 'roadcrew_tacho_rule_weekly_rest_late',
    'roadcrew_tacho_severity_minor', 'roadcrew_tacho_severity_serious', 'roadcrew_tacho_severity_very_serious',
    'roadcrew_tacho_severity_most_serious'];
  for (const key of keys) {
    assert.ok(bg.includes(`name="${key}"`), `bg ${key}`);
    assert.ok(en.includes(`name="${key}"`), `en ${key}`);
  }
  assert.match(bg, /name="roadcrew_tacho_analysis_note">[^<]*ориентир/, 'a guide, not the official analysis');
  const screen = read(tacho + 'RoadCrewTachoAnalysisActivity.java');
  assert.match(screen, /R\.string\.roadcrew_tacho_analysis_note/);
});

test('no day read: the two texts Galin approved, never the green "no violations"', () => {
  // Galin, 10.10.2026: "Да" - ROADMAP 394.
  const bg = read('res/values-bg/roadcrew_tacho_strings.xml');
  const en = read('res/values/roadcrew_tacho_strings.xml');
  assert.match(bg, /name="roadcrew_tacho_analysis_empty">Картата няма записи за дейности\.</);
  assert.match(bg, /name="roadcrew_tacho_violations_unknown">Не са прочетени дни от картата - нарушенията не могат да се проверят\.</);
  for (const key of ['roadcrew_tacho_analysis_empty', 'roadcrew_tacho_violations_unknown']) {
    assert.ok(en.includes(`name="${key}"`), `en ${key}`);
  }
  const screen = read(tacho + 'RoadCrewTachoAnalysisActivity.java');
  const violations = between(screen, 'private View violations(', '\n\t}\n');
  assert.match(violations, /if \(s\.noDays\)[\s\S]*R\.string\.roadcrew_tacho_violations_unknown[\s\S]*else if \(list\.isEmpty\(\)/,
    'the unknown text comes first; the green check only in the else');
  const render = between(screen, 'private void render()', '\n\t}\n');
  assert.match(render, /s\.noDays && card\.complete[\s\S]*R\.string\.roadcrew_tacho_analysis_empty/, 'an empty card is not "damaged"');
});
