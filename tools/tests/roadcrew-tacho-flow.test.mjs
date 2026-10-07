import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const tacho = (name) => fileURLToPath(new URL(`../../OsmAnd/src/net/osmand/plus/roadcrew/tacho/${name}`, import.meta.url));

// ROADMAP 329 (Codex's Test 118 review, P1): read, store, mark - with the
// simulated card from roadcrew-tacho-download.
test('driver card session: 6281 is stored but never marked; automation is dev-only', () => {
  const output = mkdtempSync(join(tmpdir(), 'roadcrew-tacho-flow-'));
  execFileSync('javac', ['--release', '17', '-encoding', 'UTF-8', '-d', output,
    tacho('RoadCrewTachoDownloadDate.java'), tacho('RoadCrewTachoCardDownload.java'), tacho('RoadCrewTachoDownloadFlow.java'),
    fileURLToPath(new URL('./roadcrew-tacho-download/CardDownloadTest.java', import.meta.url)),
    fileURLToPath(new URL('./roadcrew-tacho-flow/DownloadFlowTest.java', import.meta.url))], {stdio: 'pipe'});
  const result = execFileSync('java', ['-cp', output, 'DownloadFlowTest'], {encoding: 'utf8'});
  assert.match(result, /\d+ download flow checks passed/);
  console.log(result.trim());
});

test('the screen goes through the flow and reads adb extras only when automation is accepted', () => {
  const activity = readFileSync(tacho('RoadCrewTachoCardActivity.java'), 'utf8');
  assert.match(activity, /RoadCrewTachoDownloadFlow\.run\(/, 'the Activity runs the tested flow');
  assert.doesNotMatch(activity, /RoadCrewTachoCardDownload\.markDownloaded\(/, 'no marking outside the flow');
  const handle = activity.slice(activity.indexOf('private void handleIntent'), activity.indexOf('protected void onStart'));
  assert.match(handle, /acceptsAutomation\(/, 'handleIntent asks whether this build accepts automation');
  assert.match(handle, /automation && intent\.getBooleanExtra\(EXTRA_DOWNLOAD/, 'download extra gated');
  assert.match(handle, /automation && intent\.getBooleanExtra\(EXTRA_TRACE/, 'trace extra gated');
});

// Galin, 27.09: "I never asked for a lock to this reader. Once Android
// recognises it, it must work with our app." Any USB smart-card reader
// (CCID, interface class 11) opens RoadCrew and is used - not one model.
test('CCID discovery has no vendor allowlist (discovery does not prove successful reading)', () => {
  const filter = readFileSync(fileURLToPath(new URL('../../OsmAnd/res/xml/roadcrew_tacho_usb_filter.xml', import.meta.url)), 'utf8');
  const entries = filter.replace(/<!--[\s\S]*?-->/g, '').match(/<usb-device[^>]*>/g) ?? [];
  assert.deepEqual(entries, ['<usb-device class="11" />'], 'Android offers RoadCrew for every CCID reader');
  const activity = readFileSync(tacho('RoadCrewTachoCardActivity.java'), 'utf8');
  assert.doesNotMatch(activity, /getVendorId\(\)|getProductId\(\)|READER_VENDOR_ID|READER_PRODUCT_ID/,
    'the screen does not pick a reader by its maker or model');
  const find = activity.slice(activity.indexOf('private void findReader('), activity.indexOf('if (found == null) {\n\t\t\treader = Reader.NONE;'));
  assert.match(find, /RoadCrewTachoCcidTransport\.isCardReader\(/, 'it looks for the CCID interface');
  const transport = readFileSync(tacho('RoadCrewTachoCcidTransport.java'), 'utf8');
  assert.match(transport, /static boolean isCardReader\(@NonNull UsbDevice device\)/);
});
