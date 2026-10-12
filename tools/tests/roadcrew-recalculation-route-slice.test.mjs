import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const provider = read('../../OsmAnd/src/net/osmand/plus/routing/RouteProvider.java');
const result = read('../../OsmAnd/src/net/osmand/plus/routing/RouteCalculationResult.java');
const start = provider.indexOf('if (params.previousToRecalculate != null && params.onlyStartPointChanged && !communityRanking)');
assert.ok(start >= 0);
const reuse = provider.slice(start, provider.indexOf('boolean complex =', start));

// Source-contract tests: no Android classpath/Gradle emulation. The overload
// already translates per-location entries into the deduplicated segment list.
test('recalculation asks the existing location-index overload for the remaining route', () => {
  assert.match(reuse, /getOriginalRoute\(currentRoute\)/);
  assert.doesNotMatch(reuse, /\.subList\(currentRoute,/,
    'a point index is not an index into the unique segment list');
  assert.doesNotMatch(reuse, /currentRoute < originalRoute\.size\(\)/);
});

test('recalculation bounds its point index against route locations', () => {
  assert.match(reuse, /currentRoute >= 0/);
  assert.match(reuse, /currentRoute < .*getImmutableAllLocations\(\)\.size\(\)/);
});

test('the existing overload indexes before deduplicating adjacent segment references', () => {
  const body = result.slice(result.indexOf('public List<RouteSegmentResult> getOriginalRoute(int startIndex, int endIndex,'),
    result.indexOf('* PREPARATION'));
  assert.match(body, /list\.add\(segments\.get\(startIndex\)\)/);
  assert.match(body, /segments\.get\(i - 1\) != segments\.get\(i\)/);
  assert.match(result, /locations\.add\(n\);[\s\S]*?segmentsToPopulate\.add\(s\);/,
    'the lookup list contains a segment reference for each location');
});
