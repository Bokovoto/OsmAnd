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
  assert.match(push, /pendingOpen\.accept\(kind, referenceId\);\s*tryOpenPending\(mapActivity\);/);
  assert.doesNotMatch(push, /openPushReference\(/, 'opening happens only in tryOpenPending');
});

test('it opens only when this same activity is resumed and the layer is attached to it', () => {
  const open = body(layer, 'public static void tryOpenPending(');
  for (const guard of [/layer\.getMapActivity\(\) != mapActivity/, /isFinishing\(\)/, /isDestroyed\(\)/,
    /isAtLeast\(Lifecycle\.State\.RESUMED\)/]) {
    assert.match(open, guard);
  }
  assert.ok(open.indexOf('pendingOpen.consume(request)') > open.indexOf('showPendingHelpResult('),
    'lookup completion is not presentation');
  assert.match(open, /if \(request\.outcome == null\) \{\s*return;/);
  assert.match(body(layer, 'public void setMapActivity('), /tryOpenPending\(mapActivity\)/);
  assert.match(body(activity, 'protected void onResume()'), /RoadCrewReportsLayer\.tryOpenPending\(this\)/);
});

// Seen on the phone in Test 99 (logged): IntentHelper.parseContentIntent clears
// every intent that has extras, synchronously in onNewIntent, so the push taken
// 300 ms later found kind=null - a tap on a running app opened only the map.
test('a new intent is taken before IntentHelper clears its extras', () => {
  const newIntent = body(activity, 'protected void onNewIntent(Intent intent)');
  const take = newIntent.indexOf('RoadCrewReportsLayer.handlePushIntent(this, intent);');
  assert.notEqual(take, -1, 'taken directly, not after a delay');
  assert.ok(take < newIntent.indexOf('intentHelper.parseLaunchIntents()'), 'before the intent is parsed');
  assert.doesNotMatch(newIntent, /runInUIThread\(\(\) -> RoadCrewReportsLayer\.handlePushIntent/);
});

test('the initial intent is taken before any launch or content parser', () => {
  const create = body(activity, 'public void onCreate(Bundle savedInstanceState)');
  const take = create.indexOf('RoadCrewReportsLayer.handlePushIntent(this, getIntent());');
  assert.notEqual(take, -1, 'onNewIntent does not cover a fresh Activity');
  assert.ok(take < create.indexOf('intentHelper.parseLaunchIntents()'));
});

test('the notice, its confirmation and the inbox open the request by id', () => {
  const route = body(layer, 'private void openPushReference(');
  assert.match(route, /KIND_HELP_PROBABLY_RESOLVED\.equals\(kind\)\) \{\s*openHelpRequest\(mapActivity, referenceId, false\)/);
  assert.match(route, /KIND_HELP_RESOLVE_CONFIRM\.equals\(kind\)\) \{\s*openHelpRequest\(mapActivity, referenceId, true\)/);
  assert.match(body(layer, 'static void openInboxNotification('), /KIND_HELP_PROBABLY_RESOLVED\.equals\(entry\.kind\)/);
  assert.match(body(layer, 'private void openHelpRequest('), /pendingOpen\.accept/);
  const opening = body(layer, 'private static void loadPendingHelp(');
  assert.match(opening, /RoadCrewReportsSync\.fetchReport\(/);
  assert.doesNotMatch(opening, /mapActivity|showHelpReportDetailsDialog|confirmResolveHelpReport/,
    'lookup callback must not retain or present to an old activity');
  const present = body(layer, 'private void showPendingHelpResult(');
  assert.match(present, /activeHelpOpenDialog = confirmResolveHelpReport\(mapActivity, report\)/);
  assert.match(present, /activeHelpOpenDialog = showHelpReportDetailsDialog\(mapActivity, report\)/);
  for (const outcome of ['onClosed', 'onNotFound', 'onError']) assert.match(opening, new RegExp(`public void ${outcome}\\(`));
});

test('all lookup results use the latest request and current resumed host', () => {
  const complete = body(layer, 'private static void completePendingHelp(');
  assert.match(complete, /if \(!pendingOpen\.completeLookup\(request, outcome, report\)\) \{\s*return;/);
  assert.match(complete, /layer\.getMapActivity\(\)/);
  assert.match(complete, /tryOpenPending\(currentActivity\)/);
  assert.doesNotMatch(complete, /dialog\.show|confirmResolveHelpReport/);
  assert.match(body(layer, 'public static void tryOpenPending('), /dismissActiveHelpOpenDialog\(\)/);
});

test('the lookup takes an active report as the server holds it, and only reports a closed one', () => {
  const lookup = body(sync, 'public static void fetchReport(');
  assert.match(lookup, /"\/v1\/reports\/" \+ reportId/);
  assert.match(lookup, /HTTP_NOT_FOUND\) \{\s*app\.runInUIThread\(callback::onNotFound\)/);
  assert.match(lookup, /"ACTIVE"\.equals\(json\.optString\("status", ""\)\)\) \{\s*RoadCrewReportsRepository\.removeReport\(app, reportId\);\s*app\.runInUIThread\(callback::onClosed\)/);
  assert.match(lookup, /RoadCrewReportsRepository\.applyServerState\(app, report\);/);
});
