// Pure Java policy execution and source wiring guards, not Android lifecycle tests.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const service = read('../src/net/osmand/plus/roadcrew/RoadCrewRecordingService.kt');
const coordinator = read('../src/net/osmand/plus/roadcrew/RoadCrewMapObservationCoordinator.java');
const consent = read('../src/net/osmand/plus/roadcrew/RoadCrewMapObservationConsent.java');

test('actual policy records without a route in foreground and background, with existing eligibility', () => {
  const policy = read('../../OsmAnd-java/src/main/java/net/osmand/router/RoadCrewRecordingPolicy.java')
    .replace(/^package .*;\s*/m, '');
  const input = `${policy}
class Checks {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void run() {
    for (int flags = 0; flags < 32; flags++) {
      boolean consent = (flags & 1) != 0, truck = (flags & 2) != 0;
      boolean simulation = (flags & 4) != 0, visible = (flags & 8) != 0;
      boolean service = (flags & 16) != 0;
      check(RoadCrewRecordingPolicy.canCollect(consent, truck, simulation, visible, service)
        == (consent && truck && !simulation && (visible || service)), "Collection " + flags);
      for (boolean permission : new boolean[]{false, true}) {
        check(RoadCrewRecordingPolicy.canStartService(consent, truck, simulation, visible, permission)
          == (consent && truck && !simulation && visible && permission), "Service start " + flags);
      }
    }
    for (boolean active : new boolean[]{false, true}) {
      for (boolean visible : new boolean[]{false, true}) {
        for (boolean otherGps : new boolean[]{false, true}) {
          check(RoadCrewRecordingPolicy.needsOwnGps(active, visible, otherGps)
            == (active && !visible && !otherGps), "GPS ownership");
        }
      }
    }
    check(RoadCrewRecordingPolicy.canCollect(true, true, false, false, true), "No-route background denied");
    check(!RoadCrewRecordingPolicy.needsOwnGps(true, false, true), "Duplicate navigation GPS");
    check(RoadCrewRecordingPolicy.needsOwnGps(true, false, false), "No GPS after navigation stops");
    check(RoadCrewRecordingPolicy.canCollect(true, true, false, false, true), "Navigation stop ended collection");
  }
}
try { Checks.run(); System.out.println("RECORDING_POLICY_OK"); } catch (Throwable e) { System.out.println("RECORDING_POLICY_FAILED " + e); }
/exit
`;
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'], { input, encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /RECORDING_POLICY_OK/);
  assert.doesNotMatch(result.stdout + result.stderr, /Error:|RECORDING_POLICY_FAILED/);
});

test('no unrequested recording Stop/Resume control, pause preference or consent reset', () => {
  const profile = read('../src/net/osmand/plus/roadcrew/RoadCrewDriverProfileDialog.java');
  assert.doesNotMatch(service + consent + profile,
    /RECORDING_STOPPED|isRecordingStopped|setRecordingStopped|RoadCrewRecordingControls|roadcrew_recording_(?:stop|resume)|STOP_RECORDING|RESUME_RECORDING/);
  assert.match(consent, /CURRENT_CONSENT_VERSION = 2;/);
  assert.doesNotMatch(service, /\.addAction\(|onTaskRemoved|areNotificationsEnabled|isFollowingMode|stopNavigationService/);
  assert.match(service, /catch \(e: SecurityException\) \{\s*\/\/[^\n]*\s*LOG.warn\(/);
  for (const path of ['../res/values/strings.xml', '../res/values-bg/strings.xml']) {
    const xml = read(path);
    assert.doesNotMatch(xml, /name="roadcrew_recording_(?:stop|resume|stopped|permissions|background_ready|foreground_only)"/);
  }
});

test('independent location service hands over GPS without changing navigation or GPX', () => {
  const manifest = read('../AndroidManifest.xml');
  const activity = read('../src/net/osmand/plus/activities/MapActivity.java');
  const nav = read('../src/net/osmand/plus/NavigationService.java');
  assert.match(manifest, /<service\s+android:name="net.osmand.plus.roadcrew.RoadCrewRecordingService"\s+android:exported="false"\s+android:foregroundServiceType="location"\s+android:stopWithTask="true"\s*\/>/);
  const navigationServiceStart = manifest.indexOf('android:name="net.osmand.plus.NavigationService"');
  const navigationService = manifest.slice(navigationServiceStart, manifest.indexOf('</service>', navigationServiceStart));
  assert.ok(navigationServiceStart >= 0);
  assert.match(navigationService, /android:stopWithTask="false"/);
  assert.match(activity, /settings.MAP_ACTIVITY_ENABLED = true;\s*net.osmand.plus.roadcrew.RoadCrewMapObservationCoordinator.onMapActivityAvailable\(app\);/);
  assert.match(service, /startForeground\(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION\)/);
  assert.match(service, /return START_NOT_STICKY/);
  assert.match(service, /gps === helper && eligible\(app\) && needsOwnGps\(app\)/);
  assert.match(service, /else releaseGps\(\)/);
  assert.match(service, /app.navigationService != null \|\| app.navigationCarAppService != null/);
  assert.match(service, /LOCATION_SOURCE.addListener\(sourceListener\)/);
  assert.match(service, /LOCATION_SOURCE.removeListener\(sourceListener\)/);
  assert.match(service, /if \(!stopping\) handler.postDelayed\(this, 2_000\)/);
  assert.match(service, /RoadCrewMapObservationCoordinator.updateLocationFromNavigationService\(app, it\)/);
  assert.match(nav, /RoadCrewMapObservationCoordinator.updateLocationFromNavigationService/);
  assert.doesNotMatch(service, /USED_BY_|stopService\(|\.setEnabled\(|\.setConsent\(|TripJournal.*(?:clear|revoke)/);
});

test('navigation end keeps collection eligible and the same confirmed-passage pipeline', () => {
  const end = coordinator.split('private synchronized void endNavigationSession()')[1].split('private void queueTripBoundary')[0];
  assert.match(end, /navigationFinished\(\)/);
  assert.doesNotMatch(end, /RecordingService|setEnabled|stopListening|enabled = false/);
  assert.match(coordinator, /RoadCrewRecordingService.isRunning\(\) \|\| isActiveTruckNavigation\(\)/);
  assert.match(coordinator, /ApplicationMode mode = isActiveTruckNavigation\(\)\s*\? app.getRoutingHelper\(\).getAppMode\(\)\s*: app.getSettings\(\).getApplicationMode\(\)/);
  assert.match(coordinator, /RoadCrewTripJournal.get\(app\).capture\(evidence, observedAt, road, binding\)/);
  assert.match(coordinator, /RoadCrewTripJournal.get\(app\).transferConfirmed\(outbox\)/);
  assert.match(coordinator, /!location.hasAccuracy\(\)[\s\S]*!location.hasSpeed\(\) \|\| !location.hasBearing\(\)/);
});
