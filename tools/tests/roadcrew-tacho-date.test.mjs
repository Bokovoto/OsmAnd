import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

test('isolated date protocol gates every operation and never retries writes', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-date-'));
  const protocol = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoDownloadDate.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-date/DownloadDateTest.java', import.meta.url));
  // Only two pure-Java files; no Android SDK, Gradle output or dependency classpath.
  execFileSync('javac', ['--release', '17', '-d', output, protocol, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'DownloadDateTest'], {encoding: 'utf8'});
  assert.match(result, /38 protocol cases passed/);
  console.log(result.trim());
});
