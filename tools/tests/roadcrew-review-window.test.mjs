import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 01.10.2026, before the release: the drive for approval is centred and
// a second later it is gone from the screen.

const source = name => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/${name}`, import.meta.url), 'utf8');
const file = path => fileURLToPath(new URL(path, import.meta.url));

test('keeping the drive in the window - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-review-window-'));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output,
    file('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewReviewWindow.java'),
    file('../../OsmAnd-java/src/main/java/net/osmand/data/QuadRect.java'),
    file('./roadcrew-review-window/ReviewWindowTest.java')], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'ReviewWindowTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ review window checks passed/);
  console.log(result.trim());
});

test('the review watches the drive instead of guessing with a second timer', () => {
  const controller = source('roadcrew/RoadCrewValidationController.java');
  assert.match(controller, /RoadCrewReviewWindow\.needsRefit\(/, 'the one-second tick checks it');
  assert.equal((controller.match(/fitMapToTrip\(activity, tripBounds\), \d+\)/g) ?? []).length, 1,
    'one delayed fit, not two blind ones');
});

test('while a drive is being approved the map holds its place', () => {
  const manager = source('helpers/MapDisplayPositionManager.java');
  assert.match(manager, /hasTripReviewJourney\(\)/,
    'the place does not flip between navigation and free driving under the panel');
});
