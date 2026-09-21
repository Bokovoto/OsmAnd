import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source-contract checks only. The reading side of the cutover: the phone is
// sent roads - a way, a direction and the proven stretches in metres - and
// measures the road itself, because it already holds the geometry.

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');
const CELLS = '../../OsmAnd-java/src/main/java/net/osmand/router/RoadCrewCellPreferences.java';

test('the hint stays a hint: one, or the ordinary cost, never less', () => {
  const source = read(CELLS);
  const cost = source.slice(source.indexOf('public double costFactor('),
    source.indexOf('private Measures measuresFor('));
  assert.match(cost, /return 1;/, 'a proven stretch costs one');
  assert.match(cost, /RoadCrewRoutePreferences\.ORDINARY_COST_FACTOR/,
    'everything else keeps the ordinary cost');
  // Every exit, not a keyword search: prose about what the code may not do
  // must not be mistaken for the code doing it.
  const returns = [...cost.matchAll(/return ([^;]+);/g)].map(match => match[1].trim());
  assert.deepEqual([...new Set(returns)].sort(),
    ['1', 'RoadCrewRoutePreferences.ORDINARY_COST_FACTOR'],
    `costFactor may only return those two, found ${returns.join(', ')}`);
});

test('the phone measures the road with the same code that recorded it', () => {
  const source = read(CELLS);
  assert.match(source, /RoadCrewWayCanonical\.canonicalise\(xs, ys\)/);
  assert.match(source, /RoadCrewWayCanonical\.canonicalMeasure\(raw\[index\], canonical\)/,
    'the recording side converts measures the same way; two conversions would drift');
  assert.doesNotMatch(source, /fromLatitude|toLatitude/,
    'no coordinates travel: the geometry is already on the phone');
});

test('a stretch is dropped when it stops being proven, without being told', () => {
  const source = read(CELLS);
  assert.match(source, /public RoadCrewCellPreferences fresh\(long now\)/,
    'the phone expires entries by itself, offline');
  const parse = source.slice(source.indexOf('public static RoadCrewCellPreferences parse('),
    source.indexOf('public RoadCrewCellPreferences fresh('));
  assert.match(parse, /run\.expiresAt <= now/, 'already expired on arrival is not used');
  assert.match(parse, /run\.expiresAt > doc\.generatedAt \+ MAX_TILE_AGE_MILLIS/,
    'and a document cannot promise more than the window allows');
  assert.match(source, /run\.expiresAt <= now.*continue|if \(run\.forward != forward \|\| run\.expiresAt <= now\)/s,
    'an entry that expires between tiles is not used either');
});

test('only a tile of the agreed kind is believed', () => {
  const parse = read(CELLS);
  assert.match(parse, /schemaVersion != 2/);
  assert.match(parse, /EVIDENCE_MODEL\.equals\(doc\.evidenceModel\)/);
  assert.match(parse, /ROUTING_EFFECT\.equals\(doc\.routingEffect\)/);
  assert.match(parse, /RoadCrewRoutePreferences\.POLICY\.equals\(doc\.routingPreferencePolicy\)/,
    'the policy the old path checks, checked here too');
  assert.match(parse, /now - doc\.generatedAt > MAX_TILE_AGE_MILLIS/, 'and not a stale tile');
});

test('direction is part of the answer, not an afterthought', () => {
  const source = read(CELLS);
  assert.match(source, /boolean forward = measures\.reversed \? !rawForward : rawForward/,
    'the canonical direction, the same rule the recording side uses');
  assert.match(source, /run\.forward != forward/,
    'a stretch proven one way says nothing about the other');
});

test('both sources rank the same road, and neither can go below one', () => {
  const planner = readFileSync(new URL(
    '../../OsmAnd-java/src/main/java/net/osmand/router/BinaryRoutePlanner.java',
    import.meta.url), 'utf8');
  const call = planner.slice(planner.indexOf('double roadCrewFactor = Math.min('),
    planner.indexOf('return obstacle + heightObstacle + distTimeOnRoadToPass * roadCrewFactor;'));
  assert.match(call, /roadCrewPreferenceMatcher\.costFactor/, 'the old segments still rank');
  assert.match(call, /roadCrewCellMatcher\.costFactor/, 'and the cells rank beside them');
  assert.match(call, /Math\.min\(/,
    'a road proven by either costs one; the minimum of two hints is still a hint');
  const context = readFileSync(new URL(
    '../../OsmAnd-java/src/main/java/net/osmand/router/RoutingContext.java',
    import.meta.url), 'utf8');
  assert.equal((context.match(/roadCrewCellMatcher = /g) || []).length, 2,
    'both constructors build it, or one route kind would silently ignore the evidence');
});
