package org.roadcrew.migration;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final String RELEASE_SHA256 =
			"18ACE3D71CE155E20C2ADD395F42B0088D44AABF381DB8FD6BD0F047AB331481";
	private static final long RELEASE_VERSION_CODE = 5400;
	private static final int REQUEST_PERMISSION = 100;
	private static final int REQUEST_UNINSTALL = 101;
	private static final int REQUEST_INSTALL = 102;
	private static final String TEST_78_URL =
			"https://github.com/Bokovoto/OsmAnd/releases/download/roadcrew-v0.1.0-test.78/RoadCrew.apk";

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private TextView status;
	private ProgressBar progress;
	private Button primary;
	private Button close;
	private File releaseApk;
	private boolean prepared;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		createUi();
		prepareReleaseApk();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (prepared) {
			renderState();
		}
	}

	@Override
	protected void onDestroy() {
		executor.shutdownNow();
		super.onDestroy();
	}

	private void createUi() {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setGravity(Gravity.CENTER_HORIZONTAL);
		root.setPadding(dp(28), dp(48), dp(28), dp(28));
		root.setBackgroundColor(Color.rgb(5, 9, 8));

		TextView brand = text("RoadCrew", 38, Color.WHITE, Typeface.BOLD);
		root.addView(brand, matchWrap());

		TextView subtitle = text("Защитено обновяване", 18, Color.rgb(33, 217, 149), Typeface.BOLD);
		LinearLayout.LayoutParams subtitleParams = matchWrap();
		subtitleParams.topMargin = dp(8);
		root.addView(subtitle, subtitleParams);

		status = text("Подготовка на Test 79...", 19, Color.WHITE, Typeface.NORMAL);
		status.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
		statusParams.topMargin = dp(36);
		root.addView(status, statusParams);

		progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		progress.setIndeterminate(true);
		root.addView(progress, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));

		primary = button("Продължи", true);
		primary.setEnabled(false);
		primary.setOnClickListener(v -> continueMigration());
		LinearLayout.LayoutParams primaryParams = matchWrap();
		primaryParams.topMargin = dp(28);
		primaryParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
		primaryParams.height = dp(64);
		root.addView(primary, primaryParams);

		close = button("Затваряне", false);
		close.setOnClickListener(v -> finish());
		LinearLayout.LayoutParams closeParams = matchWrap();
		closeParams.topMargin = dp(12);
		closeParams.width = LinearLayout.LayoutParams.MATCH_PARENT;
		closeParams.height = dp(58);
		root.addView(close, closeParams);

		setContentView(root);
	}

	private void prepareReleaseApk() {
		executor.execute(() -> {
			try {
				File directory = new File(getCacheDir(), "roadcrew-migration");
				if (!directory.isDirectory() && !directory.mkdirs()) {
					throw new IllegalStateException("Cannot create migration directory");
				}
				releaseApk = new File(directory, "RoadCrew-Test-79.apk");
				if (!isExpectedReleaseApk(releaseApk)) {
					copyEmbeddedRelease(releaseApk);
				}
				if (!isExpectedReleaseApk(releaseApk)) {
					throw new SecurityException("Unexpected RoadCrew certificate");
				}
				runOnUiThread(() -> {
					prepared = true;
					progress.setVisibility(View.GONE);
					renderState();
				});
			} catch (Exception e) {
				runOnUiThread(() -> {
					progress.setVisibility(View.GONE);
					status.setText("Запазеният Test 79 липсва или не премина проверката. Използвайте пълния RoadCrew migration файл.");
				});
			}
		});
	}

	private void copyEmbeddedRelease(File destination) throws Exception {
		try (InputStream input = getAssets().open("RoadCrew.apk");
			 FileOutputStream output = new FileOutputStream(destination)) {
			byte[] buffer = new byte[128 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				output.write(buffer, 0, read);
			}
		}
	}

	private void renderState() {
		if (isSecureRoadCrewInstalled()) {
			status.setText("Test 79 е инсталиран със защитения RoadCrew подпис.");
			primary.setText("Премахни помощника");
			primary.setEnabled(true);
			primary.setOnClickListener(v -> uninstallSelf());
		} else if (isPackageInstalled(ROADCREW_PACKAGE)) {
			status.setText("Старата тестова версия трябва да бъде премахната еднократно. В системния прозорец НЕ запазвайте данните, защото съдържат стария подпис. След миграцията настройте отново профила и офлайн картата.");
			primary.setText("Деинсталирай без запазване на данните");
			primary.setEnabled(true);
			primary.setOnClickListener(v -> continueMigration());
		} else if (hasRetainedRoadCrewData()) {
			status.setText("Android е запазил данните и стария подпис на Test 78. Инсталирайте Test 78 отново, повторете деинсталирането и махнете отметката за запазване на данните.");
			primary.setText("Изтегли отново Test 78");
			primary.setEnabled(true);
			primary.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TEST_78_URL))));
		} else {
			status.setText("Старата версия е премахната. Продължете с инсталирането на Test 79.");
			primary.setText("Инсталирай Test 79");
			primary.setEnabled(true);
			primary.setOnClickListener(v -> continueMigration());
		}
	}

	private void continueMigration() {
		if (!canInstallPackages()) {
			Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					Uri.parse("package:" + getPackageName()));
			startActivityForResult(permission, REQUEST_PERMISSION);
			return;
		}
		if (isPackageInstalled(ROADCREW_PACKAGE)) {
			showUninstallWarning();
		} else if (hasRetainedRoadCrewData()) {
			renderState();
		} else {
			installRelease();
		}
	}

	private void showUninstallWarning() {
		new AlertDialog.Builder(this)
				.setTitle("Важно при деинсталирането")
				.setMessage("На следващия системен екран махнете отметката „Запази данните“. Иначе Android ще запази стария подпис и Test 79 няма да може да се инсталира.")
				.setNegativeButton("Отказ", null)
				.setPositiveButton("Разбрах", (dialog, which) -> uninstallOldRoadCrew())
				.show();
	}

	private void uninstallOldRoadCrew() {
		Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
				Uri.parse("package:" + ROADCREW_PACKAGE));
		uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
		startActivityForResult(uninstall, REQUEST_UNINSTALL);
	}

	private void installRelease() {
		Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", releaseApk);
		Intent install = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivityForResult(install, REQUEST_INSTALL);
	}

	private void uninstallSelf() {
		startActivity(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
				Uri.parse("package:" + getPackageName())));
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_PERMISSION && canInstallPackages()) {
			continueMigration();
		} else if (requestCode == REQUEST_UNINSTALL) {
			if (resultCode == RESULT_OK && !isPackageInstalled(ROADCREW_PACKAGE)
					&& !hasRetainedRoadCrewData()) {
				installRelease();
			} else {
				renderState();
				if (isPackageInstalled(ROADCREW_PACKAGE)) {
					status.setText("Старата версия не беше премахната. Потвърдете системния екран за деинсталиране, преди да продължите.");
				}
			}
		} else {
			renderState();
		}
	}

	private boolean canInstallPackages() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
				|| getPackageManager().canRequestPackageInstalls();
	}

	private boolean isSecureRoadCrewInstalled() {
		try {
			PackageInfo info = getPackageManager().getPackageInfo(ROADCREW_PACKAGE,
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
							? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
			Signature signature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
					? info.signingInfo.getApkContentsSigners()[0] : info.signatures[0];
			return RELEASE_SHA256.equals(sha256(signature.toByteArray()));
		} catch (Exception e) {
			return false;
		}
	}

	private boolean isPackageInstalled(String packageName) {
		try {
			getPackageManager().getPackageInfo(packageName, 0);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	private boolean hasRetainedRoadCrewData() {
		if (isPackageInstalled(ROADCREW_PACKAGE)) {
			return false;
		}
		try {
			getPackageManager().getPackageInfo(ROADCREW_PACKAGE, PackageManager.MATCH_UNINSTALLED_PACKAGES);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			return false;
		}
	}

	private boolean isExpectedReleaseApk(File apk) {
		if (!apk.isFile()) {
			return false;
		}
		try {
			PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
							? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
			if (info == null || !ROADCREW_PACKAGE.equals(info.packageName)
					|| getVersionCode(info) != RELEASE_VERSION_CODE) {
				return false;
			}
			Signature signature = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
					? info.signingInfo.getApkContentsSigners()[0] : info.signatures[0];
			return RELEASE_SHA256.equals(sha256(signature.toByteArray()));
		} catch (Exception e) {
			return false;
		}
	}

	private long getVersionCode(PackageInfo info) {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
	}

	private static String sha256(byte[] value) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
		StringBuilder result = new StringBuilder();
		for (byte item : digest) {
			result.append(String.format(Locale.US, "%02X", item));
		}
		return result.toString();
	}

	private TextView text(String value, int size, int color, int style) {
		TextView view = new TextView(this);
		view.setText(value);
		view.setTextSize(size);
		view.setTextColor(color);
		view.setTypeface(Typeface.DEFAULT, style);
		view.setGravity(Gravity.CENTER);
		return view;
	}

	private Button button(String value, boolean primaryButton) {
		Button button = new Button(this);
		button.setText(value);
		button.setTextSize(17);
		button.setTextColor(Color.WHITE);
		button.setAllCaps(false);
		button.setBackgroundColor(primaryButton ? Color.rgb(20, 116, 82) : Color.rgb(43, 51, 48));
		return button;
	}

	private LinearLayout.LayoutParams matchWrap() {
		return new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}
}
