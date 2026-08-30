// Pure JVM state logic + source wiring guards. Does not compile Android or simulate a device.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const routing = read('../src/net/osmand/plus/routing/RoutingHelper.java');
const coordinator = read('../src/net/osmand/plus/roadcrew/RoadCrewMapObservationCoordinator.java');
const controller = read('../src/net/osmand/plus/roadcrew/RoadCrewValidationController.java');
const journal = read('../src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt');
const ui = read('../src/net/osmand/plus/roadcrew/RoadCrewTripReview.kt');
const hud = read('../src/net/osmand/plus/roadcrew/RoadCrewNeonHud.java');
const background = read('../src/net/osmand/plus/roadcrew/RoadCrewTripMapBackground.java');

function method(source, signature) {
  const at = source.indexOf(signature); assert.notEqual(at, -1, signature);
  const start = source.indexOf('{', at);
  let end = start + 1, depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  assert.equal(depth, 0); return source.slice(at, end);
}

test('navigation boundaries are real Start / Stop / final arrival, never pause or recalculation', () => {
  assert.match(method(routing, 'public void setFollowingMode(boolean follow)'),
    /if \(follow\) \{ net\.osmand\.plus\.roadcrew\.RoadCrewMapObservationCoordinator\.onNavigationStarted\(app\); \}/);
  const clear = method(routing, 'public synchronized void clearCurrentRoute(');
  assert.equal(clear.match(/onNavigationFinished/g)?.length, 1);
  assert.match(clear, /if \(newFinalLocation == null\) \{[\s\S]*setFollowingMode\(false\);[\s\S]*onNavigationFinished\(app\);\s*\}/);
  assert.doesNotMatch(method(routing, 'public void pauseNavigation()'), /onNavigationFinished|clearCurrentRoute/);
  assert.doesNotMatch(method(coordinator, 'private synchronized void observeTripContext()'), /previousFollowing|endNavigationSession/);
  assert.match(journal, /if \(!lifecycle\.isNavigating\) finish\(false\)/);
  assert.match(journal, /lifecycle\.shouldCloseForGap\(lastPassageAt, at\)/);
  assert.match(method(coordinator, 'RoadCrewTripJournal.Trip prepareTripReview('),
    /if \(manual && !navigationSessionActive\) \{ journal\.collectionPaused\(\); resetPipeline\(\); \}/);
  // A drain already queued before a boundary must not capture a new-generation fix into the old course.
  assert.equal(method(coordinator, 'private void process(').match(/sample\.generation != appliedCollectionGeneration/g)?.length, 2);
  const boundary = method(coordinator, 'private void queueTripBoundary(');
  assert.match(boundary, /boundary.run\(\);\s*appliedCollectionGeneration = generation;/);
  assert.match(boundary, /catch \(RuntimeException e\) \{[\s\S]*appliedCollectionGeneration = -1;/);
  assert.doesNotMatch(boundary, /finally \{[^}]*appliedCollectionGeneration = generation/);
});

test('finished-course review is immediate, mandatory and not throttled by network retry', () => {
  const tick = method(controller, 'private void tick()');
  const work = method(controller, 'private void work(');
  assert.match(tick, /boolean review = canPrompt && \(manual \|\| SystemClock.elapsedRealtime\(\) >= nextReviewElapsed\)/);
  assert.doesNotMatch(tick, /next_prompt|last_attempt/);
  assert.ok(work.indexOf('prepareTripReview') < work.indexOf('token.isEmpty()'));
  assert.ok(work.indexOf('prepareTripReview') < work.indexOf('RoadCrewValidationApi.request('));
  assert.match(method(controller, 'private boolean updateSafety()'), /!app.getRoutingHelper\(\).isPauseNavigation\(\)/);
  assert.match(method(controller, 'private boolean updateSafety()'), /!RoadCrewMapObservationCoordinator.getInstance\(app\).hasNavigationSession\(\)/);
  const show = method(controller, 'private void showTrip(');
  assert.match(show, /dialog\.setCancelable\(false\)/);
  assert.match(show, /dialog\.setCanceledOnTouchOutside\(false\)/);
  assert.doesNotMatch(show, /markTripReviewShown/);
  assert.match(show, /selectedIds\(\), editor\[0\].questionIds\(\), false, false/);
  assert.doesNotMatch(ui, /roadcrew_validation_later/);
  assert.match(method(coordinator, 'private synchronized void endNavigationSession()'), /onNavigationFinished\(app\)/);
});

test('whole recorded course is the initial viewport; selected car legs do not become checks', () => {
  assert.match(ui, /if \(selected != position\) \{\s*selected = position\s*map.focus\(position\)/);
  assert.match(ui, /map.post \{ map.overview\(\) \}/);
  assert.match(ui, /rows.filter \{ it.included && it.question \}/);
  assert.match(ui, /rows.filter \{ !it.included \}.forEach \{ it.question = false \}/);
  assert.match(ui, /if \(i == 0\) moveTo\(x, y\) else lineTo\(x, y\)/);
  assert.doesNotMatch(ui, /calculatedRoute|routingHelper.*route/);
});

test('pending trips are visible and whole-course review uses the installed offline map', () => {
  assert.match(hud, /PENDING_REVIEW_COUNT_TAG/);
  assert.match(hud, /RoadCrewTripJournal\.pendingTripCount\(activity\)/);
  assert.match(hud, /RoadCrewReportsLayer\.showPendingTripReviews\(\)/);
  assert.match(background, /renderer\.loadMap\(requested,[\s\S]*false\)/);
  assert.match(background, /renderer\.getBitmap\(\)/);
  assert.match(ui, /getPixXFromLatLon/);
  assert.match(ui, /canvas\.drawBitmap\(bitmap/);
});

test('actual lifecycle preserves long courses and prompts immediately after navigation ends', () => {
  const core = name => read(`../../OsmAnd-java/src/main/java/net/osmand/router/${name}.java`)
    .replace(/^package .*;\s*/m, '');
  const input = `${core('RoadCrewTripLifecycle')}
${core('RoadCrewValidationStopGate')}
class Checks {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static void run() {
    RoadCrewTripLifecycle course = new RoadCrewTripLifecycle();
    check(!course.endNavigation(), "No invented finished navigation");
    check(course.shouldCloseForGap(1000, 1300000), "Free driving must split on long gap");
    check(course.startNavigation(), "Start was ignored");
    check(!course.startNavigation(), "Resume split the course");
    check(!course.shouldCloseForGap(1000, 7200000), "Loading/GPS pause split course");
    check(!course.shouldCloseForGap(7200000, 1000), "Clock reset split course");
    check(course.isNavigating(), "Pause cleared navigation");
    check(course.endNavigation(), "Stop/arrival did not finish");
    check(!course.endNavigation(), "Duplicate end created second review");
    check(course.startNavigation(), "Next course was ignored");
    course.reset(); check(!course.isNavigating(), "Revocation leaked active course");
    RoadCrewValidationStopGate gate = new RoadCrewValidationStopGate();
    check(gate.update(0, true, Long.MAX_VALUE, false, 0, false, 0), "Finished course did not prompt immediately");
    check(gate.update(30000, true, 15000, true, 5, true, 100), "GPS drift blocked finished course");
    check(!gate.update(64000, false, 100, true, 0, true, 5), "Background/navigation allowed prompt");
  }
}
try { Checks.run(); System.out.println("TRIP_BOUNDARY_OK"); } catch (Throwable e) { System.out.println("TRIP_BOUNDARY_FAILED " + e); }
/exit
`;
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'], { input, encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /TRIP_BOUNDARY_OK/);
  assert.doesNotMatch(result.stdout + result.stderr, /Error:|TRIP_BOUNDARY_FAILED/);
});
