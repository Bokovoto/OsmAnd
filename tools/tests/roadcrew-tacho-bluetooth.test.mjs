import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('paired VDO card download rejects incomplete/corrupt protocol exchanges', () => {
  const out = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-ble-'));
  const source = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoVuDownload.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-bluetooth/VuDownloadTest.java', import.meta.url));
  const validator = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoDdd.java', import.meta.url));
  const progress = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoVuProgress.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', out, source, validator, progress, harness]);
  const result = execFileSync('java', ['-cp', out, 'VuDownloadTest'], { encoding: 'utf8' });
  assert.match(result, /\d+ Bluetooth download checks passed/);
  console.log(result.trim());
});

test('one button reaches the right tachograph and retries a dropped start', () => {
  const out = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-ble-choice-'));
  const source = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoBleChoice.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-bluetooth/ChoiceTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', out, source, harness]);
  const result = execFileSync('java', ['-cp', out, 'ChoiceTest'], { encoding: 'utf8' });
  assert.match(result, /\d+ Bluetooth choice checks passed/);
  console.log(result.trim());
});
