import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source contract only; no Android runtime here. ROADMAP 408: Galin approved the
// mockup - "Колко ми остава" under the last download, for the newest file only,
// the card reader exactly as before, and "Отвори файл от карта..." for a .DDD
// another app downloaded (Tacho Manager keeps them in Downloads/TachoManager).

const read = path => readFileSync(new URL(`../../OsmAnd/${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const layout = read('res/layout/roadcrew_tacho_card_activity.xml');
const card = read('src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoCardActivity.java');
const manifest = read('AndroidManifest-nightlyFree.xml');

test('the button sits under the last download, the file opener under the list', () => {
  const at = id => layout.indexOf(`android:id="@+id/${id}"`);
  assert.ok(at('roadcrewTachoRemainingButton') > at('roadcrewTachoLast'), 'under "Последно сваляне"');
  assert.ok(at('roadcrewTachoRemainingButton') < at('roadcrewTachoDownloadButton'), 'above the reader download');
  assert.ok(at('roadcrewTachoOpenFileButton') > at('roadcrewTachoHistoryRows'), 'under the downloaded files');
  assert.notEqual(at('roadcrewTachoDownloadButton'), -1, 'the reader stays');
});

test('one button, for the newest file', () => {
  assert.match(card, /RoadCrewTachoRemainingActivity\.open\(this, newest\.uri, newest\.takenAt\)/);
  assert.match(card, /Stored newest = history\.get\(0\);/, 'the list is newest first');
  const rows = card.slice(card.indexOf('private void renderHistory()'));
  assert.doesNotMatch(rows.slice(0, rows.indexOf('\n\t}\n')), /RoadCrewTachoRemainingActivity/, 'not on every row');
});

test('a card file from another app opens in the same screen', () => {
  assert.match(card, /Intent\.ACTION_OPEN_DOCUMENT/);
  assert.match(card, /RoadCrewTachoRemainingActivity\.open\(this, picked, takenAt\(name\)\)/,
    'its time from the name C_yyyyMMdd_HHmm_..., as for our own files');
});

test('the screen counts with the tested rule, and is declared', () => {
  const screen = read('src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoRemainingActivity.java');
  assert.match(screen, /RoadCrewTachoRemaining\.of\(/);
  assert.match(screen, /RoadCrewTachoActivities\.read\(.*downloadMinute\(takenAt\)\)/);
  assert.match(manifest, /android:name="net\.osmand\.plus\.roadcrew\.tacho\.RoadCrewTachoRemainingActivity"/);
});

test('every text it uses exists in Bulgarian and English', () => {
  const sources = card + read('src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoRemainingActivity.java');
  const used = [...new Set([...sources.matchAll(/R\.string\.(roadcrew_tacho_remaining_[a-z0-9_]+)/g)].map(m => m[1]))];
  assert.ok(used.length > 5, `found ${used.length}`);
  for (const values of ['src/nightlyFree/res/values/roadcrew_strings.xml', 'src/nightlyFree/res/values-bg/roadcrew_strings.xml']) {
    const xml = read(values);
    assert.deepEqual(used.filter(key => !xml.includes(`name="${key}"`)), [], values);
  }
});
