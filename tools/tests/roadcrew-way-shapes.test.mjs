import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';

// Road shapes from the phone, 01.10.2026. Galin asked whether the phone draws
// the roads; it did not - the server had asked phones for 2090 shapes and
// verified none, because the phone's reply never carried measureAlgorithm and
// the server refused every shape for it, silently. Then his choices: send them
// all, in portions, and send them first - before the course.

const source = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const UPLOADER = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewMapObservationUploader.java';
const JOURNAL = '../../OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewTripJournal.kt';
// The server lives beside this repository on the build machine; elsewhere the
// contract checks are skipped rather than guessed at.
const SERVER_DESCRIPTORS = new URL('../../../backend/roadcrew-api/src/way-geometry-descriptors.ts', import.meta.url);
const SERVER_INDEX = new URL('../../../backend/roadcrew-api/src/index.ts', import.meta.url);

const body = (text, start, end) => {
  const from = text.indexOf(start);
  assert.notEqual(from, -1, `${start} not found`);
  const to = end ? text.indexOf(end, from + start.length) : -1;
  return text.slice(from, to === -1 ? undefined : to);
};
const fields = (text, pattern) => new Set([...text.matchAll(pattern)].map(match => match[1]));

test('the shape reply carries every field the server reads from it', { skip: !existsSync(SERVER_DESCRIPTORS) }, () => {
  const server = readFileSync(SERVER_DESCRIPTORS, 'utf8').replaceAll('\u0000', '');
  const wanted = fields(server, /descriptor\.(\w+)/g);
  const sent = fields(body(source(UPLOADER), 'private static JSONObject shapeJson(', '\n\t}'), /json\.put\("(\w+)"/g);
  for (const field of wanted) {
    assert.ok(sent.has(field), `the server reads descriptor.${field}; the phone never sends it`);
  }
});

test('every shape asked for is sent, in portions the server takes', () => {
  const uploader = source(UPLOADER);
  const reply = body(uploader, 'private static void uploadRequestedDescriptors(', '\n\t}\n');
  assert.match(reply, /SHAPES_PER_PORTION/, 'in portions');
  assert.match(uploader, /SHAPES_PER_PORTION = 20;/);
  const lookup = body(source(JOURNAL), 'fun wayShapes(', '\n    }\n');
  assert.doesNotMatch(lookup, /\.take\(/, 'all of them looked up, not the first twenty');
});

test('the reply says how the phone measures', () => {
  const shape = body(source(UPLOADER), 'private static JSONObject shapeJson(', '\n\t}');
  assert.match(shape, /json\.put\("measureAlgorithm", RoadCrewWayCanonical\.MEASURE_ALGORITHM\)/);
});
