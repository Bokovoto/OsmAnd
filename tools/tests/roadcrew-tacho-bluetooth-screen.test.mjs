import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

// Source contracts only; GATT callbacks, permissions and storage still need a phone test.
// ROADMAP 411: Bluetooth moved onto the card screen as a source beside the USB reader.
// ROADMAP 439 (Galin, 03.11.2026): pairing is its own place - the "Тахограф" section with a
// wizard - and "Свали картата" only downloads, from the tachograph shown on the screen.
const read = path => readFileSync(new URL(`../../OsmAnd/${path}`, import.meta.url), 'utf8');
const source = name => read(`src/net/osmand/plus/roadcrew/tacho/${name}`);
const screen = source('RoadCrewTachoCardActivity.java');
const model = source('RoadCrewTachoBluetoothModel.kt');
const section = source('RoadCrewTachoTachographActivity.java');
const pairing = source('RoadCrewTachoPairModel.kt');
const finder = source('RoadCrewTachoBleFinder.kt');
const transport = source('RoadCrewTachoBleTransport.kt');
const store = source('RoadCrewTachoBluetoothStore.kt');

const value = (xml, key) => (xml.match(new RegExp(`name="${key}">([^<]*)<`)) || [])[1] || '';
const bg = read('res/values-bg/roadcrew_tacho_strings.xml');
const en = read('res/values/roadcrew_tacho_strings.xml');

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
  assert.doesNotMatch(finder + model + pairing + section + transport + screen, /setPin|setPairingConfirmation|setPasskey/);
  assert.match(transport, /device.bondState != BluetoothDevice.BOND_BONDED/);
  assert.match(model, /RoadCrewTachoBleChoice\.nearbyPaired\(bonded, heard\)/);
  assert.match(pairing, /RoadCrewTachoBleChoice\.unpaired\(bonded, heard\)/);
});

test('a dropped start is tried again, only while no card data has come', () => {
  assert.match(model, /RoadCrewTachoBleChoice\.again\(attempt, tracker\.fileId\(\) >= 0, e\.message\)/);
  assert.match(model, /Thread\.sleep\(RoadCrewTachoBleChoice\.PAUSE_MILLIS\)/);
});

test('rotation keeps one worker; cancel and clearing release the connection', () => {
  assert.match(model, /AndroidViewModel\(app\)/);
  assert.match(model, /worker\?\.isAlive == true/);
  assert.match(model, /finally\s*\{\s*connection.close\(\)/);
  assert.match(model, /override fun onCleared\(\) \{ cancel\(\); scanner\?\.interrupt\(\) \}/);
  assert.match(model, /worker\?\.interrupt\(\)/);
  assert.match(pairing, /AndroidViewModel\(app\)/);
  assert.match(pairing, /override fun onCleared\(\) \{ worker\?\.interrupt\(\) \}/);
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
  const pattern = /R\.string\.(roadcrew_tacho_(?:ble|source|tachograph|section|wizard)[a-z0-9_]*)/g;
  const keys = [...new Set([...(screen + section).matchAll(pattern)].map(m => m[1]))];
  assert.ok(keys.length >= 40);
  const english = read('res/values/strings.xml') + read('res/values/roadcrew_tacho_strings.xml');
  const bulgarian = read('src/nightlyFree/res/values-bg/roadcrew_strings.xml') + read('res/values-bg/roadcrew_tacho_strings.xml');
  for (const strings of [english, bulgarian]) {
    assert.deepEqual(keys.filter(key => !strings.includes(`name="${key}"`)), []);
  }
  assert.ok(english.includes('name="roadcrew_tacho_file_050e"') && bulgarian.includes('name="roadcrew_tacho_file_050e"'));
  assert.doesNotMatch(section, /setText\("[^"]/);
});

test('after a failure "Опитай пак" goes to the same tachograph, without a new search', () => {
  assert.match(model, /fun retry\(\) \{[\s\S]*?downloadFrom\(address\)/);
  assert.match(model, /State\(Step\.FAILED, device = chosen\?\.second, address = chosen\?\.first/);
  assert.match(screen, /ble\.retry\(\)/);
  assert.match(screen, /failed \? R\.string\.roadcrew_tacho_ble_retry_button : R\.string\.roadcrew_tacho_download/);
});

// Galin, 03.11.2026: "Bluetooth descriptor failed (133)" after a good pairing is a VDO with
// "Данни от VDO" or "ИТС издаване" not set to "Да".
test('code 133 after pairing: a VDO is shown both settings, any other tachograph no VDO menu', () => {
  assert.ok(screen.includes('"descriptor failed (133)"'));
  assert.ok(screen.includes('R.string.roadcrew_tacho_ble_refused_title'));
  assert.ok(screen.includes('R.string.roadcrew_tacho_ble_refused_body_vdo'));
  assert.ok(screen.includes('R.string.roadcrew_tacho_ble_refused_body)'));
  assert.match(screen, /name != null && name\.startsWith\("DTCO-"\)/);
  const vdo = value(bg, 'roadcrew_tacho_ble_refused_body_vdo');
  assert.ok(vdo.includes('Меню → Водач 1 → Настройки → Данни от VDO → Да'));
  assert.ok(vdo.includes('Меню → Водач 1 → Настройки → ИТС издаване → Да'));
  assert.ok(vdo.includes('Опитай пак') && vdo.includes('133'));
  const other = value(bg, 'roadcrew_tacho_ble_refused_body');
  assert.doesNotMatch(other, /VDO|Водач 1|ИТС/);
  assert.ok(other.includes('Опитай пак') && other.includes('133'));
  assert.equal(value(bg, 'roadcrew_tacho_ble_refused_title'), 'Тахографът не даде данните');
  for (const key of ['ble_refused_title', 'ble_refused_body_vdo', 'ble_refused_body']) {
    assert.ok(value(en, `roadcrew_tacho_${key}`), key);
  }
});

// Galin, 03.11.2026: "при самото пускане трябва да види дали тахографите които са били сдвоени са
// наблизо ако се наблизо да ги избера автоматично".
test('opening the Bluetooth side looks for the paired tachographs nearby and picks the nearest', () => {
  const find = model.slice(model.indexOf('fun findNearby()'), model.indexOf('fun select('));
  assert.match(find, /if \(worker\?\.isAlive == true \|\| scanner\?\.isAlive == true\) return/);
  assert.match(find, /RoadCrewTachoBleFinder\.scan\(adapter, bonded\)/);
  assert.match(find, /RoadCrewTachoBleChoice\.pairedWithSignal\(bonded, heard\)/);
  assert.match(find, /near\.firstOrNull\(\)/);
  assert.match(screen, /ble\.getNearby\(\)\.observe\(this, /);
  assert.ok((screen.match(/lookForTachographs\(\)/g) || []).length >= 4, 'onStart, the Bluetooth tab, the switch, the permission');
  assert.match(screen, /ble\.findNearby\(\)/);
  // The nearest one heard is green with its name; none - the row says so and the button waits.
  assert.match(screen, /R\.string\.roadcrew_tacho_ble_nearby/);
  assert.match(screen, /R\.string\.roadcrew_tacho_ble_none_nearby/);
  assert.match(screen, /R\.string\.roadcrew_tacho_ble_hint_pick/);
  assert.match(screen, /boolean ready = target != null/);
  // The scan listens to the end: stopping at the first paired one would hide a second truck.
  assert.doesNotMatch(finder, /in pairedTachographs\) enough\.countDown\(\)/);
  assert.match(finder, /onScanFailed[\s\S]*?enough\.countDown\(\)/);
});

// Galin, 03.11.2026: no failed attempts that then explain themselves one thing at a time.
test('"Свали картата" downloads from the tachograph shown and never pairs', () => {
  const from = model.slice(model.indexOf('fun downloadFrom('), model.indexOf('fun retry('));
  assert.match(from, /if \(device\.bondState != BluetoothDevice\.BOND_BONDED\) throw java\.io\.IOException\("Pair the tachograph first"\)/);
  assert.doesNotMatch(model, /RoadCrewTachoBleFinder\.pair\(/);
  assert.doesNotMatch(model, /Step\.(CHOOSING|PAIRING|SEARCHING)/);
  assert.match(screen, /ble\.downloadFrom\(/);
  assert.doesNotMatch(screen, /roadcrew_tacho_ble_which|ble\.start\(\)|ble\.startWith\(/);
  assert.match(screen, /R\.string\.roadcrew_tacho_ble_unpaired_title/);
  assert.ok(value(bg, 'roadcrew_tacho_ble_unpaired_body').includes('Нов тахограф'));
});

// Galin, 03.11.2026: "да може да се кликва тахограф след което да излиза опция на сдвоени ... както и
// опция нов тахограф след което да се стартира процедурата" - and the data settings in it.
test('the "Тахограф" section: the paired ones, which are near, and a four-step wizard for a new one', () => {
  assert.match(read('AndroidManifest-nightlyFree.xml'),
    /android:name="net\.osmand\.plus\.roadcrew\.tacho\.RoadCrewTachoTachographActivity"\s*android:exported="false"/);
  assert.match(screen, /readerRow\.setOnClickListener\(v -> onTachographRow\(\)\)/);
  assert.match(screen, /new Intent\(this, RoadCrewTachoTachographActivity\.class\)/);
  assert.match(screen, /startActivityForResult\(intent, REQUEST_TACHOGRAPH\)/);
  assert.match(screen, /ble\.select\(/);
  // The steps in the order the tachograph needs them, pairing only from the list of new ones.
  assert.match(pairing, /enum class Step \{ LIST, DATA, CONNECT, SEARCHING, CHOOSE, PAIRING, PAIRED, FAILED \}/);
  assert.equal((pairing.match(/RoadCrewTachoBleFinder\.pair\(/g) || []).length, 1);
  const pairFun = pairing.slice(pairing.indexOf('fun pair(address: String)'));
  assert.match(pairFun, /if \(now\.step != Step\.CHOOSE\) return/);
  assert.ok(pairFun.indexOf('RoadCrewTachoBleFinder.pair(') > 0);
  assert.match(pairing, /fun dataDone\(\)[\s\S]*?Step\.DATA[\s\S]*?Step\.CONNECT/);
  assert.match(pairing, /fun searchNew\(\)[\s\S]*?Step\.CONNECT[\s\S]*?Step\.CHOOSE/);
  assert.match(section, /static final int WIZARD_STEPS = 4;/);
  for (const n of [1, 2, 3, 4]) {
    assert.ok(section.includes(`R.string.roadcrew_tacho_wizard_step, ${n}, WIZARD_STEPS`), `step ${n}`);
  }
  assert.ok(section.indexOf('roadcrew_tacho_wizard_data_title') < section.indexOf('roadcrew_tacho_wizard_connect_title'));
  assert.match(section, /model\.dataDone\(\)/);
  assert.match(section, /model\.searchNew\(\)/);
  assert.match(section, /model\.pair\(/);
  assert.match(section, /setResult\(RESULT_OK, /);
  assert.match(section, /R\.string\.roadcrew_tacho_ble_nearby/);
  assert.match(section, /R\.string\.roadcrew_tacho_section_far/);
});

// Galin, 03.11.2026: both data settings on "Да", and the VDO menus said to be VDO's.
test('the wizard names the VDO menus as VDO\'s, both data settings first, no other app', () => {
  assert.equal(value(bg, 'roadcrew_tacho_wizard_data_path1'), 'Меню → Водач 1 → Настройки → Данни от VDO → Да');
  assert.equal(value(bg, 'roadcrew_tacho_wizard_data_path2'), 'Меню → Водач 1 → Настройки → ИТС издаване → Да');
  assert.ok(value(bg, 'roadcrew_tacho_wizard_data_vdo').startsWith('При VDO'));
  assert.ok(value(bg, 'roadcrew_tacho_wizard_connect_vdo').startsWith('При VDO: Меню → Водач 1 → Bluetooth → Свързване'));
  for (const key of ['wizard_data_other', 'wizard_connect_other']) {
    assert.ok(value(bg, `roadcrew_tacho_${key}`).startsWith('При друг тахограф'), key);
    assert.doesNotMatch(value(bg, `roadcrew_tacho_${key}`), /Водач 1|VDO/, key);
  }
  for (const key of ['wizard_data_path1', 'wizard_data_path2', 'wizard_connect_vdo', 'wizard_data_other', 'wizard_connect_other']) {
    assert.ok(value(en, `roadcrew_tacho_${key}`), key);
  }
  for (const xml of [bg, en]) {
    assert.doesNotMatch(xml, /Tacho Manager/);
  }
});

// Galin, 03.11.2026: Bluetooth off is said on the "Тахограф" row, and tapping it asks Android to
// turn it on - the driver answers Android's own question, nothing is switched silently.
test('Bluetooth off: the screen follows the switch and the row asks Android to turn it on', () => {
  assert.ok(screen.includes('BluetoothAdapter.ACTION_STATE_CHANGED'));
  assert.ok(screen.includes('new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)'));
  assert.doesNotMatch(screen + model + pairing + section, /adapter\(?\)?\.enable\(\)|\.enable\(\);/);
  assert.match(screen, /unregisterReceiver\(bluetoothReceiver\)/);
  const row = screen.slice(screen.indexOf('private void onTachographRow()'));
  assert.ok(row.indexOf('askBluetoothOn()') > 0 && row.indexOf('askBluetoothOn()') < row.indexOf('RoadCrewTachoTachographActivity.class'));
});
