import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 03.10.2026: on test.129 (installed over adb, not yet published) the
// updater offered the published test.128 - it only checked that the tag was
// different. The phone refused the older version and the offer came back.

const roadcrew = new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/', import.meta.url);
const source = name => readFileSync(new URL(name, roadcrew), 'utf8').replace(/\r\n/g, '\n');

test('only a newer release is newer - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-update-version-'));
  const code = fileURLToPath(new URL('RoadCrewReleaseVersions.java', roadcrew));
  const harness = fileURLToPath(new URL('./roadcrew-update-version/ReleaseVersionsTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.ReleaseVersionsTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ release version checks passed/);
  console.log(result.trim());
});

test('the updater offers only a newer release, and drops an update left from before that is not', () => {
  const updater = source('RoadCrewAppUpdater.java');
  const check = updater.slice(updater.indexOf('private static void checkForUpdates('), updater.indexOf('private static UpdateInfo fetchLatestRelease('));
  assert.match(check, /RoadCrewReleaseVersions\.isNewer\(update\.tag, CURRENT_RELEASE_TAG\)/);
  assert.doesNotMatch(check, /!CURRENT_RELEASE_TAG\.equals\(update\.tag\)/, 'different is not newer');
  const resume = updater.slice(updater.indexOf('public static void onResume('), updater.indexOf('public static void onPause('));
  assert.match(resume, /RoadCrewReleaseVersions\.isNewer\(/, 'a pending update of an older release is dropped');
});
