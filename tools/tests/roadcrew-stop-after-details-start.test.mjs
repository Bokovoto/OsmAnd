import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract check only; this does not simulate Android navigation.
//
// Starting navigation from the route Details screen left the driver with no
// way to stop it. Closing Details tells the route menu to remember Details, and
// it does so at once, before navigation has even begun. From then on the Route
// tab reopened Details - offering only Details and Resume - and Resume
// remembered Details again. Stop lives in the main route panel, which the menu
// never reached. Starting from the main panel does not have the problem: that
// path leaves the menu remembering the main panel.
//
// The invariant: once navigation starts, the Route tab reaches Stop, whichever
// screen the Start was pressed on.
const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const details = read('../../OsmAnd/src/net/osmand/plus/routepreparationmenu/ChooseRouteFragment.java');
const menu = read('../../OsmAnd/src/net/osmand/plus/routepreparationmenu/MapRouteInfoMenu.java');

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

test('premise: closing Details reports to the menu synchronously, and a non-back close remembers Details', () => {
  // If either stops being true, the fix below is aimed at the wrong moment.
  assert.match(body(details, 'public void dismiss(boolean backPressed)'),
    /getMapRouteInfoMenu\(\)\.onDismiss\(this, currentMenuState, args, backPressed\);/);
  assert.match(body(menu, 'public void onDismiss(Fragment fragment'),
    /new MapRouteMenuStateHolder\(this, ROUTE_DETAILS,[\s\S]*menuBackStack\.push\(holder\);/);
});

test('starting road navigation from Details makes the menu forget Details', () => {
  const callback = body(details, 'public void onNavigationRequested()');
  const dismissAt = callback.indexOf('dismiss(false);');
  const startAt = callback.indexOf('getMapActions().startNavigation();');
  const forgetAt = callback.indexOf('getMapRouteInfoMenu().forgetRouteDetails();');
  assert.notEqual(forgetAt, -1, 'Details Start must tell the menu to forget Details');
  assert.ok(dismissAt < forgetAt, 'forgetting must come after the close that remembered Details');
  assert.ok(startAt < forgetAt, 'and only once road navigation has been started');
  // Public transport Show starts nothing, so Details stays a place to return to.
  const roadBranch = callback.slice(callback.indexOf('if (!app.getRoutingHelper().isPublicTransportMode())'));
  assert.ok(roadBranch.includes('forgetRouteDetails()'), 'forgetting belongs to the road branch only');
});

test('the menu forgets Details only when Details is what it remembers', () => {
  const forget = body(menu, 'void forgetRouteDetails()');
  assert.match(forget, /!menuBackStack\.empty\(\) && menuBackStack\.peek\(\)\.getType\(\) == ROUTE_DETAILS/);
  assert.match(forget, /menuBackStack\.pop\(\);/);
  assert.doesNotMatch(forget, /menuBackStack\.clear\(\)/, 'the main panel underneath must survive');
});
