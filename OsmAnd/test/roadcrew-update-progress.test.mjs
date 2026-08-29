import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const updater = read('../src/net/osmand/plus/roadcrew/RoadCrewAppUpdater.java');
const dialog = read('../src/net/osmand/plus/roadcrew/RoadCrewUpdateProgressDialog.kt');
const activity = read('../src/net/osmand/plus/activities/MapActivity.java');

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
    ['-J--add-modules=jdk.httpserver', '--add-modules', 'jdk.httpserver', '--execution', 'local', '--feedback', 'concise', '-'],
    { input: probe, encoding: 'utf8', timeout: 40000 });
  assert.ifError(result.error);
  const output = `${result.stdout}\n${result.stderr}`;
  assert.equal(result.status, 0, output);
  assert.ok(output.includes(marker), output);
  assert.doesNotMatch(output, /PROBE_FAILED|Error:/, output);
}

test('APK transport reports real bytes, validates files and cleans interrupted transfers', () => {
  // Real production transport and local HTTP fixtures; no Android compilation.
  const transfer = read('../../OsmAnd-java/src/main/java/net/osmand/util/RoadCrewUpdateTransfer.java')
    .replace(/^package .*;\s*/m, '');
  runProbe(`
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
${transfer}
class TransferChecks {
  static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
  static class Progress implements RoadCrewUpdateTransfer.Listener {
    List<Long> received = new ArrayList<>(), totals = new ArrayList<>();
    int verifying;
    Runnable cancel;
    public void onProgress(long value, long total) {
      received.add(value); totals.add(total);
      if (value > 0 && cancel != null) cancel.run();
    }
    public void onVerifying() { verifying++; }
  }
  static void fail(RoadCrewUpdateTransfer transfer, String api, String browser, File file, long size, String sha) throws Exception {
    boolean failed = false;
    try { transfer.download(api, browser, file, size, sha); } catch (Exception expected) { failed = true; }
    check(failed, "invalid transfer succeeded");
    check(!new File(file + ".part").exists(), "partial file survived failure");
  }
  static void run() throws Exception {
    byte[] bytes = new byte[2 * 1024 * 1024];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
    String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger browserCalls = new AtomicInteger(), requests = new AtomicInteger(), headerOk = new AtomicInteger();
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      String path = exchange.getRequestURI().getPath();
      if ("identity".equals(exchange.getRequestHeaders().getFirst("Accept-Encoding"))
          && "application/octet-stream".equals(exchange.getRequestHeaders().getFirst("Accept"))) headerOk.incrementAndGet();
      try {
        if (path.equals("/redirect")) {
          exchange.getResponseHeaders().add("Location", "/ok");
          exchange.sendResponseHeaders(302, -1);
        } else if (path.equals("/missing")) {
          exchange.sendResponseHeaders(503, -1);
        } else if (path.equals("/html")) {
          exchange.getResponseHeaders().add("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().write("not an APK".getBytes());
        } else {
          if (path.equals("/browser")) browserCalls.incrementAndGet();
          exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
          if (path.equals("/tiny")) {
            exchange.sendResponseHeaders(200, 7);
            exchange.getResponseBody().write(new byte[7]);
          } else {
            exchange.sendResponseHeaders(200, path.equals("/unknown") ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes, 0, path.equals("/broken") ? 1100000 : bytes.length);
          }
        }
      } catch (IOException expectedOnCancel) { } finally { exchange.close(); }
    });
    Path dir = Files.createTempDirectory("roadcrew-update-test-");
    File apk = dir.resolve("RoadCrew.apk").toFile();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.start();
    try {
      Progress p = new Progress();
      new RoadCrewUpdateTransfer("RoadCrew-test", p).download(base + "/redirect", base + "/browser", apk, bytes.length, sha);
      check(Arrays.equals(bytes, Files.readAllBytes(apk.toPath())), "download differs");
      check(p.received.get(0) == 0 && p.received.get(p.received.size() - 1) == bytes.length, "progress endpoints");
      for (int i = 1; i < p.received.size(); i++) check(p.received.get(i) >= p.received.get(i - 1), "nonmonotonic bytes");
      check(p.totals.stream().allMatch(v -> v == bytes.length), "wrong progress total");
      check(p.verifying == 1 && headerOk.get() > 0 && browserCalls.get() == 0, "headers/verify/fallback");
      check(!new File(apk + ".part").exists(), "partial survived success");

      Progress unknown = new Progress();
      new RoadCrewUpdateTransfer("test", unknown).download("", base + "/unknown", apk, -1, sha);
      check(unknown.totals.stream().allMatch(v -> v <= 0), "invented total");
      check(apk.length() == bytes.length, "chunked transfer failed");
      new RoadCrewUpdateTransfer("test", new Progress()).download(base + "/missing", base + "/browser", apk, bytes.length, sha);
      check(browserCalls.get() == 1, "missing API should fall back");
      new RoadCrewUpdateTransfer("test", new Progress()).download(base + "/html", base + "/browser", apk, bytes.length, sha);
      check(browserCalls.get() == 2, "metadata response should fall back");

      Files.writeString(apk.toPath(), "previous verified APK");
      fail(new RoadCrewUpdateTransfer("test", new Progress()), base + "/broken", base + "/browser", apk, bytes.length, sha);
      check(browserCalls.get() == 2, "broken body silently restarted");
      check(Files.readString(apk.toPath()).equals("previous verified APK"), "failed download replaced good file");
      fail(new RoadCrewUpdateTransfer("test", new Progress()), "", base + "/ok", apk, bytes.length, "00".repeat(32));
      fail(new RoadCrewUpdateTransfer("test", new Progress()), "", base + "/ok", apk, bytes.length - 1, sha);
      fail(new RoadCrewUpdateTransfer("test", new Progress()), "", base + "/tiny", apk, -1, "");

      Progress cancelled = new Progress();
      RoadCrewUpdateTransfer cancelTransfer = new RoadCrewUpdateTransfer("test", cancelled);
      cancelled.cancel = cancelTransfer::requestCancel;
      fail(cancelTransfer, base + "/ok", base + "/browser", apk, bytes.length, sha);
      check(browserCalls.get() == 2 && cancelled.verifying == 0, "cancel continued");
      int before = requests.get();
      RoadCrewUpdateTransfer beforeStart = new RoadCrewUpdateTransfer("test", new Progress());
      beforeStart.requestCancel();
      fail(beforeStart, base + "/ok", base + "/browser", apk, bytes.length, sha);
      check(requests.get() == before, "cancel before start made network request");

      new RoadCrewUpdateTransfer("test", new Progress()).download(base + "/ok", "", apk, bytes.length, sha);
      check(Arrays.equals(bytes, Files.readAllBytes(apk.toPath())), "explicit retry failed");
    } finally {
      server.stop(0);
      Files.deleteIfExists(dir.resolve("RoadCrew.apk.part"));
      Files.deleteIfExists(apk.toPath());
      Files.deleteIfExists(dir);
    }
  }
}
try { TransferChecks.run(); System.out.println("TRANSFER_OK"); } catch (Throwable e) { System.out.println("PROBE_FAILED: " + e); e.printStackTrace(); }
/exit
`, 'TRANSFER_OK');
});

test('single download survives rotation, rejects duplicate taps and releases only after cancellation', () => {
  // Execute just the lifecycle/state methods, using tiny Android boundary stubs.
  const methods = [
    'private static void downloadAndInstall(', 'public static void onResume(',
    'public static void onPause(', 'private static void cancelDownload()',
    'private static void finishCancelled(', 'private static void primaryAction()',
    'private static void clearPending(', 'public static boolean isUpdateInProgress()'
  ].map(signature => block(updater, signature)).join('\n');
  runProbe(`
import java.io.*;
import java.lang.ref.*;
import java.util.*;
${block(updater, 'public enum Phase')}
class R { static class string { static final int roadcrew_update_transfer_failed = 1, roadcrew_update_interrupted = 2, roadcrew_update_installer_failed = 3; } }
class Log { static void w(String tag, String msg, Exception e) {} }
class JSONObject { String value; JSONObject(String value) { this.value = value; } public String toString() { return value; } }
class UpdateInfo { JSONObject toJson() { return new JSONObject("saved"); } static UpdateInfo fromJson(JSONObject o) { return new UpdateInfo(); } }
class Preferences {
  Map<String, String> values = new HashMap<>();
  Preferences edit() { return this; }
  Preferences putString(String k, String v) { values.put(k, v); return this; }
  Preferences remove(String k) { values.remove(k); return this; }
  String getString(String k, String d) { return values.getOrDefault(k, d); }
  void apply() {}
}
class Context {
  static final int MODE_PRIVATE = 0;
  static Preferences prefs = new Preferences();
  Preferences getSharedPreferences(String name, int mode) { return prefs; }
  Context getApplicationContext() { return new Context(); }
}
class MapActivity extends Context { String getPackageName() { return "org.roadcrew.app"; } }
class WindowStub { int dismissed; void dismiss() { dismissed++; } }
class Transfer { boolean cancelled; void requestCancel() { cancelled = true; } void disconnect() {} }
class Worker { void execute(Runnable action) { action.run(); } }
class Intent { Intent(String action) {} Intent setData(Object data) { return this; } }
class Uri { static Object parse(String s) { return s; } }
class Settings { static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES = "settings"; }
class AndroidUtils { static void startActivityIfSafe(MapActivity a, Intent i) {} }
class DownloadTask {
  UpdateInfo update; Phase phase = Phase.PERMISSION;
  boolean started, cancelled, installerOpening; int error;
  File apk; Transfer transfer;
  DownloadTask(UpdateInfo u) { update = u; }
}
class StateChecks {
  static final String TAG = "test", PREFS_NAME = "prefs", KEY_PENDING_UPDATE = "pending";
  static WeakReference<MapActivity> foreground = new WeakReference<>(null);
  static DownloadTask activeTask;
  static WindowStub progressDialog, offerDialog;
  static Worker CANCELLER = new Worker();
  static boolean permission = true, installerOk = true;
  static int started, rendered, installs;
  static boolean canInstall(Context c) { return permission; }
  static void startDownload(Context c, DownloadTask task) { task.phase = Phase.CONNECTING; task.started = true; task.transfer = new Transfer(); started++; }
  static void showDownloadProgress() { if (foreground.get() != null && activeTask != null) { if (progressDialog == null) progressDialog = new WindowStub(); rendered++; } }
  static boolean openInstaller(MapActivity a, File f) { installs++; return installerOk; }
  ${methods}
  static void check(boolean b, String s) { if (!b) throw new AssertionError(s); }
  static void run() throws Exception {
    MapActivity first = new MapActivity(); onResume(first);
    downloadAndInstall(first, new UpdateInfo());
    DownloadTask original = activeTask;
    downloadAndInstall(first, new UpdateInfo());
    check(started == 1 && activeTask == original, "duplicate download");
    WindowStub previous = progressDialog;
    onPause(first);
    check(foreground.get() == null && progressDialog == null && previous.dismissed == 1, "destroyed activity retained");
    MapActivity rotated = new MapActivity(); onResume(rotated);
    check(started == 1 && activeTask == original && progressDialog != null, "rotation restarted transfer");
    cancelDownload();
    check(activeTask == original && original.phase == Phase.CANCELLING && original.transfer.cancelled, "cancel did not retain worker slot");
    downloadAndInstall(rotated, new UpdateInfo());
    check(started == 1, "new transfer before cancellation finished");
    finishCancelled(rotated, original);
    check(!isUpdateInProgress() && Context.prefs.values.isEmpty(), "cancel did not finish");

    permission = false;
    downloadAndInstall(rotated, new UpdateInfo());
    check(activeTask.phase == Phase.PERMISSION && started == 1, "download before permission");
    onPause(rotated); permission = true; onResume(rotated);
    check(started == 2, "did not resume after permission");
    activeTask.phase = Phase.FAILED; primaryAction();
    check(started == 3 && activeTask.phase == Phase.CONNECTING, "retry failed");
    primaryAction(); check(started == 3, "double retry");

    File file = File.createTempFile("roadcrew-installer-test", ".apk");
    try {
      activeTask.phase = Phase.READY; activeTask.apk = file;
      primaryAction(); primaryAction();
      check(installs == 1, "duplicate installer");
      onPause(rotated); onResume(rotated); installerOk = false; primaryAction();
      check(activeTask.phase == Phase.FAILED && activeTask.error == R.string.roadcrew_update_installer_failed, "installer failure hidden");
    } finally { file.delete(); }
    cancelDownload();
    downloadAndInstall(rotated, new UpdateInfo());
    onPause(rotated); activeTask = null;
    int before = started; onResume(rotated);
    check(activeTask.phase == Phase.FAILED && activeTask.error == R.string.roadcrew_update_interrupted && started == before, "process death silently restarted");
    primaryAction(); check(started == before + 1, "interrupted retry failed");
    cancelDownload(); finishCancelled(rotated, activeTask);
  }
}
try { StateChecks.run(); System.out.println("STATE_OK"); } catch (Throwable e) { System.out.println("PROBE_FAILED: " + e); }
/exit
`, 'STATE_OK');
});

test('poster, progress, localization and lifecycle wiring remain consistent', () => {
  assert.match(activity, /RoadCrewAppUpdater\.onResume\(this\)/);
  assert.match(activity, /RoadCrewAppUpdater\.onPause\(this\)/);
  assert.match(activity, /!RoadCrewAppUpdater\.isUpdateInProgress\(\)/);
  assert.match(dialog, /ImageView\.ScaleType\.FIT_CENTER/);
  assert.match(dialog, /Configuration\.ORIENTATION_LANDSCAPE/);
  assert.match(dialog, /WindowInsetsCompat\.Type\.displayCutout/);
  assert.match(dialog, /received\.toDouble\(\) \/ total/);
  assert.match(dialog, /floor\(fraction \* 100\)/);
  assert.match(dialog, /spinner\.visibility = if \(busy\)/);
  assert.match(dialog, /cancel\.isEnabled = phase != Phase\.CANCELLING/);
  assert.match(updater, /task\.received = apk\.length\(\)/);
  assert.doesNotMatch(updater, /openReleasePage|roadcrew_update_downloading/);
  for (const locale of ['values', 'values-bg']) {
    const strings = read(`../res/${locale}/strings.xml`);
    const references = [...`${dialog}\n${updater}`.matchAll(/R\.string\.(roadcrew_update_\w+)/g)].map(m => m[1]);
    const flavorStrings = read(`../src/nightlyFree/res/${locale}/roadcrew_strings.xml`);
    for (const key of new Set(references)) {
      assert.equal([...`${strings}\n${flavorStrings}`.matchAll(new RegExp('name="' + key + '"', 'g'))].length, 1, `${locale}/${key}`);
    }
  }
});
