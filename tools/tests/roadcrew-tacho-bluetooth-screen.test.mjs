import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source contracts only; GATT callbacks, permissions and storage still need a phone test.
// ROADMAP 411: Bluetooth moved onto the card screen as a source beside the USB reader, one
// "Свали картата" button, the tachograph found and paired by itself (Galin, 11.10.2026).
const read = path => readFileSync(new URL(`../../OsmAnd/${path}`, import.meta.url), 'utf8');
const source = name => read(`src/net/osmand/plus/roadcrew/tacho/${name}`);
const screen = source('RoadCrewTachoCardActivity.java');
const model = source('RoadCrewTachoBluetoothModel.kt');
const finder = source('RoadCrewTachoBleFinder.kt');
const transport = source('RoadCrewTachoBleTransport.kt');
const store = source('RoadCrewTachoBluetoothStore.kt');

test('Bluetooth is a source on the card screen, not a separate activity', () => {
  assert.match(screen, /private enum Source \{ USB, BLUETOOTH \}/);
  assert.match(screen, /if \(source == Source\.BLUETOOTH\) \{\s*startBluetooth\(\);\s*\} else \{\s*startDownload\(\);/);
  const layout = read('res/layout/roadcrew_tacho_card_activity.xml');
  assert.match(layout, /roadcrewTachoSourceUsb/);
  assert.match(layout, /roadcrewTachoSourceBluetooth/);
  assert.doesNotMatch(layout, /roadcrewTachoBluetoothButton/);
  assert.doesNotMatch(read('AndroidManifest-nightlyFree.xml'), /RoadCrewTachoBluetoothActivity/);
});

test('only tachographs, Android runtime permission, Android pairing dialog never bypassed', () => {
  assert.match(screen, /Manifest\.permission\.BLUETOOTH_SCAN, Manifest\.permission\.BLUETOOTH_CONNECT/);
  assert.match(screen, /requestPermissions\(missing\.toArray/);
  assert.match(finder, /RoadCrewTachoBleChoice\.isTachograph\(name\)/);
  assert.match(finder, /device\.createBond\(\)/);
  assert.match(finder, /ACTION_BOND_STATE_CHANGED/);
  assert.doesNotMatch(finder + model + transport + screen, /setPin|setPairingConfirmation|setPasskey/);
  assert.match(transport, /device.bondState != BluetoothDevice.BOND_BONDED/);
  assert.match(model, /RoadCrewTachoBleChoice\.decide\(bonded, heard\)/);
});

test('a dropped start is tried again, only while no card data has come', () => {
  assert.match(model, /RoadCrewTachoBleChoice\.again\(attempt, tracker\.fileId\(\) >= 0, e\.message\)/);
  assert.match(model, /Thread\.sleep\(RoadCrewTachoBleChoice\.PAUSE_MILLIS\)/);
});

test('rotation keeps one worker; cancel and clearing release the connection', () => {
  assert.match(model, /AndroidViewModel\(app\)/);
  assert.match(model, /worker\?\.isAlive == true/);
  assert.match(model, /finally\s*\{\s*connection.close\(\)/);
  assert.match(model, /override fun onCleared\(\) \{ cancel\(\) \}/);
  assert.match(model, /worker\?\.interrupt\(\)/);
  assert.match(screen, /cancelButton\.setOnClickListener\(v -> ble\.cancel\(\)\)/);
  assert.match(screen, /FLAG_KEEP_SCREEN_ON/);
});

test('GATT discovers UUIDs, uses indications and bounded flow control', () => {
  for (const uuid of ['eef90782-55dd-4388-b80b-695aba7a69b5', '29d3a479-1592-47df-80a4-afa742d369bb', 'db9c4128-bff3-41fe-a306-fb6f9a8aeb2d']) {
    assert.ok(transport.includes(uuid));
  }
  assert.match(transport, /discoverServices\(\)/);
  assert.match(transport, /ENABLE_INDICATION_VALUE/);
  assert.match(transport, /ArrayBlockingQueue<Event>\(128\)/);
  // The window and the grant/answer order live in RoadCrewTachoVuDownload.Credits, tested by
  // behaviour in VuDownloadTest (ROADMAP 410); the transport must route every segment through it.
  assert.match(transport, /while \(!credits\.maySend\(\)\)/);
  assert.match(transport, /credits\.sent\(\)/);
  assert.match(transport, /credits\.received\(\)/);
  assert.match(transport, /credits\.grantDue\(\)/);
  assert.match(transport, /credits\.granted\(\)\s*\n\s*write\(credit/);
});

test('progress names the part from the stream; structure and read-back before visibility', () => {
  assert.match(model, /tracker\.data\(bytes, offset, length\)/);
  assert.match(screen, /fileLabel\(s\.getFileId\(\), s\.getSecondGeneration\(\)\)/);
  const validated = store.indexOf('validateAndGetCardNumber');
  const insert = store.indexOf('resolver.insert');
  const readback = store.indexOf('Card file read-back mismatch');
  const published = store.indexOf('put(MediaStore.Downloads.IS_PENDING, 0)');
  assert.ok(validated >= 0 && validated < insert && readback < published);
  assert.match(store, /resolver.delete\(uri/);
  assert.match(screen, /MediaStore.Downloads.IS_PENDING \+ " = 0"/);
  assert.match(store, /File.createTempFile\("bluetooth-", "\.part"/);
  assert.match(store, /RoadCrewTachoFileNames.unused/);
  assert.doesNotMatch(model + transport + store + finder, /markDownloaded|UPDATE BINARY|WRITE BINARY/);
  assert.ok(model.indexOf('RoadCrewTachoReminder.remember') > model.indexOf('RoadCrewTachoBluetoothStore.save'));
});

test('each GATT characteristic uses its supported write type and still waits for completion', () => {
  assert.match(transport, /for \(c in listOf\(data, credit\)\) \{\s*configureCharacteristic\(c\)/);
  assert.match(transport, /RoadCrewTachoVuDownload\.useWriteWithoutResponse\(/);
  for (const property of ['PROPERTY_INDICATE', 'PROPERTY_WRITE_NO_RESPONSE', 'PROPERTY_WRITE']) {
    assert.ok(transport.includes(`BluetoothGattCharacteristic.${property} != 0`));
  }
  assert.match(transport, /c\.writeType = if \(withoutResponse\) BluetoothGattCharacteristic\.WRITE_TYPE_NO_RESPONSE\s*else BluetoothGattCharacteristic\.WRITE_TYPE_DEFAULT/);
  const write = transport.slice(transport.indexOf('private fun write('), transport.indexOf('private fun await('));
  assert.doesNotMatch(write, /writeType\s*=/);
  assert.match(write, /await\("write", c\.uuid, 10_000\)/);
  assert.match(transport, /properties=0x/);
  assert.match(transport, /cccd=\$hasCccd/);
});

test('Bluetooth strings are localized, not hard-coded status labels', () => {
  const keys = [...new Set([...screen.matchAll(/R\.string\.(roadcrew_tacho_(?:ble|source|tachograph)[a-z0-9_]*)/g)].map(m => m[1]))];
  assert.ok(keys.length >= 25);
  const english = read('res/values/strings.xml') + read('res/values/roadcrew_tacho_strings.xml');
  const bulgarian = read('src/nightlyFree/res/values-bg/roadcrew_strings.xml') + read('res/values-bg/roadcrew_tacho_strings.xml');
  for (const strings of [english, bulgarian]) {
    assert.deepEqual(keys.filter(key => !strings.includes(`name="${key}"`)), []);
  }
  assert.ok(english.includes('name="roadcrew_tacho_file_050e"') && bulgarian.includes('name="roadcrew_tacho_file_050e"'));
});

test('after a failure "Опитай пак" goes to the same tachograph, without a new search', () => {
  assert.match(model, /fun retry\(\) \{[\s\S]*?if \(address != null\) startWith\(address\) else start\(\)/);
  assert.match(model, /State\(Step\.FAILED, device = chosen\?\.second, address = chosen\?\.first/);
  assert.match(screen, /ble\.retry\(\)/);
  assert.match(screen, /failed \? R\.string\.roadcrew_tacho_ble_retry_button : R\.string\.roadcrew_tacho_download/);
  assert.match(screen, /getString\(R\.string\.roadcrew_tacho_ble_nearest, /);
});

test('pairing texts give the tachograph menu with Водач 1, and no other app by name', () => {
  const value = (xml, key) => (xml.match(new RegExp(`name="${key}">([^<]*)<`)) || [])[1] || '';
  const bg = read('res/values-bg/roadcrew_tacho_strings.xml');
  const en = read('res/values/roadcrew_tacho_strings.xml');
  for (const key of ['ble_hint', 'ble_not_paired_detail', 'ble_pairing_body', 'ble_pairing_failed_body']) {
    assert.ok(value(bg, `roadcrew_tacho_${key}`).includes('Меню → Водач 1 → Bluetooth → Свързване'), key);
    assert.ok(value(en, `roadcrew_tacho_${key}`).includes('Menu → Driver 1 → Bluetooth → Connect'), key);
  }
  for (const xml of [bg, en]) {
    assert.doesNotMatch(xml, /Tacho Manager/);
  }
});
