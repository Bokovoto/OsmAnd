import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 380. Galin, 09.10.2026: the card file's violations, checked against
// Tacho Manager. Regulation 561/2006; categories from Regulation 2016/403
// Annex I, read from EUR-Lex. Synthetic timelines only.

test('violations of 561/2006 with the categories of 2016/403 - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-violations-'));
  const dir = '../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/';
  const sources = ['RoadCrewTachoActivities.java', 'RoadCrewTachoViolations.java']
    .map(name => fileURLToPath(new URL(dir + name, import.meta.url)));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-violations/ViolationsTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, ...sources, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.tacho.ViolationsTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ violation checks passed/);
  console.log(result.trim());
});
