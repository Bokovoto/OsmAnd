import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

/**
 * The release notes reached the phone with a letter destroyed mid-word - the
 * driver saw "ко��а" in What's New while every file in the repository held
 * clean Bulgarian.
 *
 * The text was never wrong. It was broken on the way in: the reader decoded
 * each network chunk on its own, and a Cyrillic letter is two bytes. A letter
 * that straddles a chunk boundary is handed to two separate decoders, each
 * seeing half a character and writing the replacement mark for it.
 *
 * It follows that the fault is invisible in every short response and fires at
 * exactly one offset in a long one, which is why one word broke and the rest
 * of the notes were perfect. A test that reads a small body would pass against
 * the broken code, so this one places the letter deliberately on the boundary.
 */
const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');

/** The method's own source, so the test runs production code rather than a copy. */
function block(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, signature);
  const open = source.indexOf('{', start);
  let depth = 1;
  let end = open + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  assert.equal(depth, 0, signature);
  return source.slice(start, end).replaceAll('@NonNull ', '').replaceAll('@Nullable ', '');
}

function runProbe(probe, marker) {
  const result = spawnSync(process.platform === 'win32' ? 'jshell.exe' : 'jshell',
    ['--execution', 'local', '--feedback', 'concise', '-'],
    { input: probe, encoding: 'utf8', timeout: 40000 });
  assert.ifError(result.error);
  const output = `${result.stdout}\n${result.stderr}`;
  assert.equal(result.status, 0, output);
  assert.ok(output.includes(marker), output);
  assert.doesNotMatch(output, /PROBE_FAILED|Error:/, output);
}

test('release notes survive a multi-byte letter that falls on a read boundary', () => {
  const updater = read('../src/net/osmand/plus/roadcrew/RoadCrewAppUpdater.java');
  runProbe(`
import java.io.*;
import java.nio.charset.StandardCharsets;
class Probe {
  ${block(updater, 'private static String readFully')}

  static void check(boolean ok, String message) {
    if (!ok) { System.out.println("PROBE_FAILED " + message); throw new AssertionError(message); }
  }

  static void run() throws Exception {
    // The buffer is 16 KiB. Padding of 16383 single-byte characters puts the
    // first byte of the next letter at the last position of the first read,
    // and its second byte at the start of the next - the one arrangement the
    // old code cannot survive.
    //
    // The letters are written as code points rather than as Cyrillic source,
    // so that the probe itself cannot be damaged by the console encoding it
    // is piped through. A test for a decoding fault must not depend on one.
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 16383; i++) text.append('.');
    for (int i = 0; i < 5; i++) text.append((char) (0x043A + i));
    String expected = text.toString();

    byte[] body = expected.getBytes(StandardCharsets.UTF_8);
    check(body.length > 16 * 1024, "fixture too short to cross a read boundary");
    check((body[16383] & 0xC0) == 0xC0, "fixture does not start a letter on the boundary");

    // A stream that hands over at most one buffer at a time, as a socket does.
    String actual = readFully(new ByteArrayInputStream(body));

    check(actual.indexOf((char) 0xFFFD) < 0, "decoded text contains a replacement character");
    check(actual.equals(expected), "decoded text differs from what was sent");
    System.out.println("PROBE_OK");
  }
}
Probe.run();
`, 'PROBE_OK');
});
