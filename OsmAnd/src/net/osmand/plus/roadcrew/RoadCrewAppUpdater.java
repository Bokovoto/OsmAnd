package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.R;
import net.osmand.plus.utils.AndroidUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoadCrewAppUpdater {

	private static final String TAG = "RoadCrewAppUpdater";
	private static final String PREFS_NAME = "roadcrew_app_updater";
	private static final String KEY_LAST_CHECK_MILLIS = "last_check_millis";
	private static final String KEY_DISMISSED_TAG = "dismissed_tag";
	private static final String CURRENT_RELEASE_TAG = "roadcrew-v0.1.0-test.45";
	private static final String LATEST_RELEASE_API =
			"https://api.github.com/repos/Bokovoto/OsmAnd/releases/latest";
	private static final String APK_ASSET_NAME = "RoadCrew.apk";
	private static final long CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L;
	private static final int CONNECT_TIMEOUT_MILLIS = 10 * 1000;
	private static final int READ_TIMEOUT_MILLIS = 60 * 1000;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
	private static boolean checkRunning;
	private static boolean dialogShowing;

	private RoadCrewAppUpdater() {
	}

	public static void checkForUpdatesIfNeeded(@NonNull MapActivity activity) {
		if (!RoadCrewReportsLayer.isEnabled(activity.getApp()) || dialogShowing) {
			return;
		}
		SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		long now = System.currentTimeMillis();
		if (now - preferences.getLong(KEY_LAST_CHECK_MILLIS, 0) < CHECK_INTERVAL_MILLIS) {
			return;
		}
		preferences.edit().putLong(KEY_LAST_CHECK_MILLIS, now).apply();
		checkForUpdates(activity, preferences, false);
	}

	public static void checkForUpdatesNow(@NonNull MapActivity activity) {
		if (!RoadCrewReportsLayer.isEnabled(activity.getApp()) || dialogShowing) {
			return;
		}
		activity.getApp().showToastMessage(R.string.roadcrew_update_checking);
		SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		checkForUpdates(activity, preferences, true);
	}

	private static void checkForUpdates(@NonNull MapActivity activity, @NonNull SharedPreferences preferences,
			boolean forced) {
		synchronized (RoadCrewAppUpdater.class) {
			if (checkRunning) {
				return;
			}
			checkRunning = true;
		}
		EXECUTOR.execute(() -> {
			try {
				UpdateInfo update = fetchLatestRelease();
				if (update != null
						&& !CURRENT_RELEASE_TAG.equals(update.tag)
						&& (forced || !update.tag.equals(preferences.getString(KEY_DISMISSED_TAG, "")))) {
					activity.getApp().runInUIThread(() -> showUpdateDialog(activity, preferences, update));
				} else if (forced) {
					activity.getApp().runInUIThread(() ->
							activity.getApp().showToastMessage(R.string.roadcrew_update_up_to_date));
				}
			} catch (Exception e) {
				Log.w(TAG, "RoadCrew update check failed", e);
				if (forced) {
					activity.getApp().runInUIThread(() ->
							activity.getApp().showToastMessage(R.string.roadcrew_update_check_failed));
				}
			} finally {
				synchronized (RoadCrewAppUpdater.class) {
					checkRunning = false;
				}
			}
		});
	}

	@Nullable
	private static UpdateInfo fetchLatestRelease() throws Exception {
		HttpURLConnection connection = openConnection(LATEST_RELEASE_API);
		try {
			if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
				return null;
			}
			String body = readFully(connection.getInputStream());
			JSONObject object = new JSONObject(body);
			String tag = object.optString("tag_name");
			String releasePageUrl = object.optString("html_url");
			JSONArray assets = object.optJSONArray("assets");
			if (tag.isEmpty() || assets == null) {
				return null;
			}
			for (int i = 0; i < assets.length(); i++) {
				JSONObject asset = assets.getJSONObject(i);
				if (APK_ASSET_NAME.equals(asset.optString("name"))) {
					String apiUrl = asset.optString("url");
					String browserUrl = asset.optString("browser_download_url");
					if (!apiUrl.isEmpty() || !browserUrl.isEmpty()) {
						return new UpdateInfo(tag, object.optString("name", tag), apiUrl, browserUrl,
								releasePageUrl, asset.optLong("size", -1));
					}
				}
			}
			return null;
		} finally {
			connection.disconnect();
		}
	}

	private static void showUpdateDialog(@NonNull MapActivity activity, @NonNull SharedPreferences preferences,
			@NonNull UpdateInfo update) {
		if (dialogShowing || activity.isFinishing()
				|| (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
			return;
		}
		dialogShowing = true;
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_update_title));
		RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_update_available, update.title));
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_later), false, v -> {
			preferences.edit().putString(KEY_DISMISSED_TAG, update.tag).apply();
			dialog.dismiss();
		});
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_update), true, v -> {
			dialog.dismiss();
			downloadAndInstall(activity, update);
		});
		dialog.setOnDismissListener(d -> dialogShowing = false);
		dialog.show();
	}

	private static void downloadAndInstall(@NonNull MapActivity activity, @NonNull UpdateInfo update) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				&& !activity.getPackageManager().canRequestPackageInstalls()) {
			Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
					.setData(Uri.parse("package:" + activity.getPackageName()));
			AndroidUtils.startActivityIfSafe(activity, intent);
			activity.getApp().showToastMessage(R.string.roadcrew_update_allow_installs);
			return;
		}
		activity.getApp().showToastMessage(R.string.roadcrew_update_downloading);
		EXECUTOR.execute(() -> {
			try {
				File apk = downloadApk(activity, update);
				activity.getApp().runInUIThread(() -> openInstaller(activity, apk));
			} catch (Exception e) {
				Log.w(TAG, "RoadCrew update download failed", e);
				activity.getApp().runInUIThread(() -> {
					activity.getApp().showToastMessage(R.string.roadcrew_update_download_failed);
					openReleasePage(activity, update);
				});
			}
		});
	}

	@NonNull
	private static File downloadApk(@NonNull Context context, @NonNull UpdateInfo update) throws Exception {
		File cacheDir = context.getExternalCacheDir();
		if (cacheDir == null) {
			cacheDir = context.getCacheDir();
		}
		File directory = new File(cacheDir, "roadcrew-updates");
		if (!directory.exists() && !directory.mkdirs()) {
			throw new IllegalStateException("Could not create update cache directory");
		}
		File apk = new File(directory, APK_ASSET_NAME);
		Exception browserDownloadError = null;
		if (!update.assetApiUrl.isEmpty()) {
			try {
				downloadApkFromUrl(update.assetApiUrl, apk, update.expectedSize, true);
				return apk;
			} catch (Exception e) {
				browserDownloadError = e;
				Log.w(TAG, "RoadCrew update API asset download failed", e);
			}
		}
		if (!update.browserDownloadUrl.isEmpty()) {
			try {
				downloadApkFromUrl(update.browserDownloadUrl, apk, update.expectedSize, false);
				return apk;
			} catch (Exception e) {
				browserDownloadError = e;
				Log.w(TAG, "RoadCrew update browser asset download failed", e);
			}
		}
		throw browserDownloadError != null ? browserDownloadError : new IllegalStateException("No APK download URL");
	}

	private static void downloadApkFromUrl(@NonNull String url, @NonNull File apk, long expectedSize,
			boolean useApiHeaders) throws Exception {
		HttpURLConnection connection = openConnection(url);
		if (useApiHeaders) {
			connection.setRequestProperty("Accept", "application/octet-stream");
		}
		try {
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				throw new IllegalStateException("Unexpected download response: " + responseCode);
			}
			String contentType = connection.getContentType();
			if (contentType != null && contentType.contains("text/html")) {
				throw new IllegalStateException("Unexpected HTML response while downloading APK");
			}
			try (InputStream inputStream = connection.getInputStream();
				 FileOutputStream outputStream = new FileOutputStream(apk)) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = inputStream.read(buffer)) != -1) {
					outputStream.write(buffer, 0, read);
				}
			}
			if (apk.length() < 1024 * 1024) {
				throw new IllegalStateException("Downloaded APK is too small: " + apk.length());
			}
			if (expectedSize > 0 && apk.length() != expectedSize) {
				throw new IllegalStateException("Downloaded APK size mismatch: " + apk.length() + " != " + expectedSize);
			}
		} finally {
			connection.disconnect();
		}
	}

	private static void openInstaller(@NonNull MapActivity activity, @NonNull File apk) {
		Uri uri = AndroidUtils.getUriForFile(activity, apk);
		Intent intent = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		AndroidUtils.startActivityIfSafe(activity, intent);
	}

	private static void openReleasePage(@NonNull MapActivity activity, @NonNull UpdateInfo update) {
		if (update.releasePageUrl.isEmpty()) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(update.releasePageUrl));
		AndroidUtils.startActivityIfSafe(activity, intent);
	}

	@NonNull
	private static HttpURLConnection openConnection(@NonNull String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("User-Agent", "RoadCrew/" + CURRENT_RELEASE_TAG);
		connection.setInstanceFollowRedirects(true);
		return connection;
	}

	@NonNull
	private static String readFully(@NonNull InputStream inputStream) throws Exception {
		StringBuilder builder = new StringBuilder();
		byte[] buffer = new byte[16 * 1024];
		int read;
		while ((read = inputStream.read(buffer)) != -1) {
			builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
		}
		return builder.toString();
	}

	private static final class UpdateInfo {
		final String tag;
		final String title;
		final String assetApiUrl;
		final String browserDownloadUrl;
		final String releasePageUrl;
		final long expectedSize;

		UpdateInfo(@NonNull String tag, @NonNull String title, @NonNull String assetApiUrl,
				@NonNull String browserDownloadUrl, @NonNull String releasePageUrl, long expectedSize) {
			this.tag = tag;
			this.title = title;
			this.assetApiUrl = assetApiUrl;
			this.browserDownloadUrl = browserDownloadUrl;
			this.releasePageUrl = releasePageUrl;
			this.expectedSize = expectedSize;
		}
	}
}
