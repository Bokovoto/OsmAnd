import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';

const base = new URL('../../OsmAnd/src/net/osmand/plus/', import.meta.url);
const read = path => readFileSync(new URL(path, base), 'utf8').replace(/\r\n/g, '\n');

test('camera cache invalidation rejects in-flight results and retries failed reads', () => {
  const out = mkdtempSync(join(tmpdir(), 'rc-camera-cache-'));
  try {
    execFileSync('javac', ['--release', '17', '-d', out,
      fileURLToPath(new URL('roadcrew/RoadCrewCameraCache.java', base)),
      fileURLToPath(new URL('./roadcrew-cameras/CacheTest.java', import.meta.url))]);
    assert.match(execFileSync('java', ['-cp', out, 'net.osmand.plus.roadcrew.CacheTest'], {encoding:'utf8'}), /8 camera cache checks passed/);
  } finally { rmSync(out, {recursive: true, force: true}); }
});

test('camera audio uses foreground and service locations, not drawing', () => {
  const provider = read('OsmAndLocationProvider.java');
  const service = provider.slice(provider.indexOf('public void setLocationFromService('), provider.indexOf('public void setLocationFromSimulation('));
  const foreground = provider.slice(provider.indexOf('private void setLocation(@Nullable'), provider.indexOf('public void ensureLatestLocation('));
  for (const branch of [service, foreground]) assert.match(branch, /RoadCrewRoadAlerts\.onLocation\(app, updatedLocation\)/);
  const layer = read('roadcrew/RoadCrewReportsLayer.java');
  assert.doesNotMatch(layer, /new RoadCrewVoiceAlerts|voiceAlerts\.check|voiceAlerts\.shutdown/);
  const controller = read('roadcrew/RoadCrewRoadAlerts.java');
  assert.match(controller, /voiceAlerts\.checkCamera\(/);
  assert.match(controller, /runInUIThread/);
});

test('master mute is checked before camera and zone deduplication', () => {
  const voice = read('roadcrew/RoadCrewVoiceAlerts.java');
  for (const name of ['checkCamera', 'checkCameraZone']) {
    const start = voice.indexOf(`void ${name}(`);
    const method = voice.slice(start, voice.indexOf('\n\t}', start));
    assert.match(method, /VOICE_MUTE\.get\(\)/);
    assert.ok(method.indexOf('VOICE_MUTE') < method.indexOf('.spoken('));
  }
  assert.match(voice, /VOICE_MUTE\.addListener\(muteListener\)/);
  assert.match(voice, /textToSpeech\.stop\(\)/, 'muting also stops an utterance already playing');
});

test('map changes invalidate both camera caches, known road forbids straight fallback', () => {
  const source = read('roadcrew/RoadCrewCamerasSource.java');
  for (const event of ['onMapsIndexed', 'onReaderIndexed', 'onReaderClosed']) assert.match(source, new RegExp(event));
  assert.match(source, /truckCache\.invalidate\(\)/);
  assert.match(source, /viewCache\.invalidate\(\)/);
  assert.match(read('roadcrew/RoadCrewRoadAlerts.java'), /RoadCrewCameras\.nextAhead\(/);
  assert.match(read('roadcrew/RoadCrewCameras.java'), /knownLine != null[^]*?return nextAlong/);
});
