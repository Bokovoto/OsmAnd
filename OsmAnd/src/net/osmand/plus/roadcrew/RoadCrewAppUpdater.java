package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.R;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.RoadCrewUpdateTransfer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoadCrewAppUpdater {

	private static final String TAG = "RoadCrewAppUpdater";
	private static final String PREFS_NAME = "roadcrew_app_updater";
	private static final String KEY_LAST_CHECK_MILLIS = "last_check_millis";
	private static final String KEY_DISMISSED_TAG = "dismissed_tag";
	private static final String KEY_PENDING_UPDATE = "pending_update";
	private static final String CURRENT_RELEASE_TAG = "roadcrew-v0.1.0-test.74";
	private static final String LATEST_RELEASE_API =
			"https://api.github.com/repos/Bokovoto/OsmAnd/releases/latest";
	private static final String APK_ASSET_NAME = "RoadCrew.apk";
	private static final long CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L;
	private static final int CONNECT_TIMEOUT_MILLIS = 10 * 1000;
	private static final int READ_TIMEOUT_MILLIS = 60 * 1000;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
	private static final ExecutorService CANCELLER = Executors.newSingleThreadExecutor();
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static boolean checkRunning;
	private static boolean dialogShowing;
	private static WeakReference<MapActivity> foreground = new WeakReference<>(null);
	private static DownloadTask activeTask;
	private static RoadCrewUpdateProgressDialog progressDialog;
	private static AlertDialog offerDialog;

	private RoadCrewAppUpdater() {
	}

	public static boolean isUpdateInProgress() {
		return activeTask != null;
	}

	public static void checkForUpdatesIfNeeded(@NonNull MapActivity activity) {
		if (activeTask != null) {
			return;
		}
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
		if (activeTask != null) {
			showDownloadProgress();
			return;
		}
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
			if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
				throw new IOException("Update check HTTP status: " + connection.getResponseCode());
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
								releasePageUrl, asset.optLong("size", -1), asset.optString("digest", ""));
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
		if (activeTask != null || foreground.get() != activity || dialogShowing || activity.isFinishing()
				|| (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
			return;
		}
		dialogShowing = true;
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_update_title));
		RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_update_available, update.title));
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		offerDialog = dialog;
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_later), false, v -> {
			preferences.edit().putString(KEY_DISMISSED_TAG, update.tag).apply();
			dialog.dismiss();
		});
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_update), true, v -> {
			dialog.dismiss();
			downloadAndInstall(activity, update);
		});
		dialog.setOnDismissListener(d -> {
			dialogShowing = false;
			offerDialog = null;
		});
		dialog.show();
	}

	private static void downloadAndInstall(@NonNull MapActivity activity, @NonNull UpdateInfo update) {
		if (activeTask != null) {
			showDownloadProgress();
			return;
		}
		activeTask = new DownloadTask(update);
		activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
				.putString(KEY_PENDING_UPDATE, update.toJson().toString()).apply();
		if (canInstall(activity)) {
			startDownload(activity.getApplicationContext(), activeTask);
		}
		showDownloadProgress();
	}

	/** UI-thread attachment: keep the transfer, not a destroyed Activity, across rotation. */
	public static void onResume(@NonNull MapActivity activity) {
		foreground = new WeakReference<>(activity);
		if (activeTask == null) {
			String pending = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
					.getString(KEY_PENDING_UPDATE, "");
			if (!pending.isEmpty()) {
				try {
					activeTask = new DownloadTask(UpdateInfo.fromJson(new JSONObject(pending)));
					activeTask.phase = Phase.FAILED;
					activeTask.error = R.string.roadcrew_update_interrupted;
				} catch (Exception e) {
					clearPending(activity);
				}
			}
		}
		if (activeTask != null && activeTask.phase == Phase.PERMISSION && canInstall(activity)) {
			startDownload(activity.getApplicationContext(), activeTask);
		}
		if (activeTask != null) {
			activeTask.installerOpening = false;
		}
		showDownloadProgress();
	}

	public static void onPause(@NonNull MapActivity activity) {
		if (foreground.get() == activity) {
			foreground.clear();
			if (progressDialog != null) {
				progressDialog.dismiss();
				progressDialog = null;
			}
			if (offerDialog != null) {
				offerDialog.dismiss();
			}
		}
	}

	private static boolean canInstall(Context context) {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
				|| context.getPackageManager().canRequestPackageInstalls();
	}

	private static void startDownload(Context context, DownloadTask task) {
		if (task.started) {
			return;
		}
		task.started = true;
		task.phase = Phase.CONNECTING;
		task.transfer = new RoadCrewUpdateTransfer("RoadCrew/" + CURRENT_RELEASE_TAG,
				new RoadCrewUpdateTransfer.Listener() {
					@Override
					public void onProgress(long received, long total) {
						MAIN.post(() -> {
							if (activeTask == task && !task.cancelled) {
								task.received = received;
								task.total = total;
								task.phase = received == 0 ? Phase.CONNECTING : Phase.DOWNLOADING;
								showDownloadProgress();
							}
						});
					}

					@Override
					public void onVerifying() {
						MAIN.post(() -> {
							if (activeTask == task && !task.cancelled) {
								task.phase = Phase.VERIFYING;
								showDownloadProgress();
							}
						});
					}
				});
		EXECUTOR.execute(() -> {
			try {
				File cache = context.getExternalCacheDir();
				File directory = new File(cache != null ? cache : context.getCacheDir(), "roadcrew-updates");
				if (!directory.isDirectory() && !directory.mkdirs()) {
					throw new IOException("Could not create update cache directory");
				}
				File apk = task.transfer.download(task.update.assetApiUrl, task.update.browserDownloadUrl,
						new File(directory, APK_ASSET_NAME), task.update.expectedSize, task.update.sha256);
				MAIN.post(() -> {
					if (activeTask != task) {
						return;
					}
					if (task.cancelled) {
						finishCancelled(context, task);
						return;
					}
					task.apk = apk;
					task.received = apk.length();
					task.total = apk.length();
					task.phase = Phase.READY;
					clearPending(context);
					showDownloadProgress();
				});
			} catch (Exception e) {
				if (!task.cancelled) {
					Log.w(TAG, "RoadCrew update download failed", e);
				}
				MAIN.post(() -> {
					if (activeTask != task) {
						return;
					}
					if (task.cancelled) {
						finishCancelled(context, task);
					} else {
						task.phase = Phase.FAILED;
						task.error = R.string.roadcrew_update_transfer_failed;
						showDownloadProgress();
					}
				});
			}
		});
	}

	private static void showDownloadProgress() {
		MapActivity activity = foreground.get();
		if (activeTask == null || activity == null || activity.isFinishing() || activity.isDestroyed()) {
			return;
		}
		if (progressDialog == null) {
			progressDialog = new RoadCrewUpdateProgressDialog(activity,
					RoadCrewAppUpdater::primaryAction, RoadCrewAppUpdater::cancelDownload);
			progressDialog.show();
		}
		progressDialog.render(activeTask.phase, activeTask.received, activeTask.total,
				activeTask.update.title, activeTask.error);
	}

	private static void primaryAction() {
		MapActivity activity = foreground.get();
		DownloadTask task = activeTask;
		if (task == null || activity == null) {
			return;
		}
		if (task.phase == Phase.PERMISSION || (task.phase == Phase.READY && !canInstall(activity))) {
			Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
					.setData(Uri.parse("package:" + activity.getPackageName()));
			AndroidUtils.startActivityIfSafe(activity, intent);
		} else if (task.phase == Phase.FAILED) {
			activeTask = null;
			downloadAndInstall(activity, task.update);
		} else if (task.phase == Phase.READY && !task.installerOpening) {
			try {
				if (task.apk == null || !task.apk.isFile() || !openInstaller(activity, task.apk)) {
					throw new IOException("Could not open APK installer");
				}
				task.installerOpening = true;
			} catch (Exception e) {
				Log.w(TAG, "RoadCrew installer failed", e);
				task.phase = Phase.FAILED;
				task.error = R.string.roadcrew_update_installer_failed;
				showDownloadProgress();
			}
		}
	}

	private static void cancelDownload() {
		DownloadTask task = activeTask;
		MapActivity activity = foreground.get();
		if (task == null || activity == null || task.cancelled) {
			return;
		}
		task.cancelled = true;
		if (task.phase == Phase.CONNECTING || task.phase == Phase.DOWNLOADING || task.phase == Phase.VERIFYING) {
			task.phase = Phase.CANCELLING;
			task.transfer.requestCancel();
			CANCELLER.execute(task.transfer::disconnect);
			showDownloadProgress();
		} else {
			finishCancelled(activity, task);
		}
	}

	private static void finishCancelled(Context context, DownloadTask task) {
		if (activeTask == task) {
			activeTask = null;
			clearPending(context);
			if (progressDialog != null) {
				progressDialog.dismiss();
				progressDialog = null;
			}
		}
	}

	private static void clearPending(Context context) {
		context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_PENDING_UPDATE).apply();
	}

	private static boolean openInstaller(@NonNull MapActivity activity, @NonNull File apk) {
		Uri uri = AndroidUtils.getUriForFile(activity, apk);
		Intent intent = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, "application/vnd.android.package-archive")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return AndroidUtils.startActivityIfSafe(activity, intent);
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

	public enum Phase { PERMISSION, CONNECTING, DOWNLOADING, VERIFYING, READY, FAILED, CANCELLING }

	private static final class DownloadTask {
		final UpdateInfo update;
		Phase phase = Phase.PERMISSION;
		boolean started;
		boolean installerOpening;
		volatile boolean cancelled;
		long received;
		long total;
		int error = R.string.roadcrew_update_transfer_failed;
		RoadCrewUpdateTransfer transfer;
		File apk;

		DownloadTask(UpdateInfo update) {
			this.update = update;
			this.total = update.expectedSize;
		}
	}

	private static final class UpdateInfo {
		final String tag;
		final String title;
		final String assetApiUrl;
		final String browserDownloadUrl;
		final String releasePageUrl;
		final long expectedSize;
		final String sha256;

		UpdateInfo(@NonNull String tag, @NonNull String title, @NonNull String assetApiUrl,
				@NonNull String browserDownloadUrl, @NonNull String releasePageUrl, long expectedSize,
				@NonNull String digest) {
			this.tag = tag;
			this.title = title;
			this.assetApiUrl = assetApiUrl;
			this.browserDownloadUrl = browserDownloadUrl;
			this.releasePageUrl = releasePageUrl;
			this.expectedSize = expectedSize;
			this.sha256 = digest.startsWith("sha256:") ? digest.substring(7) : "";
		}

		JSONObject toJson() {
			JSONObject object = new JSONObject();
			try {
				object.put("tag", tag).put("title", title).put("api", assetApiUrl)
						.put("browser", browserDownloadUrl).put("page", releasePageUrl)
						.put("size", expectedSize).put("digest", "sha256:" + sha256);
			} catch (org.json.JSONException e) {
				throw new IllegalStateException(e);
			}
			return object;
		}

		static UpdateInfo fromJson(JSONObject object) throws org.json.JSONException {
			return new UpdateInfo(object.getString("tag"), object.getString("title"),
					object.getString("api"), object.getString("browser"), object.getString("page"),
					object.getLong("size"), object.optString("digest", ""));
		}
	}
}
