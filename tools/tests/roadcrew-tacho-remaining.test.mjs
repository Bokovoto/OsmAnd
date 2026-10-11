import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 408. Galin, 11.10.2026: drivers need a right count of how much they
// may still drive - before the break, today, this week, over two weeks - and
// the deadlines that are hard to remember. Counted forward from the card's last
// minute by the same reading of 561/2006 as the violations. Synthetic timelines.

test('how much is left, from the card - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-remaining-'));
  const dir = '../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/';
  const sources = ['RoadCrewTachoActivities.java', 'RoadCrewTachoViolations.java', 'RoadCrewTachoRemaining.java']
    .map(name => fileURLToPath(new URL(dir + name, import.meta.url)));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-remaining/RemainingTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, ...sources, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.tacho.RemainingTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ remaining-time checks passed/);
  console.log(result.trim());
});
