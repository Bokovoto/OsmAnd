package net.osmand.plus.roadcrew.tacho;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.R;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A first, deliberately narrow proof that RoadCrew can read a tachograph
 * driver card on its own, through a plain USB smart-card reader - without the
 * third-party app. The dev.tacho build additionally exposes an explicitly
 * confirmed date-only experiment; it is NOT a completed DDD download.
 *
 * The reader (ACR39U or anything else matching roadcrew_tacho_usb_filter.xml)
 * can arrive two ways: Android hands it to this activity directly because the
 * manifest's intent-filter matched (the device was just plugged in), or the
 * activity is opened first and has to go looking for an already-attached one.
 * Both paths end up asking for USB permission the same way.
 */
public final class RoadCrewTachoCardActivity extends Activity {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewTachoCardActivity.class);
	private static final String ACTION_USB_PERMISSION = "net.osmand.plus.roadcrew.tacho.USB_PERMISSION";

	static final String EXTRA_DOWNLOAD = "roadcrew_download";
	static final String EXTRA_MARK_CARD = "roadcrew_mark_card";
	static final String EXTRA_TRACE = "roadcrew_trace";
	private boolean traceRequested;
	@Nullable private String markCardNumber;
	private static final String TAG = "RoadCrewTacho";

	private TextView statusView;
	private TextView logView;
	private boolean downloadRequested;
	private UsbManager usbManager;
	private UsbDevice lastDevice;
	private boolean busy;
	private final List<byte[]> discoveredFileIds = new ArrayList<>();
	private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
				return;
			}
			UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
				readCard(device);
			} else {
				statusView.setText(R.string.roadcrew_tacho_no_permission);
			}
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.roadcrew_tacho_card_activity);
		statusView = findViewById(R.id.roadcrewTachoStatus);
		logView = findViewById(R.id.roadcrewTachoLog);
		usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

		Button retry = findViewById(R.id.roadcrewTachoRetryButton);
		retry.setOnClickListener(v -> findAndRead());
		Button scan = findViewById(R.id.roadcrewTachoScanButton);
		scan.setOnClickListener(v -> scanFileIds());
		Button writeTest = findViewById(R.id.roadcrewTachoWriteTestButton);
		writeTest.setOnClickListener(v -> confirmTestDateWrite());

		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			registerReceiver(permissionReceiver, filter);
		}

		handleIntent(getIntent());
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		handleIntent(intent);
	}

	private void handleIntent(@Nullable Intent intent) {
		// A development trigger for the DDD download until the driver's own
		// buttons exist (they come with a mockup first - Galin's rule):
		// am start ... --ez roadcrew_download true (ROADMAP 327).
		downloadRequested = intent != null && intent.getBooleanExtra(EXTRA_DOWNLOAD, false);
		// Marking only for the one card named in the command, after its DDD is
		// stored in the same session - a working card is never marked by accident.
		markCardNumber = intent == null ? null : intent.getStringExtra(EXTRA_MARK_CARD);
		// Every command and reply of the download, for a software twin of the
		// card in tests and for diagnosis. Kept in the app's own external
		// folder, never in Downloads, never uploaded (ROADMAP 327).
		traceRequested = intent != null && intent.getBooleanExtra(EXTRA_TRACE, false);
		UsbDevice device = intent == null ? null : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
		if (device != null) {
			requestPermissionAndRead(device);
		} else {
			findAndRead();
		}
	}

	/** No device came in with the intent - look for one already plugged in. */
	private void findAndRead() {
		for (UsbDevice device : usbManager.getDeviceList().values()) {
			if (device.getVendorId() == 1839 && device.getProductId() == 45312) {
				requestPermissionAndRead(device);
				return;
			}
		}
		statusView.setText(R.string.roadcrew_tacho_no_device);
	}

	private void requestPermissionAndRead(UsbDevice device) {
		if (usbManager.hasPermission(device)) {
			readCard(device);
			return;
		}
		// Android 14+ (targetSdk 34+) refuses a mutable PendingIntent built from an
		// implicit intent; UsbManager still needs it mutable so it can add
		// EXTRA_DEVICE/EXTRA_PERMISSION_GRANTED when it fires the broadcast, so the
		// fix is to make the intent explicit instead of dropping mutability.
		Intent permissionRequest = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
		int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
		PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, permissionRequest, flags);
		usbManager.requestPermission(device, permissionIntent);
	}

	private void readCard(UsbDevice device) {
		if (busy) return;
		if (downloadRequested) {
			downloadRequested = false;
			downloadCard(device);
			return;
		}
		setBusy(true);
		lastDevice = device;
		statusView.setText(R.string.roadcrew_tacho_reading);
		logView.setText("");
		new Thread(() -> {
			UsbDeviceConnection connection = usbManager.openDevice(device);
			if (connection == null) {
				runOnUiThread(() -> {
					setBusy(false);
					statusView.setText(getString(R.string.roadcrew_tacho_error, "could not open the USB connection"));
				});
				return;
			}
			try {
				RoadCrewTachoCardReader.CardSummary summary = RoadCrewTachoCardReader.readSummary(device, connection);
				runOnUiThread(() -> showSummary(summary));
			} catch (IOException e) {
				LOG.warn("tachograph card read failed", e);
				String message = e.getMessage();
				runOnUiThread(() -> statusView.setText(getString(R.string.roadcrew_tacho_error, message)));
			} finally {
				connection.close();
				runOnUiThread(() -> setBusy(false));
			}
		}, "RoadCrewTachoCardRead").start();
	}

	private void showSummary(RoadCrewTachoCardReader.CardSummary summary) {
		setBusy(false);
		statusView.setText(R.string.roadcrew_tacho_waiting_for_reader);
		StringBuilder text = new StringBuilder();
		text.append("ATR (").append(summary.atr.length).append(" bytes): ")
				.append(RoadCrewTachoCardReader.toHex(summary.atr)).append('\n');
		for (RoadCrewTachoCardReader.Step step : summary.steps) {
			text.append('\n').append(step.ok ? "OK  " : "FAIL").append("  ").append(step.label)
					.append('\n').append("    ").append(step.detail).append('\n');
		}
		logView.setText(text.toString());
		// Also to logcat: exact bytes, no risk of a screenshot being mistyped by hand.
		LOG.info("ATR=" + RoadCrewTachoCardReader.toHex(summary.atr));
		for (RoadCrewTachoCardReader.Step step : summary.steps) {
			LOG.info((step.ok ? "OK " : "FAIL ") + step.label + " :: " + step.detail);
		}
	}

	/**
	 * SELECT only, across a plausible range of file ids under the Tachograph
	 * DF - never a write. This is how a real file to test the write path on
	 * gets found instead of guessed (Galin, 18.09: try on the test card that
	 * is in the reader now, but find the real file first rather than picking
	 * one blind).
	 */
	private void scanFileIds() {
		if (busy) return;
		if (lastDevice == null) {
			statusView.setText(R.string.roadcrew_tacho_no_device);
			return;
		}
		statusView.setText(R.string.roadcrew_tacho_scanning);
		setBusy(true);
		logView.setText("");
		UsbDevice device = lastDevice;
		new Thread(() -> {
			UsbDeviceConnection connection = usbManager.openDevice(device);
			if (connection == null) {
				runOnUiThread(() -> {
					setBusy(false);
					statusView.setText(getString(R.string.roadcrew_tacho_error, "could not open the USB connection"));
				});
				return;
			}
			try (RoadCrewTachoCardReader.OpenCard card = RoadCrewTachoCardReader.open(device, connection)) {
				List<byte[]> found = RoadCrewTachoCardReader.probeFileIds(
						card, RoadCrewTachoCardReader.AID_TACHOGRAPH_G1, 0x0501, 0x0520);
				discoveredFileIds.clear();
				discoveredFileIds.addAll(found);
				runOnUiThread(() -> {
					statusView.setText(R.string.roadcrew_tacho_waiting_for_reader);
					appendSteps("ATR: " + RoadCrewTachoCardReader.toHex(card.atr), card.steps);
				});
			} catch (IOException e) {
				LOG.warn("tachograph card scan failed", e);
				String message = e.getMessage();
				runOnUiThread(() -> statusView.setText(getString(R.string.roadcrew_tacho_error, message)));
			} finally {
				connection.close();
				runOnUiThread(() -> setBusy(false));
			}
		}, "RoadCrewTachoCardScan").start();
	}

	/** A separate confirmation for every diagnostic write, only in local tacho builds. */
	private void confirmTestDateWrite() {
		if (busy) return;
		try {
			String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
			if (version == null || !version.startsWith("0.1.0-dev.tacho")) return;
		} catch (android.content.pm.PackageManager.NameNotFoundException e) {
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.roadcrew_tacho_test_date_title)
				.setMessage(R.string.roadcrew_tacho_test_date_warning)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(R.string.roadcrew_tacho_write_test, (dialog, which) -> writeTestDate())
				.show();
	}

	private void writeTestDate() {
		if (busy) return;
		if (lastDevice == null) {
			statusView.setText(R.string.roadcrew_tacho_no_device);
			return;
		}
		setBusy(true);
		statusView.setText(R.string.roadcrew_tacho_writing);
		UsbDevice device = lastDevice;
		new Thread(() -> {
			UsbDeviceConnection connection = usbManager.openDevice(device);
			if (connection == null) {
				runOnUiThread(() -> {
					setBusy(false);
					statusView.setText(getString(R.string.roadcrew_tacho_error, "could not open the USB connection"));
				});
				return;
			}
			try (RoadCrewTachoCardReader.OpenCard card = RoadCrewTachoCardReader.open(device, connection)) {
				long requested = System.currentTimeMillis() / 1000L;
				long previous = RoadCrewTachoCardReader.writeTestDownloadDate(card, requested, (oldDate, newDate) -> {
					String audit = "TEST ONLY EF050E before=" + oldDate + " requested=" + newDate;
					LOG.info(audit);
					try (java.io.FileOutputStream out = openFileOutput("tacho-test-date-audit.txt", MODE_APPEND)) {
						out.write((audit + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
						out.getFD().sync();
					}
				});
				card.steps.add(new RoadCrewTachoCardReader.Step("EF050E test date - readback verified", true,
						"before=" + RoadCrewTachoCardReader.describeDate(previous)
						+ "; after=" + RoadCrewTachoCardReader.describeDate(requested)));
				runOnUiThread(() -> {
					statusView.setText(R.string.roadcrew_tacho_waiting_for_reader);
					appendSteps("TEST CARD DATE ONLY - no DDD exported", card.steps);
				});
			} catch (IOException e) {
				LOG.warn("tachograph card write test failed", e);
				String message = e.getMessage();
				runOnUiThread(() -> statusView.setText(getString(R.string.roadcrew_tacho_error, message)));
			} finally {
				connection.close();
				runOnUiThread(() -> setBusy(false));
			}
		}, "RoadCrewTachoCardWriteTest").start();
	}

	/**
	 * The whole driver card into one DDD file (ROADMAP 327). Reads only; the
	 * file is stored and read back before anything is reported as done.
	 */
	private void downloadCard(UsbDevice device) {
		setBusy(true);
		lastDevice = device;
		statusView.setText(R.string.roadcrew_tacho_reading);
		logView.setText("");
		// Every command and answer of this session, for a card whose download
		// has to be diagnosed or replayed in a test. Kept in the app's own files.
		StringBuilder trace = traceRequested ? new StringBuilder() : null;
		traceRequested = false;
		new Thread(() -> {
			UsbDeviceConnection connection = usbManager.openDevice(device);
			if (connection == null) {
				runOnUiThread(() -> {
					setBusy(false);
					statusView.setText(getString(R.string.roadcrew_tacho_error, "could not open the USB connection"));
				});
				return;
			}
			long started = System.currentTimeMillis();
			try (RoadCrewTachoCardReader.OpenCard card = RoadCrewTachoCardReader.open(device, connection)) {
				RoadCrewTachoDownloadDate.Channel direct = RoadCrewTachoCardReader.channel(card);
				RoadCrewTachoDownloadDate.Channel channel = trace == null ? direct : command -> {
					trace.append("> ").append(RoadCrewTachoCardReader.toHex(command)).append('\n');
					try {
						byte[] answer = direct.exchange(command);
						trace.append("< ").append(RoadCrewTachoCardReader.toHex(answer)).append('\n');
						return answer;
					} catch (IOException e) {
						trace.append("! ").append(e.getMessage()).append('\n');
						throw e;
					}
				};
				RoadCrewTachoCardDownload.Result result = RoadCrewTachoCardDownload.download(channel);
				String name = saveDdd(result);
				String summary = String.format(java.util.Locale.ROOT,
						"DDD saved %s bytes=%d gen2=%s files=%d absent=%s warnings=%s sha256=%s seconds=%d",
						name, result.ddd.length, result.secondGeneration, result.storedTags.size(),
						result.absent, result.warnings, sha256(result.ddd),
						(System.currentTimeMillis() - started) / 1000);
				android.util.Log.i(TAG, summary);
				String markCard = markCardNumber;
				markCardNumber = null;
				if (markCard != null) {
					if (!markCard.equals(result.cardNumber)) {
						summary += "\nNOT marked: this card is " + result.cardNumber + ", the command named " + markCard;
					} else {
						// DDP_035: after the download, update LastCardDownload - in
						// DF Tachograph and, on a Gen2 card, Tachograph_G2. The file
						// is already stored and read back above.
						long now = System.currentTimeMillis() / 1000L;
						RoadCrewTachoCardDownload.markDownloaded(channel, now,
								result.secondGeneration, (before, requested) -> {
									String audit = "MARK card=" + result.cardNumber + " file=" + name
											+ " before=" + before + " requested=" + requested;
									android.util.Log.i(TAG, audit);
									try (java.io.FileOutputStream out = openFileOutput("tacho-download-audit.txt", MODE_APPEND)) {
										out.write((audit + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
										out.getFD().sync();
									}
								});
						summary += "\nMarked as downloaded: " + RoadCrewTachoCardReader.describeDate(now)
								+ (result.secondGeneration ? " (Tachograph and Tachograph_G2)" : " (Tachograph)");
					}
					android.util.Log.i(TAG, summary.substring(summary.lastIndexOf('\n') + 1));
				}
				final String shown = summary;
				runOnUiThread(() -> {
					statusView.setText(R.string.roadcrew_tacho_waiting_for_reader);
					logView.setText(shown);
				});
			} catch (IOException e) {
				android.util.Log.w(TAG, "DDD download failed: " + e.getMessage(), e);
				String message = e.getMessage();
				runOnUiThread(() -> statusView.setText(getString(R.string.roadcrew_tacho_error, message)));
			} finally {
				connection.close();
				if (trace != null) {
					saveTrace(trace);
				}
				runOnUiThread(() -> setBusy(false));
			}
		}, "RoadCrewTachoCardDownload").start();
	}

	private void saveTrace(StringBuilder trace) {
		java.io.File folder = new java.io.File(getExternalFilesDir(null), "tacho-trace");
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
				.format(new java.util.Date());
		java.io.File file = new java.io.File(folder, "trace_" + stamp + ".txt");
		if (!folder.isDirectory() && !folder.mkdirs()) {
			android.util.Log.w(TAG, "trace NOT saved: cannot create " + folder);
			return;
		}
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
			out.write(trace.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			out.getFD().sync();
			android.util.Log.i(TAG, "trace saved " + file + " bytes=" + file.length());
		} catch (IOException e) {
			android.util.Log.w(TAG, "trace NOT saved: " + e.getMessage(), e);
		}
	}

	/**
	 * Downloads/RoadCrew/C_yyyyMMdd_HHmm_card.ddd, where the driver can find
	 * and send it. Read back and compared before it counts as stored.
	 */
	private String saveDdd(RoadCrewTachoCardDownload.Result result) throws IOException {
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT)
				.format(new java.util.Date());
		String card = result.cardNumber.replaceAll("[^A-Za-z0-9]", "");
		String name = "C_" + stamp + "_" + (card.isEmpty() ? "card" : card) + ".ddd";
		byte[] stored;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			android.content.ContentResolver resolver = getContentResolver();
			android.content.ContentValues values = new android.content.ContentValues();
			values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
			values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
			values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
					android.os.Environment.DIRECTORY_DOWNLOADS + "/RoadCrew");
			values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
			android.net.Uri uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
			if (uri == null) {
				throw new IOException("Could not create " + name + " in Downloads");
			}
			try (java.io.OutputStream out = resolver.openOutputStream(uri)) {
				if (out == null) {
					throw new IOException("Could not open " + name);
				}
				out.write(result.ddd);
			}
			values.clear();
			values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
			resolver.update(uri, values, null, null);
			try (java.io.InputStream in = resolver.openInputStream(uri)) {
				stored = readAll(in);
			}
		} else {
			java.io.File dir = new java.io.File(getExternalFilesDir(null), "RoadCrew");
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("Could not create " + dir);
			}
			java.io.File file = new java.io.File(dir, name);
			try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
				out.write(result.ddd);
				out.getFD().sync();
			}
			try (java.io.InputStream in = new java.io.FileInputStream(file)) {
				stored = readAll(in);
			}
		}
		if (!java.util.Arrays.equals(stored, result.ddd)) {
			throw new IOException(name + " did not read back as written");
		}
		return name;
	}

	private static byte[] readAll(@Nullable java.io.InputStream in) throws IOException {
		if (in == null) {
			throw new IOException("Stored file cannot be read back");
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		for (int n; (n = in.read(buffer)) > 0; ) {
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	private static String sha256(byte[] data) {
		try {
			return RoadCrewTachoCardReader.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
		} catch (java.security.NoSuchAlgorithmException e) {
			return "?";
		}
	}

	private void setBusy(boolean value) {
		busy = value;
		findViewById(R.id.roadcrewTachoRetryButton).setEnabled(!value);
		findViewById(R.id.roadcrewTachoScanButton).setEnabled(!value);
		findViewById(R.id.roadcrewTachoWriteTestButton).setEnabled(!value);
	}

	private void appendSteps(String header, List<RoadCrewTachoCardReader.Step> steps) {
		StringBuilder text = new StringBuilder(logView.getText());
		text.append("\n== ").append(header).append(" ==\n");
		LOG.info("== " + header + " ==");
		for (RoadCrewTachoCardReader.Step step : steps) {
			text.append(step.ok ? "OK  " : "FAIL").append("  ").append(step.label)
					.append('\n').append("    ").append(step.detail).append('\n');
			LOG.info((step.ok ? "OK " : "FAIL ") + step.label + " :: " + step.detail);
		}
		logView.setText(text.toString());
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		unregisterReceiver(permissionReceiver);
	}
}
