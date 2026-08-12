package net.osmand.plus.roadcrew;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.download.DownloadActivity;
import net.osmand.plus.utils.AndroidUtils;

public final class RoadCrewStartupSetup {

	private static final String PREFS_NAME = "roadcrew_startup_setup";
	private static final String KEY_COMPLETED = "completed";
	private static final String KEY_LAST_PROMPT_MILLIS = "last_prompt_millis";
	private static final long PROMPT_THROTTLE_MILLIS = 24 * 60 * 60 * 1000L;

	private static boolean shownThisSession;
	private static boolean dialogShowing;

	private RoadCrewStartupSetup() {
	}

	public static boolean showIfNeeded(@NonNull MapActivity activity) {
		if (shownThisSession || dialogShowing || activity.isFinishing()
				|| (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())
				|| !RoadCrewReportsLayer.isEnabled(activity.getApp())) {
			return false;
		}
		boolean needsLocationPermission = !OsmAndLocationProvider.isLocationPermissionAvailable(activity);
		boolean needsDeviceLocation = !isDeviceLocationEnabled(activity);
		boolean needsNotifications = !areNotificationsReady(activity);
		boolean needsDriverProfile = !isDriverProfileReady(activity);
		boolean needsOfflineMap = !activity.getApp().getResourceManager().isAnyMapInstalled();
		SharedPreferences preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		if (!needsLocationPermission && !needsDeviceLocation && !needsNotifications
				&& !needsDriverProfile && !needsOfflineMap) {
			preferences.edit().putBoolean(KEY_COMPLETED, true).apply();
			return false;
		}
		long now = System.currentTimeMillis();
		if ((preferences.getBoolean(KEY_COMPLETED, false) && !needsDriverProfile)
				|| now - preferences.getLong(KEY_LAST_PROMPT_MILLIS, 0) < PROMPT_THROTTLE_MILLIS) {
			return false;
		}
		shownThisSession = true;
		dialogShowing = true;
		preferences.edit().putLong(KEY_LAST_PROMPT_MILLIS, now).apply();

		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_setup_title));
		RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_setup_intro));

		if (needsLocationPermission) {
			addSetupItem(activity, content,
					activity.getString(R.string.roadcrew_setup_location_permission),
					activity.getString(R.string.roadcrew_setup_location_permission_body),
					activity.getString(R.string.roadcrew_setup_enable_location),
					v -> ActivityCompat.requestPermissions(activity, new String[] {
									Manifest.permission.ACCESS_FINE_LOCATION,
									Manifest.permission.ACCESS_COARSE_LOCATION},
							OsmAndLocationProvider.REQUEST_LOCATION_PERMISSION));
		}
		if (needsDeviceLocation) {
			addSetupItem(activity, content,
					activity.getString(R.string.roadcrew_setup_device_location),
					activity.getString(R.string.roadcrew_setup_device_location_body),
					activity.getString(R.string.roadcrew_setup_open_settings),
					v -> AndroidUtils.startActivityIfSafe(activity,
							new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
		}
		if (needsNotifications) {
			addSetupItem(activity, content,
					activity.getString(R.string.roadcrew_setup_notifications),
					activity.getString(R.string.roadcrew_setup_notifications_body),
					activity.getString(R.string.roadcrew_setup_enable_notifications),
					v -> openNotificationPermission(activity));
		}
		if (needsOfflineMap) {
			addSetupItem(activity, content,
					activity.getString(R.string.roadcrew_setup_offline_map),
					activity.getString(R.string.roadcrew_setup_offline_map_body),
					activity.getString(R.string.roadcrew_setup_download_map),
					v -> openMapDownload(activity));
		}
		if (needsDriverProfile) {
			addSetupItem(activity, content,
					activity.getString(R.string.roadcrew_profile_title),
					activity.getString(R.string.roadcrew_setup_driver_profile_body),
					activity.getString(R.string.roadcrew_setup_complete_profile),
					v -> RoadCrewDriverProfileDialog.show(activity, activity.getApp()));
		}

		RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_setup_push_note));

		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_later), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_done), true, v -> dialog.dismiss());
		dialog.setOnDismissListener(d -> dialogShowing = false);
		dialog.show();
		return true;
	}

	private static void addSetupItem(@NonNull MapActivity activity, @NonNull LinearLayout content,
			@NonNull String title, @NonNull String body, @NonNull String buttonTitle,
			@NonNull android.view.View.OnClickListener listener) {
		RoadCrewUi.addSectionTitle(activity, content, title);
		RoadCrewUi.addBody(activity, content, body);
		RoadCrewUi.addFullWidthButton(activity, content, buttonTitle, true, listener);
	}

	private static boolean areNotificationsReady(@NonNull Context context) {
		return AndroidUtils.hasPostNotificationPermission(context)
				&& NotificationManagerCompat.from(context).areNotificationsEnabled();
	}

	private static boolean isDeviceLocationEnabled(@NonNull Context context) {
		LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		if (locationManager == null) {
			return false;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			return locationManager.isLocationEnabled();
		}
		try {
			return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
					|| locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean isDriverProfileReady(@NonNull MapActivity activity) {
		RoadCrewDriverProfile profile = RoadCrewDriverProfile.load(activity.getApp());
		return !profile.getDisplayName().isEmpty()
				&& profile.hasPlateIdentity()
				&& profile.isPlateAlertsEnabled();
	}

	private static void openNotificationPermission(@NonNull MapActivity activity) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& !AndroidUtils.hasPostNotificationPermission(activity)) {
			ActivityCompat.requestPermissions(activity,
					new String[] {Manifest.permission.POST_NOTIFICATIONS},
					AndroidUtils.POST_NOTIFICATIONS_REQUEST_CODE);
			return;
		}
		Intent intent;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
					.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
		} else {
			intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
					.setData(Uri.fromParts("package", activity.getPackageName(), null));
		}
		AndroidUtils.startActivityIfSafe(activity, intent);
	}

	private static void openMapDownload(@NonNull MapActivity activity) {
		Intent intent = new Intent(activity, activity.getApp().getAppCustomization().getDownloadActivity());
		intent.putExtra(DownloadActivity.TAB_TO_OPEN, DownloadActivity.DOWNLOAD_TAB);
		AndroidUtils.startActivityIfSafe(activity, intent);
	}
}
