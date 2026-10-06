import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Galin's phone, 06.10.2026 09:36 and 09:37, test.129: RoadCrew closed twice -
// ForegroundServiceDidNotStartInTimeException, the start asked from
// RoadCrewRecordingService.refreshFromForeground with startForegroundService().
// He was starting the route simulation: it began between that request and
// onStartCommand, eligible() turned false, and the service stopped without
// startForeground() - which Android punishes by closing the whole app. The
// service code was not changed in 129; the simulation reached an old race.
// Source contract only: no Android runtime is simulated here.

const service = readFileSync(new URL(
  '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewRecordingService.kt', import.meta.url), 'utf8')
  .replace(/\r\n/g, '\n');
const body = (start, end) => {
  const from = service.indexOf(start);
  assert.notEqual(from, -1, `${start} not found`);
  return service.slice(from, service.indexOf(end, from + start.length));
};

test('a started recording service enters the foreground before anything can stop it', () => {
  const start = body('override fun onStartCommand(', '\n    }\n');
  const foreground = start.indexOf('enterForeground()');
  assert.notEqual(foreground, -1, 'onStartCommand enters the foreground');
  const ways = ['stopSelf()', 'leaveForeground()', 'finishRecording()']
    .map(way => start.indexOf(way)).filter(index => index !== -1);
  assert.ok(ways.length > 0 && foreground < Math.min(...ways), 'before the first way out');
  assert.ok(foreground < start.indexOf('eligible(app)'), 'before the checks that can say no');
  assert.ok(foreground < start.indexOf('startRequested'), 'before the stale-start check too');
});

test('saying no leaves the foreground first, then stops', () => {
  const leave = body('private fun leaveForeground(', '\n    }\n');
  const dropped = leave.indexOf('stopForeground(STOP_FOREGROUND_REMOVE)');
  assert.ok(dropped !== -1 && dropped < leave.indexOf('stopSelf()'));
  const start = body('override fun onStartCommand(', '\n    }\n');
  const checks = start.slice(start.indexOf('eligible(app)'));
  assert.match(checks.slice(0, checks.indexOf('return START_NOT_STICKY')), /leaveForeground\(\)/,
    'not eligible: out of the foreground, not a bare stopSelf');
});

test('the foreground is the same location notice as before', () => {
  const enter = body('private fun enterForeground(', '\n    }\n');
  assert.match(enter, /startForeground\(NOTIFICATION_ID, notification, ServiceInfo\.FOREGROUND_SERVICE_TYPE_LOCATION\)/);
  assert.match(enter, /catch \(e: RuntimeException\)/, 'refused by Android: reported, not thrown');
});
