import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const hud = read('../src/net/osmand/plus/roadcrew/RoadCrewNeonHud.java');
const inbox = read('../src/net/osmand/plus/roadcrew/RoadCrewNotificationInbox.java');
const layer = read('../src/net/osmand/plus/roadcrew/RoadCrewReportsLayer.java');
const push = read('../src/net/osmand/plus/roadcrew/RoadCrewFirebaseMessagingService.java');

test('header separates driver notifications from pending route reviews', () => {
  assert.match(hud, /ic_action_message[\s\S]*RoadCrewNotificationInbox\.show\(activity\)/);
  assert.match(hud, /ic_action_route_distance[\s\S]*showPendingTripReviews\(\)/);
  assert.doesNotMatch(hud, /R\.drawable\.ic_action_help[\s\S]*showNearbyHelpReports/);
  assert.match(hud, /RoadCrewNotificationInbox\.unreadCount\(activity\)/);
  assert.match(hud, /RoadCrewTripJournal\.pendingTripCount\(activity\)/);
});

test('polled and push notifications enter durable local history before presentation', () => {
  assert.match(layer, /onNotifications[\s\S]*RoadCrewNotificationInbox\.store\(getApplication\(\), notifications\)[\s\S]*for \(RoadCrewNotification notification/);
  assert.match(push, /RoadCrewNotificationInbox\.storePush[\s\S]*showNotification/);
  assert.match(inbox, /MAX_ENTRIES = 100/);
  assert.match(inbox, /getSharedPreferences\(PREFS, Context\.MODE_PRIVATE\)/);
  assert.match(inbox, /PLATE_SAFETY_ALERT/);
  assert.match(inbox, /pushCopyWasRead \|\| previous != null && previous\.read/);
});

test('opening or acknowledging an item marks it read and routes to its action', () => {
  assert.match(inbox, /markRead\(activity, entry\.id\)[\s\S]*openInboxNotification\(activity, entry\)/);
  assert.match(layer, /HELP_NEARBY[\s\S]*showHelpNotificationDialog/);
  assert.match(layer, /HELP_CHAT_MESSAGE[\s\S]*showHelpChatMessageNotificationDialog/);
  assert.match(layer, /DIRECT_CHAT_MESSAGE[\s\S]*showDirectChatNotificationDialog/);
  assert.match(layer, /PLATE_SAFETY_ALERT[\s\S]*showPlateSafetyAlertDialog/);
  assert.match(layer, /markByReference\(mapActivity, kind, referenceId\)/);
});
