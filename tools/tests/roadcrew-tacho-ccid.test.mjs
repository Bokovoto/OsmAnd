import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';

const source = name => fileURLToPath(new URL(`../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/${name}`, import.meta.url));
test('CCID framing and activation against scripted USB replies, no Android or card', () => {
  const out = mkdtempSync(join(tmpdir(), 'roadcrew-ccid-'));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', out,
    source('RoadCrewTachoCcidProtocol.java'),
    fileURLToPath(new URL('./roadcrew-tacho-ccid/CcidTest.java', import.meta.url))]);
  const result = execFileSync('java', ['-cp', out, 'net.osmand.plus.roadcrew.tacho.CcidTest'], {encoding: 'utf8'});
  assert.match(result, /CCID checks passed/);
  console.log(result.trim());
});

test('Android transport delegates reception and activation to the tested protocol', () => {
  const code = readFileSync(source('RoadCrewTachoCcidTransport.java'), 'utf8');
  assert.match(code, /protocol\.exchange\(messageType, payload, b7, b8, b9\)/);
  assert.match(code, /protocol\.powerOn\(\)/);
  assert.match(code, /Capabilities\.parse\(/);
  assert.doesNotMatch(code, /declaredLength != available/);
});
