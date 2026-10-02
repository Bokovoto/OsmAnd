import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 02.10.2026: a downloaded driver card is never written over an
// earlier one - on Android 7 to 9 too, where the phone stores the file itself.

const TACHO = '../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/';

test('a taken file name gets (1), (2) - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-file-name-'));
  const source = fileURLToPath(new URL(TACHO + 'RoadCrewTachoFileNames.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-file-name/FileNameTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, source, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'FileNameTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ file name checks passed/);
  console.log(result.trim());
});

test('the card download is stored under a name nothing else holds', () => {
  const activity = readFileSync(new URL(TACHO + 'RoadCrewTachoCardActivity.java', import.meta.url), 'utf8');
  const save = activity.slice(activity.indexOf('private Stored saveDdd('), activity.indexOf('private static byte[] readAll('));
  assert.match(save, /RoadCrewTachoFileNames\.unused\(dir, name\)/, 'Android 7 to 9: never over an earlier file');
  assert.doesNotMatch(save, /new File\(dir, name\)/);
});
