import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/RoadCrewTachoCardReader.java', import.meta.url), 'utf8');

test('tachograph EF SELECT uses 02/0C, not generic FCI requests', () => {
  const commands = [...source.matchAll(/new byte\[\]\{0x00, \(byte\) 0xA4, (0x\w+), (0x\w+), 0x02,/g)];
  assert.ok(commands.length > 0);
  for (const [, p1, p2] of commands) {
    assert.equal(p1, '0x02');
    assert.equal(p2, '0x0C');
  }
});
