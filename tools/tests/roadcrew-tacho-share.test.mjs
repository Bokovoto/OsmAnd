import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// ROADMAP 327: the driver card file is sent as a ZIP holding the original DDD.
test('driver card file goes out as a ZIP with the original DDD inside', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-share-'));
  const source = fileURLToPath(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoShareZip.java', import.meta.url));
  const harness = fileURLToPath(new URL('./roadcrew-tacho-share/ShareZipTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, source, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'ShareZipTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ share zip checks passed/);
  console.log(result.trim());
});
