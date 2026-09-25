import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 327: the driver card download (Gen1 + Gen2 DDD) against a simulated
// card, compiled as plain Java - no Android SDK, Gradle or phone needed.
test('driver card download follows 2016/799 Appendix 7 as amended by 2018/502', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-download-'));
  const source = (name) => fileURLToPath(new URL(`../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/${name}`, import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-download/CardDownloadTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-d', output,
    source('RoadCrewTachoDownloadDate.java'), source('RoadCrewTachoCardDownload.java'), harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'CardDownloadTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ card download checks passed/);
  console.log(result.trim());
});
