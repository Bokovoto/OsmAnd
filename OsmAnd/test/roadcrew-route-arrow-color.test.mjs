import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

test('route arrow color preserves the native null in night/Classic mode', () => {
  const layer = readFileSync(new URL('../src/net/osmand/plus/views/layers/RouteLayer.java', import.meta.url), 'utf8');
  const style = readFileSync(new URL('../src/net/osmand/plus/roadcrew/RoadCrewVisualStyle.java', import.meta.url), 'utf8');
  const expression = layer.match(/neonDay \? [^\r\n]+ : getDirectionArrowsColor\(\)/)?.[0];
  const dayColor = style.match(/NEON_DAY_ROUTE_ARROW_COLOR = (0x[0-9a-f]+);/i)?.[1];
  assert.ok(expression, 'the tested expression must come from the route drawing call');
  assert.ok(dayColor, 'use the actual day arrow color');

  // Execute only the production Java expression, not an Android compilation.
  // The stubs model the documented primitive getter and nullable native getter.
  const probe = `
class RoadCrewVisualStyle { static int getNeonDayRouteArrowColor() { return ${dayColor}; } }
class Probe {
  Integer nativeColor;
  Integer getDirectionArrowsColor() { return nativeColor; }
  Integer select(boolean neonDay) { return ${expression}; }
  void verify() {
    nativeColor = null;
    if (select(false) != null) throw new AssertionError("native null was lost");
    if (!Integer.valueOf(${dayColor}).equals(select(true))) throw new AssertionError("day color changed");
    nativeColor = 0xff123456;
    if (!nativeColor.equals(select(false))) throw new AssertionError("native override changed");
    nativeColor = null;
    for (int i = 0; i < 4; i++) {
      select(true);
      if (select(false) != null) throw new AssertionError("day/night transition lost null");
    }
  }
}
try { new Probe().verify(); System.out.println("ROUTE_ARROW_COLOR_OK"); } catch (Throwable error) { System.out.println("ROUTE_ARROW_COLOR_FAILED: " + error); }
/exit
`;
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'],
    { input: probe, encoding: 'utf8', timeout: 30000 });
  assert.ifError(result.error);
  const output = `${result.stdout}\n${result.stderr}`;
  assert.equal(result.status, 0, output);
  assert.match(output, /ROUTE_ARROW_COLOR_OK/, output);
  assert.doesNotMatch(output, /ROUTE_ARROW_COLOR_FAILED|Error:/, output);
});
