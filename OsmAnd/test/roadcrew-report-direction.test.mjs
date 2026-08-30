import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const button = read('../src/net/osmand/plus/roadcrew/RoadCrewReportButton.java');
const report = read('../src/net/osmand/plus/roadcrew/RoadCrewReport.java');
const sync = read('../src/net/osmand/plus/roadcrew/RoadCrewReportsSync.java');
const layer = read('../src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java');

test('report creation offers three directional scopes and normalizes the opposite bearing', () => {
  assert.match(button, /roadcrew_report_direction_mine/);
  assert.match(button, /roadcrew_report_direction_opposite/);
  assert.match(button, /roadcrew_report_direction_both/);
  assert.match(button, /normalizeBearing\(heading \+ 180\)/);
  assert.match(button, /addDirectionTile\(grid/);
  assert.match(button, /class DirectionIconView extends View/);
  assert.match(button, /grid\.setColumnCount\(2\)/);
  assert.match(button, /DirectionVisual\.FORWARD[\s\S]*centerX \+ 10f \* density/);
  assert.match(button, /DirectionVisual\.OPPOSITE[\s\S]*centerX - 10f \* density/);
});

test('direction survives local persistence and API synchronization', () => {
  assert.match(report, /RoadCrewReportDirection direction/);
  assert.match(sync, /body\.put\("direction", report\.getDirection\(\)\.name\(\)\)/);
  assert.match(sync, /object\.optString\("direction"/);
});

test('compact direction badge is vector and opposite-direction alerts are filtered', () => {
  assert.match(layer, /drawDirectionBadge\(canvas, tileBox, report/);
  assert.match(layer, /directionArrowPath/);
  assert.match(layer, /report\.appliesToBearing\(location\.getBearing\(\)\)/);
  assert.doesNotMatch(layer, /drawBitmap/);
});
