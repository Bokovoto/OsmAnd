import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; this does not post a notification.
//
// Galin's decision 2 (ROADMAP 238) and the confirmation that followed: the
// looks-resolved notice carries "Все още ми трябва помощ", answered in the
// background, and "Проблем е решен", which ends the request with no undo and so
// opens the app on the confirmation instead of acting from the notice.
// Codex's conditions (section 239): each PendingIntent distinct per report,
// action and clock; a bounded broadcast lifetime with finish() on every path; a
// lost reply shown as unconfirmed; an old answer never erasing a newer notice.
const read = path => readFileSync(new URL(`../../OsmAnd/${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const service = read('src/net/osmand/plus/roadcrew/RoadCrewFirebaseMessagingService.java');
const notice = read('src/net/osmand/plus/roadcrew/RoadCrewHelpNotice.java');
const receiver = read('src/net/osmand/plus/roadcrew/RoadCrewHelpActionReceiver.java');
const manifest = read('AndroidManifest-nightlyFree.xml');
const bg = read('src/nightlyFree/res/values-bg/roadcrew_strings.xml');

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

test('the looks-resolved notice, and only it, carries the buttons under its own tag', () => {
  assert.match(body(service, 'public void onMessageReceived('), /String helpClock = data\.get\("helpClock"\);/);
  const show = body(service, 'private void showNotification(');
  assert.match(show, /KIND_HELP_PROBABLY_RESOLVED\.equals\(kind\) && clock > 0/);
  assert.match(show, /RoadCrewHelpNotice\.addActions\(this, builder, referenceId, clock\);/);
  assert.match(show, /notify\(RoadCrewHelpNotice\.tag\(referenceId\),\s*RoadCrewHelpNotice\.NOTICE_ID/);
  assert.match(show, /notify\(notificationId, builder\.build\(\)\);/, 'every other kind as before');
});

test('the answer is a broadcast named by report and clock; the solve opens the confirmation', () => {
  const actions = body(notice, 'static void addActions(');
  const answer = actions.slice(actions.indexOf('Intent answer'), actions.indexOf('Intent resolve'));
  assert.match(answer, /new Intent\(context, RoadCrewHelpActionReceiver\.class\)/, 'explicit receiver');
  assert.match(answer, /setData\(answerUri\(reportId, clock\)\)/, 'identity per report and clock');
  assert.match(answer, /PendingIntent\.getBroadcast\(/);
  assert.match(answer, /FLAG_IMMUTABLE/);
  const resolve = actions.slice(actions.indexOf('Intent resolve'));
  assert.match(resolve, /new Intent\(context, MapActivity\.class\)/);
  assert.match(resolve, /setAction\(ACTION_RESOLVE_PREFIX \+ reportId\)/, 'identity per report');
  assert.match(resolve, /KIND_HELP_RESOLVE_CONFIRM/, 'it asks, it does not resolve');
  assert.match(resolve, /PendingIntent\.getActivity\(/);
  assert.doesNotMatch(resolve.slice(0, resolve.indexOf('builder.addAction')), /getBroadcast/);
  assert.match(actions, /putInt\(reportId, clock\)/, 'the notice remembers which clock it answers');
});

test('the confirmation from the button takes its notice away and reads its inbox entry', () => {
  const layer = read('src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java');
  assert.match(body(layer, 'private void openHelpRequest('), /if \(confirmResolve\) \{\s*\/\/[^\n]*\n\s*RoadCrewHelpNotice\.cancel\(mapActivity, reportId\);/);
  assert.match(body(layer, 'public static void tryOpenPending('),
    /markByReference\(mapActivity,\s*KIND_HELP_RESOLVE_CONFIRM\.equals\(kind\) \? KIND_HELP_PROBABLY_RESOLVED : kind, referenceId\)/);
  assert.match(body(notice, 'static void cancel('), /cancel\(tag\(reportId\), NOTICE_ID\)/);
});

test('an answer to an old clock never replaces a newer notice, and carries no buttons', () => {
  const outcome = body(notice, 'static void showOutcome(');
  assert.match(outcome, /getInt\(reportId, -1\) != clock/);
  assert.doesNotMatch(outcome, /addAction\(/);
  assert.match(outcome, /notify\(tag\(reportId\), NOTICE_ID/);
});

test('the receiver is bounded, honest and always finishes', () => {
  const receive = body(receiver, 'public void onReceive(');
  assert.match(receive, /goAsync\(\)/);
  assert.match(receive, /answerHelpClockBlocking\(app, reportId, clock,\s*CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, false\)/);
  assert.match(receive, /finally \{\s*pending\.finish\(\);/);
  const connect = Number(receiver.match(/CONNECT_TIMEOUT_MILLIS = ([\d_]+)/)[1].replace(/_/g, ''));
  const readTimeout = Number(receiver.match(/READ_TIMEOUT_MILLIS = ([\d_]+)/)[1].replace(/_/g, ''));
  assert.ok(connect + readTimeout < 10_000, 'within the time a broadcast gets');
  assert.match(receive, /HelpAnswerOutcome outcome = HelpAnswerOutcome\.UNCONFIRMED;/, 'no reply is not success');
});

test('the receiver is declared, not exported, and the button speaks Bulgarian', () => {
  assert.match(manifest, /android:name="net\.osmand\.plus\.roadcrew\.RoadCrewHelpActionReceiver"\s*android:exported="false"/);
  assert.match(bg, /name="roadcrew_help_notice_problem_solved">Проблем е решен</);
});
