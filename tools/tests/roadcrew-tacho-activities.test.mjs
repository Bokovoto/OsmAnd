import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 379. Galin, 09.10.2026: "Анализ" beside "Изпрати" on every downloaded
// card file, then the file's days by the phone's clock. Step 1 is reading the
// activities (EF 0504) and places (EF 0506) - synthetic files only, a real card
// file never enters this repository.

test('the card file\'s activities and places, by the phone\'s clock - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-activities-'));
  const code = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoActivities.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-activities/ActivitiesTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'ActivitiesTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ card activity checks passed/);
  console.log(result.trim());
});
