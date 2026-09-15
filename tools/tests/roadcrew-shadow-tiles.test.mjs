import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// Test 106 (Galin, 2026-09-13): the phone asks for fixed tiles
// (/v1/truck-map/shadow-tiles/) instead of its own 30 km box (shadow-segments).
// On 13.09 every box request read about 4,700 rows - the largest reader of the
// database; a tile is built once per 15-minute epoch and shared by every phone
// in the area.

const root = fileURLToPath(new URL('../../', import.meta.url));
const downloader = readFileSync(join(root,
  'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewShadowSnapshotDownloader.java'), 'utf8').replace(/\r\n/g, '\n');

function compile() {
  const tempRoot = resolve(tmpdir());
  const output = mkdtempSync(join(tempRoot, 'roadcrew-shadow-tiles-'));
  const result = spawnSync('javac', ['-d', output,
    join(root, 'OsmAnd/src/net/osmand/plus/roadcrew/RoadCrewShadowTiles.java'),
    join(root, 'tools/tests/java/RoadCrewShadowTilesTest.java')], { encoding: 'utf8', timeout: 30000 });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stderr}`);
  return {
    output,
    run: (args = []) => spawnSync('java', ['-cp', output, 'net.osmand.plus.roadcrew.RoadCrewShadowTilesTest', ...args],
      { encoding: 'utf8', timeout: 20000 }),
    cleanup: () => {
      assert.equal(dirname(resolve(output)), tempRoot);
      assert.ok(basename(output).startsWith('roadcrew-shadow-tiles-'));
      rmSync(output, { recursive: true, force: true });
    },
  };
}

// Test 107 (Galin, 2026-09-15): the phone talks to the project's own server
// directly; Cloudflare only forwards for older versions until they are gone.
test('every RoadCrew request goes to the own server, none to Cloudflare workers.dev', () => {
  const folder = join(root, 'OsmAnd/src/net/osmand/plus/roadcrew');
  const endpoints = readFileSync(join(folder, 'RoadCrewEndpoints.java'), 'utf8');
  assert.match(endpoints, /static final String API_BASE_URL = "https:\/\/api\.roadcrew\.meriltrans\.com";/);
  const offenders = readdirSync(folder).filter(file => file.endsWith('.java'))
    .filter(file => /workers\.dev|galin-b-vasilev1/.test(readFileSync(join(folder, file), 'utf8')));
  assert.deepEqual(offenders, []);
});

test('the own server is trusted on every Android version the app supports', () => {
  const manifest = readFileSync(join(root, 'OsmAnd/AndroidManifest.xml'), 'utf8');
  assert.match(manifest, /android:networkSecurityConfig="@xml\/roadcrew_network_security_config"/);
  // With a config present the manifest's cleartext flag is ignored; it must be kept here.
  assert.match(manifest, /android:usesCleartextTraffic="true"/);
  const config = readFileSync(join(root, 'OsmAnd/res/xml/roadcrew_network_security_config.xml'), 'utf8');
  assert.match(config, /<base-config cleartextTrafficPermitted="true">/);
  assert.match(config, /<domain includeSubdomains="false">api\.roadcrew\.meriltrans\.com<\/domain>/);
  for (const root_ of ['x1', 'x2']) {
    assert.match(config, new RegExp(`@raw/roadcrew_isrg_root_${root_}`));
    const pem = readFileSync(join(root, `OsmAnd/res/raw/roadcrew_isrg_root_${root_}.pem`), 'utf8');
    assert.match(pem, /-----BEGIN CERTIFICATE-----/);
  }
});

test('the downloader asks for tiles, never for its own box', () => {
  assert.match(downloader, /RoadCrewEndpoints\.API_BASE_URL \+ "\/v1\/truck-map\/shadow-tiles\/"/);
  assert.doesNotMatch(downloader, /shadow-segments/);
  assert.match(downloader, /RoadCrewShadowTiles\.coverage\(latitude, longitude\)/);
  assert.match(downloader, /RoadCrewShadowTiles\.merge\(coverage, parts\)/);
  assert.match(downloader, /now - fetched\.fetchedAtMillis >= RoadCrewShadowTiles\.TILE_EPOCH_MILLIS/,
    'a tile is fetched again only after its epoch');
  assert.match(downloader, /MAX_SNAPSHOT_SEGMENTS = 4 \* RoadCrewShadowTiles\.MAX_SEGMENTS_PER_TILE/);
});

// Real dependency-free production class, not a reimplementation.
test('shadow tiles runtime scenarios', () => {
  const java = compile();
  try {
    const run = java.run();
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /shadow tiles scenarios PASS/);
  } finally {
    java.cleanup();
  }
});

// The phone's grid against the server's own code, when the backend is next to this repo.
const serverTiles = join(root, '..', 'backend', 'roadcrew-api', 'src', 'truck-map-tiles.ts');
test('the phone grid equals the server grid on 300 points', { skip: !existsSync(serverTiles) && 'backend not beside android' }, async () => {
  const { tileKey, tileOf } = await import(pathToFileURL(serverTiles).href);
  const points = [];
  let seed = 20260913;
  const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
  for (let i = 0; i < 250; i++) points.push([next() * 180 - 90, next() * 360 - 180]);
  for (let i = 0; i < 50; i++) points.push([Math.round((next() * 180 - 90) * 4) / 4, Math.round((next() * 360 - 180) * 4) / 4]);
  const java = compile();
  try {
    const run = java.run(['keys', ...points.map(([lat, lon]) => `${lat},${lon}`)]);
    assert.equal(run.status, 0, run.stderr);
    const phone = run.stdout.trim().split(/\r?\n/);
    const server = points.map(([lat, lon]) => tileKey(tileOf(lat, lon)));
    assert.deepEqual(phone, server);
  } finally {
    java.cleanup();
  }
});
