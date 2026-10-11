package net.osmand.plus.roadcrew.tacho;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;

import net.osmand.plus.R;
import net.osmand.plus.utils.AndroidUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The driver card screen (ROADMAP 327), as in the mockup Galin approved on
 * 25.09.2026: the reader and the card are shown live, the download starts
 * with a button, the card is marked as downloaded right after the file is
 * stored and read back, and the file goes out through Android's share sheet.
 *
 * All USB work - polling the slot, reading the card, the download - runs on
 * one worker thread over one claimed reader interface; the screen state lives
 * on the UI thread.
 */
public final class RoadCrewTachoCardActivity extends ComponentActivity {

	private static final String TAG = "RoadCrewTacho";
	private static final String ACTION_USB_PERMISSION = "net.osmand.plus.roadcrew.tacho.USB_PERMISSION";

	/** Development triggers over adb: start the download once a driver card is in, and trace it. */
	static final String EXTRA_DOWNLOAD = "roadcrew_download";
	static final String EXTRA_TRACE = "roadcrew_trace";

	private static final long POLL_MILLIS = 1000;
	private static final String FOLDER = "RoadCrew";
	private static final int HISTORY_ROWS = 5;
	private static final int REQUEST_OPEN_FILE = 4081;

	private enum Reader { NONE, NO_PERMISSION, CONNECTED }

	private enum CardState { ABSENT, READING, DRIVER, OTHER, UNREADABLE }

	private enum Phase { IDLE, DOWNLOADING, DONE, FAILED }

	/** The USB reader or the tachograph itself over Bluetooth, on the same screen (ROADMAP 411). */
	private enum Source { USB, BLUETOOTH }

	private static final String PREFS = "roadcrew_tacho_card";
	private static final String KEY_SOURCE = "source";
	private static final int REQUEST_BLUETOOTH = 4082;


	/** A DDD file in Downloads/RoadCrew that the driver can send. */
	private static final class Stored {
		final Uri uri;
		final String name;
		final long takenAt;
		final long size;
		final boolean secondGeneration;

		Stored(Uri uri, String name, long takenAt, long size, boolean secondGeneration) {
			this.uri = uri;
			this.name = name;
			this.takenAt = takenAt;
			this.size = size;
			this.secondGeneration = secondGeneration;
		}
	}

	// Screen state, UI thread only.
	private Reader reader = Reader.NONE;
	private CardState cardState = CardState.ABSENT;
	private String readerDiagnostic = "";
	@Nullable private RoadCrewTachoCardDownload.CardInfo cardInfo;
	private Phase phase = Phase.IDLE;
	private int progressDone;
	private int progressTotal = 1;
	private String progressTitle = "";
	private String progressFile = "";
	@Nullable private Stored doneFile;
	private int doneParts;
	private long doneMarkedAt;
	@Nullable private String doneHolder;
	@Nullable private String doneCardNumber;
	private String errorTitle = "";
	private String errorBody = "";
	private final List<Stored> history = new ArrayList<>();
	private boolean downloadRequested;
	private boolean traceRequested;
	@Nullable private UsbDevice device;

	// The reader, worker thread only.
	private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
	@Nullable private UsbDeviceConnection connection;
	@Nullable private RoadCrewTachoCardReader.Session session;
	@Nullable private ScheduledFuture<?> polling;
	private boolean cardSeen;

	private volatile boolean busy;
	private volatile boolean visible;
	private UsbManager usbManager;

	private TextView readerState;
	private TextView readerDetail;
	private ImageView readerIcon;
	private ImageView readerMark;
	private TextView cardStateView;
	private TextView cardDetail;
	private ImageView cardIcon;
	private ImageView cardMark;
	private View errorBlock;
	private TextView errorTitleView;
	private TextView errorBodyView;
	private View doneBlock;
	private TextView doneMarkedView;
	private LinearLayout doneDetails;
	private View progressBlock;
	private TextView progressTitleView;
	private TextView progressPercent;
	private ProgressBar progressBar;
	private TextView progressFileView;
	private TextView progressCount;
	private View lastBlock;
	private TextView lastText;
	private TextView nextText;
	private Button remainingButton;
	private Button openFileButton;
	private Button downloadButton;
	private TextView hint;
	private Button sendButton;
	private Button finishButton;
	private View historyBlock;
	private LinearLayout historyRows;

	// Bluetooth, UI thread only.
	private Source source = Source.USB;
	private RoadCrewTachoBluetoothModel ble;
	@Nullable private RoadCrewTachoBluetoothModel.State bleState;
	@Nullable private String bleLocalError;
	private View sourceBox;
	private Button sourceUsb;
	private Button sourceBluetooth;
	private TextView readerLabel;
	private TextView keepCardText;
	private ProgressBar partBar;
	private Button cancelButton;

	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			UsbDevice changed = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
				if (changed != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					attach(changed);
				} else {
					reader = Reader.NO_PERMISSION;
					render();
				}
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
				if (changed != null && device != null && changed.getDeviceId() == device.getDeviceId()) {
					device = null;
					worker.execute(RoadCrewTachoCardActivity.this::closeReader);
					reader = Reader.NONE;
					cardState = CardState.ABSENT;
					cardInfo = null;
					render();
				}
			}
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.roadcrew_tacho_card_activity);
		usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		bindViews();
		source = "bluetooth".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SOURCE, "usb"))
				? Source.BLUETOOTH : Source.USB;
		ble = new ViewModelProvider(this).get(RoadCrewTachoBluetoothModel.class);
		ble.getState().observe(this, this::bleChanged);

		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			registerReceiver(usbReceiver, filter);
		}
		handleIntent(getIntent());
		render();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleIntent(intent);
		if (visible) {
			findReader(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
		}
	}

	private void handleIntent(@Nullable Intent intent) {
		// adb triggers exist in development builds only: the activity is exported
		// for the USB reader, and in a release another app must not start a
		// download that marks the card (Codex, Test 118 review P1).
		boolean automation = intent != null && RoadCrewTachoDownloadFlow.acceptsAutomation(versionName());
		downloadRequested = automation && intent.getBooleanExtra(EXTRA_DOWNLOAD, false);
		// Every command and answer of the next download, for diagnosis and for
		// the card's twin in tests. The app's own folder, never uploaded.
		traceRequested = automation && intent.getBooleanExtra(EXTRA_TRACE, false);
		if (downloadRequested && cardState == CardState.DRIVER) {
			downloadRequested = false;
			startDownload();
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		visible = true;
		Intent intent = getIntent();
		findReader(intent == null ? null : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
		loadHistory();
	}

	@Override
	protected void onStop() {
		super.onStop();
		visible = false;
		// A download in progress keeps the reader; it lets go when it ends.
		if (!busy) {
			worker.execute(this::closeReader);
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		unregisterReceiver(usbReceiver);
		worker.execute(this::closeReader);
		worker.shutdown();
	}

	// ---- The reader ----------------------------------------------------------------------------

	private void findReader(@Nullable UsbDevice candidate) {
		// The intent that opened the screen may name a reader unplugged since.
		UsbDevice found = candidate != null && usbManager.getDeviceList().containsKey(candidate.getDeviceName())
				? candidate : null;
		if (found == null) {
			for (UsbDevice attached : usbManager.getDeviceList().values()) {
				// Any smart-card reader, not one model (Galin, 27.09).
				if (RoadCrewTachoCcidTransport.isCardReader(attached)) {
					found = attached;
					break;
				}
			}
		}
		if (found == null) {
			reader = Reader.NONE;
			render();
			return;
		}
		if (usbManager.hasPermission(found)) {
			attach(found);
			return;
		}
		reader = Reader.NO_PERMISSION;
		render();
		// UsbManager fills in EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED, so the
		// PendingIntent stays mutable - and explicit, as Android 14 requires.
		Intent request = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
		int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
		usbManager.requestPermission(found, PendingIntent.getBroadcast(this, 0, request, flags));
	}

	private void attach(@NonNull UsbDevice found) {
		device = found;
		worker.execute(() -> openReader(found));
	}

	/** Worker thread: claim the reader and poll its slot once a second. */
	private void openReader(@NonNull UsbDevice found) {
		if (session != null) {
			return;
		}
		UsbDeviceConnection opened = usbManager.openDevice(found);
		if (opened == null) {
			runOnUiThread(() -> {
				reader = Reader.NO_PERMISSION;
				render();
			});
			return;
		}
		try {
			session = RoadCrewTachoCardReader.Session.attach(found, opened);
			connection = opened;
		} catch (IOException e) {
			Log.w(TAG, "reader could not be opened: " + e.getMessage(), e);
			opened.close();
			runOnUiThread(() -> {
				readerDiagnostic = diagnostic(e);
				reader = Reader.NONE;
				render();
			});
			return;
		}
		cardSeen = false;
		runOnUiThread(() -> {
			readerDiagnostic = "";
			reader = Reader.CONNECTED;
			cardState = CardState.ABSENT;
			render();
		});
		polling = worker.scheduleWithFixedDelay(this::poll, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
	}

	/** Worker thread. */
	private void closeReader() {
		if (polling != null) {
			polling.cancel(false);
			polling = null;
		}
		if (session != null) {
			session.close();
			session = null;
		}
		if (connection != null) {
			connection.close();
			connection = null;
		}
		cardSeen = false;
	}

	/** Worker thread: notice a card going in or out; read what the screen shows about it. */
	private void poll() {
		RoadCrewTachoCardReader.Session current = session;
		if (current == null) {
			return;
		}
		boolean present;
		try {
			present = current.cardPresent();
		} catch (IOException e) {
			Log.w(TAG, "reader stopped answering: " + e.getMessage());
			closeReader();
			runOnUiThread(() -> {
				if (readerDiagnostic.isEmpty()) readerDiagnostic = diagnostic(e);
				reader = Reader.NONE;
				cardState = CardState.ABSENT;
				cardInfo = null;
				render();
			});
			return;
		}
		if (present == cardSeen) {
			return;
		}
		cardSeen = present;
		if (!present) {
			runOnUiThread(() -> {
				cardState = CardState.ABSENT;
				readerDiagnostic = "";
				cardInfo = null;
				render();
			});
			return;
		}
		runOnUiThread(() -> {
			readerDiagnostic = "";
			cardState = CardState.READING;
			render();
		});
		try (RoadCrewTachoCardReader.OpenCard card = current.powerOn()) {
			RoadCrewTachoCardDownload.CardInfo info =
					RoadCrewTachoCardDownload.readInfo(RoadCrewTachoCardReader.channel(card));
			if (info.driverCard) {
				rememberLastDownload(info.lastDownload);
			}
			runOnUiThread(() -> {
				cardInfo = info;
				cardState = info.driverCard ? CardState.DRIVER : CardState.OTHER;
				render();
				if (downloadRequested && info.driverCard) {
					downloadRequested = false;
					startDownload();
				}
			});
		} catch (IOException e) {
			Log.w(TAG, "card could not be read: " + e.getMessage(), e);
			runOnUiThread(() -> {
				readerDiagnostic = diagnostic(e);
				cardInfo = null;
				cardState = CardState.UNREADABLE;
				render();
			});
		}
	}

	private String diagnostic(IOException error) {
		// Protocol errors contain stages, lengths and status codes, never card contents.
		String detail = error.getMessage() == null ? "IO_ERROR" : error.getMessage();
		return getString(R.string.roadcrew_tacho_reader_diagnostic,
				detail.substring(0, Math.min(detail.length(), 180)));
	}

	// ---- The download --------------------------------------------------------------------------

	private void startDownload() {
		if (busy || reader != Reader.CONNECTED || cardState != CardState.DRIVER) {
			return;
		}
		busy = true;
		phase = Phase.DOWNLOADING;
		progressDone = 0;
		progressTotal = RoadCrewTachoCardDownload.FILES_G1 + RoadCrewTachoCardDownload.FILES_G2;
		progressTitle = getString(R.string.roadcrew_tacho_downloading);
		progressFile = "";
		boolean trace = traceRequested;
		traceRequested = false;
		RoadCrewTachoCardDownload.CardInfo info = cardInfo;
		render();
		worker.execute(() -> download(trace, info));
	}

	/**
	 * Worker thread: read, store and read back, then mark - through the tested
	 * RoadCrewTachoDownloadFlow - or say exactly what did not happen.
	 */
	private void download(boolean trace, @Nullable RoadCrewTachoCardDownload.CardInfo info) {
		StringBuilder traceLog = trace ? new StringBuilder() : null;
		long started = System.currentTimeMillis();
		RoadCrewTachoCardReader.Session current = session;
		try {
			if (current == null) {
				throw new IOException("the reader is not open");
			}
			try (RoadCrewTachoCardReader.OpenCard card = current.powerOn()) {
				RoadCrewTachoDownloadDate.Channel channel = traced(RoadCrewTachoCardReader.channel(card), traceLog);
				String[] storedName = {""};
				String[] cardNumber = {""};
				RoadCrewTachoDownloadFlow.Outcome<Stored> outcome = RoadCrewTachoDownloadFlow.run(channel,
						(done, total, fid, secondGeneration) -> runOnUiThread(() -> {
							progressDone = done;
							progressTotal = total;
							if (fid != 0) {
								progressFile = fileLabel(fid, secondGeneration);
							}
							render();
						}),
						result -> {
							runOnUiThread(() -> {
								progressTitle = getString(R.string.roadcrew_tacho_saving);
								render();
							});
							Stored stored = saveDdd(result);
							storedName[0] = stored.name;
							cardNumber[0] = result.cardNumber;
							Log.i(TAG, String.format(Locale.ROOT,
									"DDD saved %s bytes=%d gen2=%s files=%d absent=%s warnings=%s sha256=%s seconds=%d",
									stored.name, result.ddd.length, result.secondGeneration, result.storedTags.size(),
									result.absent, result.warnings, sha256(result.ddd),
									(System.currentTimeMillis() - started) / 1000));
							return stored;
						},
						() -> System.currentTimeMillis() / 1000L,
						// DDP_035: after the download, LastCardDownload in DF Tachograph and,
						// on a Gen2 card, Tachograph_G2. Galin, 25.09: automatically, once
						// the file is stored and read back - which it is by now.
						(before, requested) -> {
							runOnUiThread(() -> {
								progressTitle = getString(R.string.roadcrew_tacho_marking);
								render();
							});
							String audit = "MARK card=" + cardNumber[0] + " file=" + storedName[0]
									+ " before=" + before + " requested=" + requested;
							Log.i(TAG, audit);
							try (FileOutputStream out = openFileOutput("tacho-download-audit.txt", MODE_APPEND)) {
								out.write((audit + "\n").getBytes(StandardCharsets.UTF_8));
								out.getFD().sync();
							}
						});
				RoadCrewTachoCardDownload.Result result = outcome.result;
				Stored stored = outcome.stored;
				if (!outcome.marked) {
					// The chip reported damaged data: the file is kept for diagnosis, the card
					// is left as it was, and the driver is told (Codex, Test 118 review P1).
					Log.w(TAG, "DDD stored but the card was NOT marked: " + outcome.integrityWarnings);
					int places = outcome.integrityWarnings.size();
					runOnUiThread(() -> {
						integrityWarning(places);
						loadHistory();
					});
					return;
				}
				long now = outcome.markedAt;
				Log.i(TAG, "Marked as downloaded: " + RoadCrewTachoCardReader.describeDate(now)
						+ (result.secondGeneration ? " (Tachograph and Tachograph_G2)" : " (Tachograph)"));
				rememberLastDownload(now);
				runOnUiThread(() -> {
					phase = Phase.DONE;
					doneFile = stored;
					doneParts = result.storedTags.size();
					doneMarkedAt = now;
					doneHolder = info == null ? null : holderName(info);
					doneCardNumber = result.cardNumber;
					if (cardInfo != null) {
						cardInfo = new RoadCrewTachoCardDownload.CardInfo(cardInfo.driverCard,
								cardInfo.issuingMemberState, cardInfo.cardNumber, cardInfo.surname,
								cardInfo.firstNames, now);
					}
					render();
					loadHistory();
				});
			}
		} catch (IOException e) {
			RoadCrewTachoDownloadFlow.Stage stage = e instanceof RoadCrewTachoDownloadFlow.Failure
					? ((RoadCrewTachoDownloadFlow.Failure) e).stage : RoadCrewTachoDownloadFlow.Stage.READING;
			Log.w(TAG, "DDD download failed at " + stage + ": " + e.getMessage(), e);
			boolean removed;
			try {
				removed = current == null || !current.cardPresent();
			} catch (IOException gone) {
				removed = true;
			}
			RoadCrewTachoDownloadFlow.Stage failedAt = stage;
			boolean cardOrReaderGone = removed;
			String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			runOnUiThread(() -> failed(failedAt, cardOrReaderGone, reason));
		} finally {
			if (traceLog != null) {
				saveTrace(traceLog);
			}
			busy = false;
			if (!visible) {
				closeReader();
			}
		}
	}

	/** Stored, not marked: the card reported damaged data in {@code places} reads. */
	private void integrityWarning(int places) {
		phase = Phase.FAILED;
		errorTitle = getString(R.string.roadcrew_tacho_integrity_title);
		errorBody = getString(R.string.roadcrew_tacho_integrity_body, places);
		render();
	}

	private void failed(RoadCrewTachoDownloadFlow.Stage stage, boolean removed, String reason) {
		phase = Phase.FAILED;
		if (stage == RoadCrewTachoDownloadFlow.Stage.READING && removed) {
			errorTitle = getString(R.string.roadcrew_tacho_removed_title);
			errorBody = getString(R.string.roadcrew_tacho_removed_body);
		} else if (stage == RoadCrewTachoDownloadFlow.Stage.READING) {
			errorTitle = getString(R.string.roadcrew_tacho_failed_title);
			errorBody = getString(R.string.roadcrew_tacho_failed_body, reason);
		} else if (stage == RoadCrewTachoDownloadFlow.Stage.SAVING) {
			errorTitle = getString(R.string.roadcrew_tacho_save_failed_title);
			errorBody = getString(R.string.roadcrew_tacho_save_failed_body, reason);
		} else {
			errorTitle = getString(R.string.roadcrew_tacho_mark_failed_title);
			errorBody = getString(R.string.roadcrew_tacho_mark_failed_body, reason);
		}
		render();
		loadHistory();
	}

	@NonNull
	private static RoadCrewTachoDownloadDate.Channel traced(RoadCrewTachoDownloadDate.Channel direct,
			@Nullable StringBuilder trace) {
		if (trace == null) {
			return direct;
		}
		return command -> {
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
	}

	private void saveTrace(StringBuilder trace) {
		File folder = new File(getExternalFilesDir(null), "tacho-trace");
		String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
		File file = new File(folder, "trace_" + stamp + ".txt");
		if (!folder.isDirectory() && !folder.mkdirs()) {
			Log.w(TAG, "trace NOT saved: cannot create " + folder);
			return;
		}
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(trace.toString().getBytes(StandardCharsets.US_ASCII));
			out.getFD().sync();
			Log.i(TAG, "trace saved " + file + " bytes=" + file.length());
		} catch (IOException e) {
			Log.w(TAG, "trace NOT saved: " + e.getMessage(), e);
		}
	}

	/**
	 * Downloads/RoadCrew/C_yyyyMMdd_HHmm_card.ddd, where the driver can find
	 * and send it. Read back and compared before it counts as stored.
	 */
	private Stored saveDdd(RoadCrewTachoCardDownload.Result result) throws IOException {
		long takenAt = System.currentTimeMillis();
		String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(new Date(takenAt));
		String card = result.cardNumber.replaceAll("[^A-Za-z0-9]", "");
		String name = "C_" + stamp + "_" + (card.isEmpty() ? "card" : card) + ".ddd";
		byte[] stored;
		Uri uri;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			android.content.ContentResolver resolver = getContentResolver();
			android.content.ContentValues values = new android.content.ContentValues();
			values.put(MediaStore.Downloads.DISPLAY_NAME, name);
			values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
			values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
			values.put(MediaStore.Downloads.IS_PENDING, 1);
			uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
			if (uri == null) {
				throw new IOException("Could not create " + name + " in Downloads");
			}
			try (OutputStream out = resolver.openOutputStream(uri)) {
				if (out == null) {
					throw new IOException("Could not open " + name);
				}
				out.write(result.ddd);
			}
			values.clear();
			values.put(MediaStore.Downloads.IS_PENDING, 0);
			resolver.update(uri, values, null, null);
			try (InputStream in = resolver.openInputStream(uri)) {
				stored = readAll(in);
			}
		} else {
			File dir = new File(getExternalFilesDir(null), FOLDER);
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("Could not create " + dir);
			}
			// Never over an earlier download: a taken name gets " (1)", as
			// Android 10 and later do by themselves (Galin, 02.10.2026).
			File file = RoadCrewTachoFileNames.unused(dir, name);
			name = file.getName();
			try (FileOutputStream out = new FileOutputStream(file)) {
				out.write(result.ddd);
				out.getFD().sync();
			}
			try (InputStream in = new FileInputStream(file)) {
				stored = readAll(in);
			}
			uri = AndroidUtils.getUriForFile(this, file);
		}
		if (!Arrays.equals(stored, result.ddd)) {
			throw new IOException(name + " did not read back as written");
		}
		return new Stored(uri, name, takenAt, stored.length, result.secondGeneration);
	}

	private static byte[] readAll(@Nullable InputStream in) throws IOException {
		if (in == null) {
			throw new IOException("Stored file cannot be read back");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		for (int n; (n = in.read(buffer)) > 0; ) {
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	private static String sha256(byte[] data) {
		try {
			return RoadCrewTachoCardReader.toHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			return "?";
		}
	}

	/** A download date from the card or from this download; a newer one moves the reminders. */
	private void rememberLastDownload(long epochSeconds) {
		RoadCrewTachoReminder.remember(this, epochSeconds);
	}

	// ---- Files already downloaded -------------------------------------------------------------

	private void loadHistory() {
		new Thread(() -> {
			List<Stored> found = findStoredFiles();
			runOnUiThread(() -> {
				history.clear();
				history.addAll(found);
				render();
			});
		}, "RoadCrewTachoHistory").start();
	}

	private List<Stored> findStoredFiles() {
		List<Stored> found = new ArrayList<>();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
						MediaStore.Downloads.SIZE};
				String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND "
						+ MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND "
						+ MediaStore.Downloads.IS_PENDING + " = 0";
				String[] args = {Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%", "C%.ddd"};
				try (Cursor cursor = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
						projection, selection, args, MediaStore.Downloads.DISPLAY_NAME + " DESC")) {
					while (cursor != null && cursor.moveToNext() && found.size() < HISTORY_ROWS) {
						Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
						String name = cursor.getString(1);
						boolean gen2;
						try (InputStream in = getContentResolver().openInputStream(uri)) {
							gen2 = hasSecondGeneration(readAll(in));
						}
						found.add(new Stored(uri, name, takenAt(name), cursor.getLong(2), gen2));
					}
				}
			} else {
				File[] files = new File(getExternalFilesDir(null), FOLDER).listFiles(
						(dir, name) -> name.startsWith("C_") && name.endsWith(".ddd"));
				if (files != null) {
					Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
					for (File file : files) {
						if (found.size() >= HISTORY_ROWS) {
							break;
						}
						boolean gen2;
						try (InputStream in = new FileInputStream(file)) {
							gen2 = hasSecondGeneration(readAll(in));
						}
						found.add(new Stored(AndroidUtils.getUriForFile(this, file), file.getName(),
								takenAt(file.getName()), file.length(), gen2));
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "downloaded files could not be listed: " + e.getMessage(), e);
		}
		return found;
	}

	/** Any Tachograph_G2 part in the DDD: a data or signature tag with appendix 02/03 (DDP_046). */
	private static boolean hasSecondGeneration(byte[] ddd) {
		int i = 0;
		while (i + 5 <= ddd.length) {
			int appendix = ddd[i + 2] & 0xFF;
			if (appendix == 0x02 || appendix == 0x03) {
				return true;
			}
			i += 5 + (((ddd[i + 3] & 0xFF) << 8) | (ddd[i + 4] & 0xFF));
		}
		return false;
	}

	/** C_yyyyMMdd_HHmm_... as the phone's local time. */
	private static long takenAt(String name) {
		try {
			Date date = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).parse(name.substring(2, 15));
			return date == null ? 0 : date.getTime();
		} catch (ParseException | IndexOutOfBoundsException e) {
			return 0;
		}
	}

	// ---- Sending ---------------------------------------------------------------------------------

	/**
	 * Android's own share sheet with a ZIP holding the original DDD: the driver
	 * picks any app - Viber, WhatsApp, Gmail, Telegram (Galin, 25.09, option 1).
	 * ZIP because Viber refuses a .ddd file, even from the phone's own file
	 * manager, and accepts the same file zipped (tested on his phone).
	 */
	private void share(@NonNull Stored file) {
		Uri zip = prepareZip(file);
		if (zip == null) {
			Toast.makeText(this, R.string.roadcrew_tacho_send_failed, Toast.LENGTH_LONG).show();
			return;
		}
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("application/zip");
		send.putExtra(Intent.EXTRA_STREAM, zip);
		// Only e-mail uses a subject; messengers ignore it.
		send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.roadcrew_tacho_mail_subject, formatDateTime(file.takenAt)));
		send.setClipData(ClipData.newRawUri(RoadCrewTachoShareZip.zipName(file.name), zip));
		send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(send, getString(R.string.roadcrew_tacho_send_title)));
	}

	/**
	 * The ZIP written to the app's share cache, read back, and handed out
	 * through the FileProvider - Android's way of giving a file to another app.
	 */
	@Nullable
	private Uri prepareZip(@NonNull Stored file) {
		File folder = new File(getCacheDir(), "share");
		if (!folder.isDirectory() && !folder.mkdirs()) {
			Log.w(TAG, "share folder cannot be created: " + folder);
			return null;
		}
		try {
			byte[] ddd;
			try (InputStream in = getContentResolver().openInputStream(file.uri)) {
				ddd = readAll(in);
			}
			byte[] zip = RoadCrewTachoShareZip.zip(file.name, ddd, file.takenAt);
			RoadCrewTachoShareZip.verify(zip, file.name, ddd);
			File out = new File(folder, RoadCrewTachoShareZip.zipName(file.name));
			try (FileOutputStream stream = new FileOutputStream(out)) {
				stream.write(zip);
				stream.getFD().sync();
			}
			try (InputStream in = new FileInputStream(out)) {
				if (!Arrays.equals(readAll(in), zip)) {
					throw new IOException(out.getName() + " did not read back as written");
				}
			}
			return AndroidUtils.getUriForFile(this, out);
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "file cannot be prepared for sending: " + e.getMessage(), e);
			return null;
		}
	}

	// ---- The screen -----------------------------------------------------------------------------

	private void bindViews() {
		ImageButton back = findViewById(R.id.roadcrewTachoBack);
		back.setOnClickListener(v -> finish());
		findViewById(R.id.roadcrewTachoStatusCard).setBackground(
				shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 16, false));
		readerState = findViewById(R.id.roadcrewTachoReaderState);
		readerDetail = findViewById(R.id.roadcrewTachoReaderDetail);
		readerIcon = findViewById(R.id.roadcrewTachoReaderIcon);
		readerMark = findViewById(R.id.roadcrewTachoReaderMark);
		cardStateView = findViewById(R.id.roadcrewTachoCardState);
		cardDetail = findViewById(R.id.roadcrewTachoCardDetail);
		cardIcon = findViewById(R.id.roadcrewTachoCardIcon);
		cardMark = findViewById(R.id.roadcrewTachoCardMark);

		errorBlock = findViewById(R.id.roadcrewTachoError);
		errorBlock.setBackground(shape(R.color.roadcrew_tacho_err_bg, 0, 16, false));
		((ImageView) findViewById(R.id.roadcrewTachoErrorIcon)).setColorFilter(color(R.color.roadcrew_tacho_err));
		errorTitleView = findViewById(R.id.roadcrewTachoErrorTitle);
		errorBodyView = findViewById(R.id.roadcrewTachoErrorBody);

		doneBlock = findViewById(R.id.roadcrewTachoDone);
		findViewById(R.id.roadcrewTachoDoneBanner).setBackground(shape(R.color.roadcrew_tacho_ok_bg, 0, 16, false));
		ImageView check = findViewById(R.id.roadcrewTachoDoneCheck);
		check.setBackground(shape(R.color.roadcrew_tacho_ok, 0, 22, false));
		check.setColorFilter(Color.WHITE);
		doneMarkedView = findViewById(R.id.roadcrewTachoDoneMarked);
		doneDetails = findViewById(R.id.roadcrewTachoDoneDetails);
		doneDetails.setBackground(shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 16, false));

		progressBlock = findViewById(R.id.roadcrewTachoProgress);
		findViewById(R.id.roadcrewTachoProgressCard).setBackground(
				shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 16, false));
		findViewById(R.id.roadcrewTachoKeepCard).setBackground(shape(R.color.roadcrew_tacho_warn_bg, 0, 12, false));
		((ImageView) findViewById(R.id.roadcrewTachoKeepCardIcon)).setColorFilter(color(R.color.roadcrew_tacho_warn));
		progressTitleView = findViewById(R.id.roadcrewTachoProgressTitle);
		progressPercent = findViewById(R.id.roadcrewTachoProgressPercent);
		progressBar = findViewById(R.id.roadcrewTachoProgressBar);
		progressFileView = findViewById(R.id.roadcrewTachoProgressFile);
		progressCount = findViewById(R.id.roadcrewTachoProgressCount);

		lastBlock = findViewById(R.id.roadcrewTachoLast);
		lastBlock.setBackground(shape(R.color.roadcrew_tacho_warn_bg, 0, 12, false));
		lastText = findViewById(R.id.roadcrewTachoLastText);
		nextText = findViewById(R.id.roadcrewTachoNextText);

		remainingButton = findViewById(R.id.roadcrewTachoRemainingButton);
		remainingButton.setBackground(shape(R.color.roadcrew_tacho_accent, 0, 14, false));
		remainingButton.setTextColor(Color.WHITE);
		remainingButton.setOnClickListener(v -> openRemaining());
		openFileButton = findViewById(R.id.roadcrewTachoOpenFileButton);
		openFileButton.setBackground(shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 14, false));
		openFileButton.setOnClickListener(v -> pickFile());

		downloadButton = findViewById(R.id.roadcrewTachoDownloadButton);
		downloadButton.setOnClickListener(v -> {
			if (source == Source.BLUETOOTH) {
				startBluetooth();
			} else {
				startDownload();
			}
		});
		keepIconBesideText(downloadButton);
		hint = findViewById(R.id.roadcrewTachoHint);
		sendButton = findViewById(R.id.roadcrewTachoSendButton);
		sendButton.setBackground(shape(R.color.roadcrew_tacho_accent, 0, 14, false));
		sendButton.setTextColor(Color.WHITE);
		sendButton.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.roadcrew_tacho_ic_send, Color.WHITE),
				null, null, null);
		sendButton.setOnClickListener(v -> {
			if (doneFile != null) {
				share(doneFile);
			}
		});
		keepIconBesideText(sendButton);
		finishButton = findViewById(R.id.roadcrewTachoFinishButton);
		finishButton.setBackground(shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 14, false));
		finishButton.setOnClickListener(v -> {
			if (source == Source.BLUETOOTH) {
				bleLocalError = null;
				ble.reset();
			}
			phase = Phase.IDLE;
			render();
		});
		historyBlock = findViewById(R.id.roadcrewTachoHistory);
		historyRows = findViewById(R.id.roadcrewTachoHistoryRows);

		sourceBox = findViewById(R.id.roadcrewTachoSource);
		sourceUsb = findViewById(R.id.roadcrewTachoSourceUsb);
		sourceBluetooth = findViewById(R.id.roadcrewTachoSourceBluetooth);
		sourceUsb.setOnClickListener(v -> setSource(Source.USB));
		sourceBluetooth.setOnClickListener(v -> setSource(Source.BLUETOOTH));
		readerLabel = findViewById(R.id.roadcrewTachoReaderLabel);
		keepCardText = findViewById(R.id.roadcrewTachoKeepCardText);
		partBar = findViewById(R.id.roadcrewTachoPartBar);
		cancelButton = findViewById(R.id.roadcrewTachoCancelButton);
		cancelButton.setBackground(shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 14, false));
		cancelButton.setOnClickListener(v -> ble.cancel());
	}

	private void render() {
		renderSource();
		if (source == Source.BLUETOOTH) {
			renderBluetooth();
			return;
		}
		readerLabel.setText(R.string.roadcrew_tacho_reader);
		readerIcon.setImageResource(R.drawable.roadcrew_tacho_ic_usb);
		keepCardText.setText(R.string.roadcrew_tacho_keep_card);
		partBar.setVisibility(View.GONE);
		cancelButton.setVisibility(View.GONE);
		renderReader();
		renderCard();

		errorBlock.setVisibility(phase == Phase.FAILED ? View.VISIBLE : View.GONE);
		errorTitleView.setText(errorTitle);
		errorBodyView.setText(errorBody);

		doneBlock.setVisibility(phase == Phase.DONE ? View.VISIBLE : View.GONE);
		sendButton.setVisibility(phase == Phase.DONE ? View.VISIBLE : View.GONE);
		finishButton.setVisibility(phase == Phase.DONE ? View.VISIBLE : View.GONE);
		if (phase == Phase.DONE) {
			renderDone();
		}

		progressBlock.setVisibility(phase == Phase.DOWNLOADING ? View.VISIBLE : View.GONE);
		if (phase == Phase.DOWNLOADING) {
			int total = Math.max(1, progressTotal);
			progressTitleView.setText(progressTitle);
			progressPercent.setText(String.format(Locale.getDefault(), "%d%%", progressDone * 100 / total));
			progressBar.setProgress(progressDone * 1000 / total);
			progressFileView.setText(progressFile);
			progressCount.setText(getString(R.string.roadcrew_tacho_progress_count, progressDone, total));
		}

		boolean idle = phase == Phase.IDLE || phase == Phase.FAILED;
		renderLastDownload(idle);
		downloadButton.setVisibility(idle ? View.VISIBLE : View.GONE);
		hint.setVisibility(idle ? View.VISIBLE : View.GONE);
		boolean ready = reader == Reader.CONNECTED && cardState == CardState.DRIVER && !busy;
		downloadButton.setEnabled(ready);
		downloadButton.setBackground(shape(ready ? R.color.roadcrew_tacho_accent : R.color.roadcrew_tacho_off_bg, 0, 14, false));
		int buttonText = ready ? Color.WHITE : color(R.color.roadcrew_tacho_disabled_text);
		downloadButton.setTextColor(buttonText);
		downloadButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
				tinted(R.drawable.roadcrew_tacho_ic_download, buttonText), null, null, null);
		hint.setText(hintText());

		historyBlock.setVisibility(idle && !history.isEmpty() ? View.VISIBLE : View.GONE);
		renderHistory();

		boolean haveFile = idle && !history.isEmpty();
		remainingButton.setVisibility(haveFile ? View.VISIBLE : View.GONE);
		if (haveFile) {
			long at = history.get(0).takenAt;
			remainingButton.setText(at > 0
					? getString(R.string.roadcrew_tacho_remaining_button, RoadCrewTachoUi.dayTime(at / 60000))
					: getString(R.string.roadcrew_tacho_remaining_title));
		}
		openFileButton.setVisibility(idle ? View.VISIBLE : View.GONE);
	}

	// ---- Bluetooth: the tachograph itself (ROADMAP 410-411) --------------------------------------

	private void setSource(Source chosen) {
		if (busy || (bleState != null && bleState.getBusy())) {
			return;
		}
		source = chosen;
		bleLocalError = null;
		getSharedPreferences(PREFS, MODE_PRIVATE).edit()
				.putString(KEY_SOURCE, chosen == Source.BLUETOOTH ? "bluetooth" : "usb").apply();
		render();
	}

	private void renderSource() {
		boolean bluetooth = source == Source.BLUETOOTH;
		sourceBox.setBackground(shape(R.color.roadcrew_tacho_off_bg, 0, 12, false));
		styleSource(sourceUsb, !bluetooth);
		styleSource(sourceBluetooth, bluetooth);
	}

	private void styleSource(Button button, boolean selected) {
		button.setBackground(selected ? shape(R.color.roadcrew_tacho_surface, 0, 10, false) : null);
		button.setTextColor(color(selected ? R.color.roadcrew_tacho_text : R.color.roadcrew_tacho_secondary));
		button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
	}

	/** One button: the model finds the tachograph, pairs it through Android if new, and downloads. */
	private void startBluetooth() {
		List<String> missing = new ArrayList<>();
		for (String permission : bluetoothPermissions()) {
			if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
				missing.add(permission);
			}
		}
		if (!missing.isEmpty()) {
			requestPermissions(missing.toArray(new String[0]), REQUEST_BLUETOOTH);
			return;
		}
		bleLocalError = null;
		ble.start();
	}

	private static String[] bluetoothPermissions() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
		}
		return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != REQUEST_BLUETOOTH) {
			return;
		}
		boolean granted = grantResults.length > 0;
		for (int result : grantResults) {
			granted &= result == PackageManager.PERMISSION_GRANTED;
		}
		if (granted) {
			bleLocalError = null;
			ble.start();
		} else {
			bleLocalError = getString(R.string.roadcrew_tacho_ble_permission);
			render();
		}
	}

	private void bleChanged(RoadCrewTachoBluetoothModel.State state) {
		RoadCrewTachoBluetoothModel.Step before = bleState == null ? null : bleState.getStep();
		bleState = state;
		RoadCrewTachoBluetoothModel.Step step = state.getStep();
		if (state.getBusy()) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		if (step == RoadCrewTachoBluetoothModel.Step.DONE && before != step) {
			loadHistory();
		}
		if (step == RoadCrewTachoBluetoothModel.Step.CHOOSING && before != step) {
			chooseTachograph(state.getChoices());
		}
		render();
	}

	/** Several unpaired tachographs heard (a parking lot): nearest first, only tachographs. */
	private void chooseTachograph(List<RoadCrewTachoBleChoice.Candidate> choices) {
		String[] labels = new String[choices.size()];
		for (int i = 0; i < labels.length; i++) {
			labels[i] = choices.get(i).name;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.roadcrew_tacho_ble_which)
				.setItems(labels, (dialog, which) -> ble.startWith(choices.get(which).address))
				.setNegativeButton(R.string.roadcrew_tacho_ble_cancel, (dialog, which) -> ble.reset())
				.setOnCancelListener(dialog -> ble.reset())
				.show();
	}

	@Nullable
	private BluetoothAdapter bluetoothAdapter() {
		BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
		return manager == null ? null : manager.getAdapter();
	}

	private void renderBluetooth() {
		RoadCrewTachoBluetoothModel.State s = bleState == null ? new RoadCrewTachoBluetoothModel.State() : bleState;
		RoadCrewTachoBluetoothModel.Step step = s.getStep();
		boolean bleBusy = s.getBusy();
		readerLabel.setText(R.string.roadcrew_tacho_tachograph);
		readerIcon.setImageResource(R.drawable.ic_action_bluetooth);
		renderTachograph(s);
		renderBleCard(s);

		boolean failed = step == RoadCrewTachoBluetoothModel.Step.FAILED || bleLocalError != null;
		errorBlock.setVisibility(failed ? View.VISIBLE : View.GONE);
		if (failed) {
			errorTitleView.setText(bleLocalError != null ? bleLocalError : bleErrorTitle(s.getError()));
			errorBodyView.setText(bleLocalError != null ? "" : bleErrorBody(s.getError()));
		}
		boolean done = step == RoadCrewTachoBluetoothModel.Step.DONE && s.getFile() != null;
		doneBlock.setVisibility(done ? View.VISIBLE : View.GONE);
		sendButton.setVisibility(done ? View.VISIBLE : View.GONE);
		finishButton.setVisibility(done ? View.VISIBLE : View.GONE);
		if (done) {
			renderBleDone(s);
		}

		progressBlock.setVisibility(bleBusy ? View.VISIBLE : View.GONE);
		partBar.setVisibility(step == RoadCrewTachoBluetoothModel.Step.RECEIVING ? View.VISIBLE : View.GONE);
		keepCardText.setText(R.string.roadcrew_tacho_ble_keep);
		if (bleBusy) {
			renderBleProgress(s);
		}
		cancelButton.setVisibility(bleBusy ? View.VISIBLE : View.GONE);

		boolean idle = !bleBusy && !done;
		renderLastDownload(idle);
		downloadButton.setVisibility(idle ? View.VISIBLE : View.GONE);
		hint.setVisibility(idle ? View.VISIBLE : View.GONE);
		hint.setText(R.string.roadcrew_tacho_ble_hint);
		BluetoothAdapter adapter = bluetoothAdapter();
		boolean ready = adapter != null && adapter.isEnabled() && !bleBusy && !busy;
		downloadButton.setEnabled(ready);
		downloadButton.setBackground(shape(ready ? R.color.roadcrew_tacho_accent : R.color.roadcrew_tacho_off_bg, 0, 14, false));
		int buttonText = ready ? Color.WHITE : color(R.color.roadcrew_tacho_disabled_text);
		downloadButton.setTextColor(buttonText);
		downloadButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
				tinted(R.drawable.roadcrew_tacho_ic_download, buttonText), null, null, null);

		historyBlock.setVisibility(idle && !history.isEmpty() ? View.VISIBLE : View.GONE);
		renderHistory();
		boolean haveFile = idle && !history.isEmpty();
		remainingButton.setVisibility(haveFile ? View.VISIBLE : View.GONE);
		if (haveFile) {
			long at = history.get(0).takenAt;
			remainingButton.setText(at > 0
					? getString(R.string.roadcrew_tacho_remaining_button, RoadCrewTachoUi.dayTime(at / 60000))
					: getString(R.string.roadcrew_tacho_remaining_title));
		}
		openFileButton.setVisibility(idle ? View.VISIBLE : View.GONE);
	}

	private void renderTachograph(RoadCrewTachoBluetoothModel.State s) {
		String device = s.getDevice() == null ? "" : s.getDevice();
		switch (s.getStep()) {
			case SEARCHING:
				statusIcon(readerIcon, readerMark, readerState, false);
				readerState.setText(R.string.roadcrew_tacho_ble_searching_state);
				readerDetail.setText("");
				return;
			case PAIRING:
				statusIcon(readerIcon, readerMark, readerState, false);
				readerState.setText(R.string.roadcrew_tacho_ble_pairing_state);
				readerDetail.setText(device);
				return;
			case CONNECTING:
				statusIcon(readerIcon, readerMark, readerState, true);
				readerState.setText(R.string.roadcrew_tacho_ble_connecting_state);
				readerDetail.setText(device);
				return;
			case READING_CARD:
			case RECEIVING:
			case SAVING:
				statusIcon(readerIcon, readerMark, readerState, true);
				readerState.setText(R.string.roadcrew_tacho_ble_connected);
				readerDetail.setText(device);
				return;
			default:
				break;
		}
		BluetoothAdapter adapter = bluetoothAdapter();
		if (adapter == null || !adapter.isEnabled()) {
			statusIcon(readerIcon, readerMark, readerState, false);
			readerState.setText(R.string.roadcrew_tacho_ble_off);
			readerDetail.setText(R.string.roadcrew_tacho_ble_off_detail);
			return;
		}
		List<String> paired = pairedTachographs(adapter);
		if (paired == null) {
			// Android does not let us read the paired list yet: the button asks for that.
			statusIcon(readerIcon, readerMark, readerState, false);
			readerState.setText(R.string.roadcrew_tacho_source_bluetooth);
			readerDetail.setText(R.string.roadcrew_tacho_ble_not_paired_detail);
		} else if (paired.size() == 1) {
			statusIcon(readerIcon, readerMark, readerState, true);
			readerState.setText(R.string.roadcrew_tacho_ble_paired);
			readerDetail.setText(paired.get(0));
		} else if (paired.size() > 1) {
			statusIcon(readerIcon, readerMark, readerState, true);
			readerState.setText(getString(R.string.roadcrew_tacho_ble_paired_several, paired.size()));
			readerDetail.setText(R.string.roadcrew_tacho_ble_paired_several_detail);
		} else {
			statusIcon(readerIcon, readerMark, readerState, false);
			readerState.setText(R.string.roadcrew_tacho_ble_not_paired);
			readerDetail.setText(R.string.roadcrew_tacho_ble_not_paired_detail);
		}
	}

	/** Paired tachographs by name; null while Android does not let us read the list. */
	@Nullable
	private List<String> pairedTachographs(BluetoothAdapter adapter) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				&& checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
			return null;
		}
		List<String> names = new ArrayList<>();
		try {
			for (BluetoothDevice device : adapter.getBondedDevices()) {
				if (RoadCrewTachoBleChoice.isTachograph(device.getName())) {
					names.add(device.getName());
				}
			}
		} catch (SecurityException e) {
			return null;
		}
		return names;
	}

	private void renderBleCard(RoadCrewTachoBluetoothModel.State s) {
		RoadCrewTachoBluetoothModel.Step step = s.getStep();
		if (step == RoadCrewTachoBluetoothModel.Step.READING_CARD || step == RoadCrewTachoBluetoothModel.Step.RECEIVING
				|| step == RoadCrewTachoBluetoothModel.Step.SAVING) {
			statusIcon(cardIcon, cardMark, cardStateView, true);
			cardStateView.setText(R.string.roadcrew_tacho_card_reading);
			cardDetail.setText(R.string.roadcrew_tacho_card_driver);
		} else if (step == RoadCrewTachoBluetoothModel.Step.DONE) {
			statusIcon(cardIcon, cardMark, cardStateView, true);
			cardStateView.setText(R.string.roadcrew_tacho_ble_card_done);
			cardDetail.setText(R.string.roadcrew_tacho_card_driver);
		} else {
			statusIcon(cardIcon, cardMark, cardStateView, false);
			cardStateView.setText(R.string.roadcrew_tacho_ble_card_in);
			cardDetail.setText(R.string.roadcrew_tacho_ble_card_in_detail);
		}
	}

	private void renderBleProgress(RoadCrewTachoBluetoothModel.State s) {
		String title;
		String line = "";
		String count = "";
		String percent = "";
		int bar = 0;
		switch (s.getStep()) {
			case SEARCHING:
				title = getString(R.string.roadcrew_tacho_ble_searching);
				break;
			case PAIRING:
				title = getString(R.string.roadcrew_tacho_ble_pairing, s.getDevice());
				line = getString(R.string.roadcrew_tacho_ble_pairing_body);
				break;
			case CONNECTING:
				title = getString(R.string.roadcrew_tacho_ble_connecting);
				if (s.getRetrying()) {
					line = getString(R.string.roadcrew_tacho_ble_retry, s.getAttempt(), RoadCrewTachoBleChoice.ATTEMPTS);
				}
				break;
			case READING_CARD:
				title = getString(R.string.roadcrew_tacho_ble_reading_card);
				line = getString(R.string.roadcrew_tacho_ble_reading_detail);
				break;
			case RECEIVING:
				title = getString(R.string.roadcrew_tacho_ble_receiving);
				bar = s.getPermille();
				percent = String.format(Locale.getDefault(), "%d%%", bar / 10);
				line = getString(R.string.roadcrew_tacho_ble_part, fileLabel(s.getFileId(), s.getSecondGeneration()),
						s.getFilePercent());
				count = getString(R.string.roadcrew_tacho_progress_count,
						Math.min(s.getFilesDone() + 1, s.getFilesTotal()), s.getFilesTotal());
				partBar.setProgress(s.getFilePercent());
				break;
			default:
				title = getString(R.string.roadcrew_tacho_ble_saving);
				bar = 1000;
				percent = "100%";
				break;
		}
		progressTitleView.setText(title);
		progressPercent.setText(percent);
		progressBar.setProgress(bar);
		progressFileView.setText(line);
		progressCount.setText(count);
	}

	private void renderBleDone(RoadCrewTachoBluetoothModel.State s) {
		RoadCrewTachoBluetoothStore.Stored file = s.getFile();
		doneMarkedView.setText(getString(R.string.roadcrew_tacho_ble_done_via,
				s.getDevice() == null ? "" : s.getDevice(), formatDateTime(file.getTakenAt())));
		doneDetails.removeAllViews();
		detailRow(R.string.roadcrew_tacho_detail_generation,
				getString(s.getSecondGeneration() ? R.string.roadcrew_tacho_gen12 : R.string.roadcrew_tacho_gen1));
		detailRow(R.string.roadcrew_tacho_detail_file, getString(R.string.roadcrew_tacho_file_size_parts,
				Formatter.formatShortFileSize(this, file.getSize()), s.getFilesTotal()));
		detailRow(R.string.roadcrew_tacho_detail_folder, getString(R.string.roadcrew_tacho_folder));
		// "Изпрати" sends the file just downloaded.
		doneFile = new Stored(file.getUri(), file.getName(), file.getTakenAt(), file.getSize(), s.getSecondGeneration());
	}

	private String bleErrorTitle(@Nullable String error) {
		String e = error == null ? "" : error;
		if (e.contains("Bluetooth is off")) {
			return getString(R.string.roadcrew_tacho_ble_off);
		}
		if (e.contains("No tachograph nearby")) {
			return getString(R.string.roadcrew_tacho_ble_none_found);
		}
		return getString(R.string.roadcrew_tacho_ble_failed);
	}

	private String bleErrorBody(@Nullable String error) {
		String e = error == null ? "" : error;
		if (e.contains("Bluetooth is off")) {
			return getString(R.string.roadcrew_tacho_ble_off_body);
		}
		if (e.contains("No tachograph nearby")) {
			return getString(R.string.roadcrew_tacho_ble_none_found_body);
		}
		if (e.startsWith("Pairing")) {
			return getString(R.string.roadcrew_tacho_ble_pairing_failed_body);
		}
		return getString(R.string.roadcrew_tacho_ble_failed_body, e.substring(0, Math.min(e.length(), 180)));
	}

	// ---- How much is left (ROADMAP 408) ----------------------------------------------------------

	/** The newest file only: an older one would count from an older moment. */
	private void openRemaining() {
		if (history.isEmpty()) {
			return;
		}
		Stored newest = history.get(0);
		RoadCrewTachoRemainingActivity.open(this, newest.uri, newest.takenAt);
	}

	/** A .DDD another app downloaded - Tacho Manager keeps them in Downloads/TachoManager. */
	private void pickFile() {
		Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		pick.addCategory(Intent.CATEGORY_OPENABLE);
		pick.setType("*/*");
		try {
			startActivityForResult(pick, REQUEST_OPEN_FILE);
		} catch (ActivityNotFoundException e) {
			Log.w(TAG, "no document picker: " + e.getMessage());
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_OPEN_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
			return;
		}
		Uri picked = data.getData();
		String name = displayName(picked);
		RoadCrewTachoRemainingActivity.open(this, picked, takenAt(name));
	}

	@NonNull
	private String displayName(@NonNull Uri uri) {
		try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				String name = cursor.getString(0);
				return name == null ? "" : name;
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "picked file has no name: " + e.getMessage());
		}
		return "";
	}

	private void renderReader() {
		boolean ok = reader == Reader.CONNECTED;
		statusIcon(readerIcon, readerMark, readerState, ok);
		if (reader == Reader.CONNECTED) {
			readerState.setText(R.string.roadcrew_tacho_reader_connected);
			readerDetail.setText(device != null && device.getProductName() != null ? device.getProductName() : "ACR39U");
		} else if (reader == Reader.NO_PERMISSION) {
			readerState.setText(R.string.roadcrew_tacho_reader_permission);
			readerDetail.setText(R.string.roadcrew_tacho_reader_permission_detail);
		} else {
			readerState.setText(R.string.roadcrew_tacho_reader_none);
			readerDetail.setText(readerDiagnostic.isEmpty()
					? getString(R.string.roadcrew_tacho_reader_none_detail) : readerDiagnostic);
		}
	}

	private void renderCard() {
		if (reader != Reader.CONNECTED) {
			statusIcon(cardIcon, cardMark, cardStateView, false);
			cardStateView.setText(R.string.roadcrew_tacho_card_unknown);
			cardDetail.setText(R.string.roadcrew_tacho_card_unknown_detail);
			return;
		}
		statusIcon(cardIcon, cardMark, cardStateView, cardState == CardState.DRIVER || cardState == CardState.READING);
		switch (cardState) {
			case READING:
				cardStateView.setText(R.string.roadcrew_tacho_card_present);
				cardDetail.setText(R.string.roadcrew_tacho_card_reading);
				break;
			case DRIVER:
				cardStateView.setText(R.string.roadcrew_tacho_card_present);
				String name = cardInfo == null ? "" : holderName(cardInfo);
				cardDetail.setText(name.isEmpty() ? getString(R.string.roadcrew_tacho_card_driver)
						: getString(R.string.roadcrew_tacho_card_driver_named, name));
				break;
			case OTHER:
				cardStateView.setText(R.string.roadcrew_tacho_card_other);
				cardDetail.setText(R.string.roadcrew_tacho_card_other_detail);
				break;
			case UNREADABLE:
				cardStateView.setText(R.string.roadcrew_tacho_card_unreadable);
				cardDetail.setText(readerDiagnostic.isEmpty()
						? getString(R.string.roadcrew_tacho_card_unreadable_detail) : readerDiagnostic);
				break;
			default:
				cardStateView.setText(R.string.roadcrew_tacho_card_absent);
				cardDetail.setText(R.string.roadcrew_tacho_card_absent_detail);
				break;
		}
	}

	private void statusIcon(ImageView icon, ImageView mark, TextView state, boolean ok) {
		int fg = color(ok ? R.color.roadcrew_tacho_ok : R.color.roadcrew_tacho_off);
		icon.setBackground(shape(ok ? R.color.roadcrew_tacho_ok_bg : R.color.roadcrew_tacho_off_bg, 0, 12, false));
		icon.setColorFilter(fg);
		mark.setImageResource(ok ? R.drawable.roadcrew_tacho_ic_check : R.drawable.roadcrew_tacho_ic_cross);
		mark.setColorFilter(fg);
		state.setTextColor(fg);
	}

	private String hintText() {
		if (reader == Reader.NONE) {
			return getString(R.string.roadcrew_tacho_hint_no_reader);
		}
		if (reader == Reader.NO_PERMISSION) {
			return getString(R.string.roadcrew_tacho_hint_permission);
		}
		switch (cardState) {
			case READING:
				return getString(R.string.roadcrew_tacho_hint_reading);
			case DRIVER:
				return getString(R.string.roadcrew_tacho_hint_ready);
			case OTHER:
				return getString(R.string.roadcrew_tacho_hint_other);
			case UNREADABLE:
				return getString(R.string.roadcrew_tacho_card_unreadable_detail);
			default:
				return getString(R.string.roadcrew_tacho_hint_no_card);
		}
	}

	/** From the inserted driver card; with none, the latest this phone knows of. */
	private void renderLastDownload(boolean idle) {
		long last;
		if (cardState == CardState.DRIVER && cardInfo != null) {
			last = cardInfo.lastDownload;
		} else {
			last = RoadCrewTachoReminder.lastDownload(this);
		}
		boolean known = last >= 0 && (cardState == CardState.DRIVER || last > 0);
		lastBlock.setVisibility(idle && known ? View.VISIBLE : View.GONE);
		if (!known) {
			return;
		}
		if (last == 0) {
			lastText.setText(R.string.roadcrew_tacho_never);
			nextText.setVisibility(View.GONE);
			return;
		}
		lastText.setText(getString(R.string.roadcrew_tacho_last, formatDateTime(last * 1000L)));
		nextText.setVisibility(View.VISIBLE);
		nextText.setText(getString(R.string.roadcrew_tacho_next,
				formatDate(RoadCrewTachoReminderPlan.deadline(last, java.util.TimeZone.getDefault()))));
	}

	private void renderDone() {
		doneMarkedView.setText(getString(R.string.roadcrew_tacho_done_marked, formatDateTime(doneMarkedAt * 1000L)));
		doneDetails.removeAllViews();
		if (doneHolder != null && !doneHolder.isEmpty()) {
			detailRow(R.string.roadcrew_tacho_detail_driver, doneHolder);
		}
		if (doneCardNumber != null && !doneCardNumber.isEmpty()) {
			detailRow(R.string.roadcrew_tacho_detail_card, doneCardNumber);
		}
		if (doneFile != null) {
			detailRow(R.string.roadcrew_tacho_detail_generation,
					getString(doneFile.secondGeneration ? R.string.roadcrew_tacho_gen12 : R.string.roadcrew_tacho_gen1));
			detailRow(R.string.roadcrew_tacho_detail_file, getString(R.string.roadcrew_tacho_file_size_parts,
					Formatter.formatShortFileSize(this, doneFile.size), doneParts));
		}
		detailRow(R.string.roadcrew_tacho_detail_folder, getString(R.string.roadcrew_tacho_folder));
	}

	private void detailRow(@StringRes int label, String value) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		int vertical = dp(11);
		row.setPadding(0, vertical, 0, vertical);
		TextView key = new TextView(this);
		key.setText(label);
		key.setTextSize(15);
		key.setTextColor(color(R.color.roadcrew_tacho_secondary));
		TextView text = new TextView(this);
		text.setText(value);
		text.setTextSize(15);
		text.setTextColor(color(R.color.roadcrew_tacho_text));
		text.setGravity(Gravity.END);
		LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
		valueParams.setMarginStart(dp(12));
		row.addView(key);
		row.addView(text, valueParams);
		if (doneDetails.getChildCount() > 0) {
			View line = new View(this);
			line.setBackgroundColor(color(R.color.roadcrew_tacho_line));
			doneDetails.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
		}
		doneDetails.addView(row);
	}

	private void renderHistory() {
		historyRows.removeAllViews();
		for (Stored file : history) {
			View line = new View(this);
			line.setBackgroundColor(color(R.color.roadcrew_tacho_line));
			historyRows.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

			LinearLayout row = new LinearLayout(this);
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(0, dp(10), 0, dp(10));

			ImageView icon = new ImageView(this);
			icon.setImageResource(R.drawable.roadcrew_tacho_ic_file);
			icon.setColorFilter(color(R.color.roadcrew_tacho_secondary));
			icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
			row.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

			LinearLayout texts = new LinearLayout(this);
			texts.setOrientation(LinearLayout.VERTICAL);
			TextView when = new TextView(this);
			when.setText(file.takenAt > 0 ? formatDateTime(file.takenAt) : file.name);
			when.setTextSize(15);
			when.setTextColor(color(R.color.roadcrew_tacho_text));
			TextView what = new TextView(this);
			what.setText(Formatter.formatShortFileSize(this, file.size) + " · "
					+ getString(file.secondGeneration ? R.string.roadcrew_tacho_gen12 : R.string.roadcrew_tacho_gen1));
			what.setTextSize(13);
			what.setTextColor(color(R.color.roadcrew_tacho_secondary));
			texts.addView(when);
			texts.addView(what);
			LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
			textParams.setMarginStart(dp(12));
			row.addView(texts, textParams);

			// Galin, 09.10.2026: "Анализ" beside "Изпрати" - this file's violations and days (ROADMAP 381).
			ImageButton analysis = new ImageButton(this);
			analysis.setImageResource(R.drawable.roadcrew_tacho_ic_chart);
			analysis.setColorFilter(color(R.color.roadcrew_tacho_accent));
			analysis.setBackground(shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 12, false));
			analysis.setContentDescription(getString(R.string.roadcrew_tacho_analysis));
			analysis.setOnClickListener(v -> RoadCrewTachoAnalysisActivity.open(this, file.uri, file.takenAt));
			LinearLayout.LayoutParams analysisParams = new LinearLayout.LayoutParams(dp(44), dp(44));
			analysisParams.setMarginEnd(dp(8));
			row.addView(analysis, analysisParams);

			ImageButton send = new ImageButton(this);
			send.setImageResource(R.drawable.roadcrew_tacho_ic_send);
			send.setColorFilter(color(R.color.roadcrew_tacho_accent));
			send.setBackground(shape(R.color.roadcrew_tacho_surface, R.color.roadcrew_tacho_line, 12, false));
			send.setContentDescription(getString(R.string.roadcrew_tacho_send));
			send.setOnClickListener(v -> share(file));
			row.addView(send, new LinearLayout.LayoutParams(dp(44), dp(44)));
			historyRows.addView(row);
		}
	}

	private String fileLabel(int fid, boolean secondGeneration) {
		String key = "roadcrew_tacho_file_" + String.format(Locale.ROOT, "%04x", fid);
		int id = getResources().getIdentifier(key, "string", getPackageName());
		String name = id == 0 ? String.format(Locale.ROOT, "%04X", fid) : getString(id);
		return getString(R.string.roadcrew_tacho_progress_file, secondGeneration ? 2 : 1, name);
	}

	@Nullable
	private String versionName() {
		try {
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (android.content.pm.PackageManager.NameNotFoundException e) {
			return null;
		}
	}

	private static String holderName(@NonNull RoadCrewTachoCardDownload.CardInfo info) {
		return (info.firstNames + " " + info.surname).trim();
	}

	private static String formatDateTime(long millis) {
		return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
	}

	private static String formatDate(long millis) {
		return new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(new Date(millis));
	}

	private int color(@ColorRes int id) {
		return getResources().getColor(id, getTheme());
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private GradientDrawable shape(@ColorRes int fill, @ColorRes int stroke, int radiusDp, boolean topOnly) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(color(fill));
		if (stroke != 0) {
			drawable.setStroke(dp(1), color(stroke));
		}
		float r = dp(radiusDp);
		if (topOnly) {
			drawable.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
		} else {
			drawable.setCornerRadius(r);
		}
		return drawable;
	}

	/** A Button draws its start icon at the edge; pad both sides so icon and text sit together in the middle. */
	private static void keepIconBesideText(@NonNull Button button) {
		button.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			Drawable icon = button.getCompoundDrawablesRelative()[0];
			if (icon == null) {
				return;
			}
			float text = button.getPaint().measureText(button.getText().toString());
			int content = Math.round(icon.getIntrinsicWidth() + button.getCompoundDrawablePadding() + text);
			int side = Math.max(0, (button.getWidth() - content) / 2);
			if (button.getPaddingStart() != side || button.getPaddingEnd() != side) {
				button.setPaddingRelative(side, button.getPaddingTop(), side, button.getPaddingBottom());
			}
		});
	}

	@Nullable
	private Drawable tinted(@DrawableRes int id, int tint) {
		Drawable drawable = getDrawable(id);
		if (drawable == null) {
			return null;
		}
		drawable = drawable.mutate();
		drawable.setTint(tint);
		return drawable;
	}
}
