import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 327: when the driver card reminder fires - plain Java, no phone needed.
test('driver card reminder: 08:00 on days 25-30 after the last download', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-reminder-'));
  const source = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoReminderPlan.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-reminder/ReminderPlanTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, source, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'ReminderPlanTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ reminder checks passed/);
  console.log(result.trim());
});
