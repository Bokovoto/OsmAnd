import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Galin, 06.10.2026, test.131 in the route simulation: "казва само "наблизо"
// без камера". Measured on his phone (SM-A536B, com.google.android.tts
// 20260817.01, voice bg-bg-language) with synthesizeToFile - nothing played:
// "ка̀мера наблизо.", "Ка̀мера наблизо.", "ка́мера наблизо." and "наблизо."
// come out as the same audio byte for byte; "ка̀мера." alone is silence; the
// report "ка̀мера по маршрута след 500 метра." is 0.41 s shorter than the
// same without the mark. The voice drops a word that carries an accent mark,
// grave or acute - so since test.78 (31.08) a camera report was said without
// "камера". Source contract only: no Android runtime is simulated here.

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const voice = read('../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewVoiceAlerts.java');

test('nothing RoadCrew says carries an accent mark - the voice would drop the word', () => {
  const marked = voice.split('\n')
    .map((line, index) => `${index + 1}: ${line.trim()}`)
    .filter(line => /\p{Mn}/u.test(line));
  assert.deepEqual(marked, []);
});
