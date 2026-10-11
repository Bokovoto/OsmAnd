import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source contracts only; GATT callbacks, permissions and storage still need a phone test.
const read = path => readFileSync(new URL(`../../OsmAnd/${path}`, import.meta.url), 'utf8');
const source = name => read(`src/net/osmand/plus/roadcrew/tacho/${name}`);
const ui = source('RoadCrewTachoBluetoothActivity.kt');
const model = source('RoadCrewTachoBluetoothModel.kt');
const transport = source('RoadCrewTachoBleTransport.kt');
const store = source('RoadCrewTachoBluetoothStore.kt');

test('Bluetooth is separate from USB and not an externally callable activity', () => {
  assert.match(source('RoadCrewTachoCardActivity.java'), /downloadButton.setOnClickListener\(v -> startDownload\(\)\)/);
  assert.match(read('res/layout/roadcrew_tacho_card_activity.xml'), /roadcrewTachoBluetoothButton/);
  assert.match(read('AndroidManifest-nightlyFree.xml'), /RoadCrewTachoBluetoothActivity"\s+android:exported="false"/);
});

test('only user-selected bonded devices, with Android runtime permission and confirmation', () => {
  assert.match(ui, /Build.VERSION.SDK_INT >= 31.*BLUETOOTH_CONNECT/);
  assert.match(ui, /adapter.bondedDevices/);
  assert.match(ui, /setPositiveButton\(R.string.roadcrew_tacho_ble_parked\).*model.start\(device\)/);
  assert.match(transport, /device.bondState != BluetoothDevice.BOND_BONDED/);
  assert.doesNotMatch(ui + transport, /createBond|setPin|setPairingConfirmation|startScan/);
});

test('rotation keeps one worker; cancel and clearing release the connection', () => {
  assert.match(model, /AndroidViewModel\(app\)/);
  assert.match(model, /worker\?\.isAlive == true/);
  assert.match(model, /finally\s*\{\s*connection.close\(\)/);
  assert.match(model, /override fun onCleared\(\) \{ cancel\(\) \}/);
  assert.match(model, /worker\?\.interrupt\(\)/);
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

test('structure and read-back checked before visibility, no card mark in BLE flow', () => {
  const validated = store.indexOf('validateAndGetCardNumber');
  const insert = store.indexOf('resolver.insert');
  const readback = store.indexOf('Card file read-back mismatch');
  const published = store.indexOf('put(MediaStore.Downloads.IS_PENDING, 0)');
  assert.ok(validated >= 0 && validated < insert && readback < published);
  assert.match(store, /resolver.delete\(uri/);
  assert.match(source('RoadCrewTachoCardActivity.java'), /MediaStore.Downloads.IS_PENDING \+ " = 0"/);
  assert.match(store, /File.createTempFile\("bluetooth-", "\.part"/);
  assert.match(store, /RoadCrewTachoFileNames.unused/);
  assert.doesNotMatch(model + transport + store, /markDownloaded|UPDATE BINARY|WRITE BINARY/);
  assert.match(ui, /RoadCrewTachoRemainingActivity.open\(this, it.uri, it.takenAt\)/);
  assert.ok(model.indexOf('RoadCrewTachoReminder.remember') > model.indexOf('RoadCrewTachoBluetoothStore.save'));
});

test('Bluetooth strings are localized, not hard-coded status labels', () => {
  const keys = [...new Set([...(ui + model).matchAll(/R\.string\.(roadcrew_tacho_ble_[a-z0-9_]+)/g)].map(m => m[1]))];
  assert.ok(keys.length >= 15);
  for (const path of ['res/values/strings.xml', 'src/nightlyFree/res/values-bg/roadcrew_strings.xml']) {
    const strings = read(path);
    assert.deepEqual(keys.filter(key => !strings.includes(`name="${key}"`)), []);
  }
});
