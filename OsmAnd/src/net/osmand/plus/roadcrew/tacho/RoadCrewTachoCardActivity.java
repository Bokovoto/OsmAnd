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

	private TextView statusView;
	private TextView logView;
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
