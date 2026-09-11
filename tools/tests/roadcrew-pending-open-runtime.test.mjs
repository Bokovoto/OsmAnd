import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// Compile only the dependency-free production state and its harness. No Android
// classpath, stubs, module compilation or Gradle build is involved.
test('pending-open production state survives host changes and rejects stale callbacks', () => {
  const root = fileURLToPath(new URL('../../', import.meta.url));
  const tempRoot = resolve(tmpdir());
  const output = mkdtempSync(join(tempRoot, 'roadcrew-pending-open-'));
  try {
    const compile = spawnSync('javac', ['-d', output,
      join(root, 'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewPendingOpen.java'),
      join(root, 'tools/tests/java/RoadCrewPendingOpenTest.java')], { encoding: 'utf8', timeout: 30000 });
    assert.equal(compile.status, 0, `${compile.error ?? ''}\n${compile.stderr}`);
    const run = spawnSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.RoadCrewPendingOpenTest'],
      { encoding: 'utf8', timeout: 10000 });
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stderr}`);
    assert.match(run.stdout, /runtime scenarios PASS/);
  } finally {
    assert.equal(dirname(resolve(output)), tempRoot);
    assert.ok(basename(output).startsWith('roadcrew-pending-open-'));
    rmSync(output, { recursive: true, force: true });
  }
});
