import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; this does not run an Activity.
//
// Galin's decision 3 (ROADMAP 238): tapping the looks-resolved notice opens that
// Help request. On a cold start the tap was lost - handlePushIntent needed a
// layer that did not exist yet - and the fixed 300 and 1600 ms delays around it
// guaranteed nothing. The payload now waits and is opened when this same
// activity is resumed, not finishing, and the layer is attached to it; the
// request is then fetched by id, so a closed or missing one is said plainly
// instead of offering buttons for a state that is gone.
const read = path => readFileSync(new URL(`../../OsmAnd/src/net/osmand/plus/${path}`, import.meta.url), 'utf8')
  .replace(/\r\n/g, '\n');
const layer = read('roadcrew/RoadCrewReportsLayer.java');
const sync = read('roadcrew/RoadCrewReportsSync.java');
const activity = read('activities/MapActivity.java');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} must exist`);
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  assert.fail(`${signature} has no closing brace`);
}

test('a tap is kept, not lost, when the layer is not ready', () => {
  const push = body(layer, 'public static boolean handlePushIntent(');
  assert.doesNotMatch(push, /activeLayer == null/, 'a cold start must not drop the tap');
  assert.match(push, /pendingKind = kind;\s*pendingReferenceId = referenceId;\s*tryOpenPending\(mapActivity\);/);
  assert.doesNotMatch(push, /openPushReference\(/, 'opening happens only in tryOpenPending');
});

test('it opens only when this same activity is resumed and the layer is attached to it', () => {
  const open = body(layer, 'public static void tryOpenPending(');
  for (const guard of [/layer\.getMapActivity\(\) != mapActivity/, /isFinishing\(\)/, /isDestroyed\(\)/,
    /isAtLeast\(Lifecycle\.State\.RESUMED\)/]) {
    assert.match(open, guard);
  }
  assert.ok(open.indexOf('pendingKind = null') < open.indexOf('openPushReference('), 'consumed once, when opened');
  assert.match(body(layer, 'public void setMapActivity('), /tryOpenPending\(mapActivity\)/);
  assert.match(body(activity, 'protected void onResume()'), /RoadCrewReportsLayer\.tryOpenPending\(this\)/);
});

test('the notice, its confirmation and the inbox open the request by id', () => {
  const route = body(layer, 'private void openPushReference(');
  assert.match(route, /KIND_HELP_PROBABLY_RESOLVED\.equals\(kind\)\) \{\s*openHelpRequest\(mapActivity, referenceId, false\)/);
  assert.match(route, /KIND_HELP_RESOLVE_CONFIRM\.equals\(kind\)\) \{\s*openHelpRequest\(mapActivity, referenceId, true\)/);
  assert.match(body(layer, 'static void openInboxNotification('), /KIND_HELP_PROBABLY_RESOLVED\.equals\(entry\.kind\)/);
  const opening = body(layer, 'private void openHelpRequest(');
  assert.match(opening, /RoadCrewReportsSync\.fetchReport\(/);
  assert.match(opening, /confirmResolve\) \{\s*confirmResolveHelpReport\(mapActivity, report\);\s*\} else \{\s*showHelpReportDetailsDialog\(mapActivity, report\);/);
  for (const outcome of ['onClosed', 'onNotFound', 'onError']) assert.match(opening, new RegExp(`public void ${outcome}\\(`));
});

test('the lookup takes an active report as the server holds it, and only reports a closed one', () => {
  const lookup = body(sync, 'public static void fetchReport(');
  assert.match(lookup, /"\/v1\/reports\/" \+ reportId/);
  assert.match(lookup, /HTTP_NOT_FOUND\) \{\s*app\.runInUIThread\(callback::onNotFound\)/);
  assert.match(lookup, /"ACTIVE"\.equals\(json\.optString\("status", ""\)\)\) \{\s*RoadCrewReportsRepository\.removeReport\(app, reportId\);\s*app\.runInUIThread\(callback::onClosed\)/);
  assert.match(lookup, /RoadCrewReportsRepository\.applyServerState\(app, report\);/);
});
