import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// ROADMAP 328. OsmAnd's own bug (d7aca6b64b, 2026-06-24), inherited with the
// July base: canProfileUseFastRouting asked whether CAR derives from the
// profile - never true for TRUCK - so HH fast routing was never allowed for
// trucks, native library or not (measured: 246 s native, 247 s Java, 21 s in
// OsmAnd from Play, which has the upstream fix).
test('a profile derived from CAR (TRUCK) may use HH fast routing', () => {
  const source = readFileSync(new URL('../../OsmAnd/src/net/osmand/plus/settings/enums/RouteCalculationMethod.java', import.meta.url), 'utf8');
  assert.match(source,
    /mode\.isDerivedRoutingFrom\(ApplicationMode\.CAR\) \|\| mode\.isDerivedRoutingFrom\(ApplicationMode\.BICYCLE\)/,
    'the profile must be derived from CAR or BICYCLE, as in OsmAnd master');
  assert.doesNotMatch(source, /ApplicationMode\.CAR\.isDerivedRoutingFrom\(mode\)/, 'the inverted check is gone');
});
