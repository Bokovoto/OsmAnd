import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Galin, 06.10.2026, in the simulation: the navigation and RoadCrew spoke at
// once - "Трябва да не се препокриват 2 оповестявания"; asked, he chose
// "Изчаква, после се казва": whichever comes second waits and follows the
// first; a camera warning that no longer applies is dropped.

const plus = new URL('../../OsmAnd/src/net/osmand/plus/', import.meta.url);
const read = path => readFileSync(new URL(path, plus), 'utf8').replace(/\r\n/g, '\n');
const between = (text, start, end) => text.slice(text.indexOf(start), text.indexOf(end, text.indexOf(start) + 1));

test('the queue: waits for the other voice, keeps the order, drops what no longer applies - plain Java', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-voice-order-'));
  const code = fileURLToPath(new URL('roadcrew/RoadCrewSpeechQueue.java', plus));
  const harness = fileURLToPath(new URL('./roadcrew-voice-order/SpeechQueueTest.java', import.meta.url));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output, code, harness], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.SpeechQueueTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ speech order checks passed/);
  console.log(result.trim());
});

test('RoadCrew waits while the navigation speaks', () => {
  const voice = read('roadcrew/RoadCrewVoiceAlerts.java');
  const speak = between(voice, 'private void speak(@NonNull String message, @NonNull BooleanSupplier stillTrue)', '\n\t}\n');
  assert.match(speak, /queue\.add\(/, 'every warning goes through the queue');
  // The one place that says anything is the queue's drain - nothing bypasses it.
  assert.equal((voice.match(/textToSpeech\.speak\(/g) ?? []).length, 1);
  assert.match(between(voice, 'private void drain()', '\n\t}\n'), /textToSpeech\.speak\(/);
  assert.match(voice, /navigationSpeaking\(\)/);
  const asked = between(voice, 'private boolean navigationSpeaking()', '\n\t}\n');
  assert.match(asked, /getVoiceRouter\(\)/, 'the navigation\'s own player is asked');
  assert.match(asked, /\.getPlayer\(\)/);
  assert.match(asked, /\.isSpeaking\(\)/);
  assert.match(voice, /UtteranceProgressListener/, 'RoadCrew knows when its own sentence ends');
  const camera = between(voice, 'void checkCamera(', '\n\t}\n');
  assert.match(camera, /stillAhead\(/, 'a camera warning waits only while the camera is ahead');
});

test('the navigation waits while RoadCrew speaks - both players', () => {
  const player = read('voice/CommandPlayer.java');
  assert.match(player, /public boolean isSpeaking\(\)/);
  for (const name of ['voice/JsTtsCommandPlayer.java', 'voice/JsMediaCommandPlayer.java']) {
    const source = read(name);
    assert.match(source, /public boolean isSpeaking\(\)/, `${name} says when it speaks`);
    const play = between(source, 'public synchronized List<String> playCommands(', '\n\t}\n');
    assert.match(play, /RoadCrewRoadAlerts\.isSpeaking\(\)/, `${name} waits for RoadCrew`);
  }
  const alerts = read('roadcrew/RoadCrewRoadAlerts.java');
  assert.match(alerts, /public static boolean isSpeaking\(\)/);
});
