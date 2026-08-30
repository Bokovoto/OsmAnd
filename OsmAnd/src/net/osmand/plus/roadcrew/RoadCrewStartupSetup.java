package net.osmand.plus.roadcrew;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.download.DownloadActivity;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.utils.AndroidUtils;

public final class RoadCrewStartupSetup {

	private static final String PREFS_NAME = "roadcrew_startup_setup";
	private static final String KEY_COMPLETED = "completed";
	private static final String KEY_CURRENT_STEP = "current_step";
	private static final int STEP_COUNT = 6;

	private static boolean dialogShowing;
	private static boolean manualReview;
	private static AlertDialog currentDialog;

	private RoadCrewStartupSetup() {
	}

	public static boolean showIfNeeded(@NonNull MapActivity activity) {
		if (!canShow(activity)) {
			return false;
		}
		if (dialogShowing) {
			return true;
		}
		SharedPreferences preferences = getPreferences(activity);
		if (areAllStepsReady(activity)) {
			preferences.edit()
					.putBoolean(KEY_COMPLETED, true)
					.putInt(KEY_CURRENT_STEP, 0)
					.apply();
			return false;
		}
		preferences.edit().putBoolean(KEY_COMPLETED, false).apply();
		int savedStep = clampStep(preferences.getInt(KEY_CURRENT_STEP, 0));
		int firstIncompleteStep = findFirstIncompleteStep(activity);
		showStep(activity, Math.min(savedStep, firstIncompleteStep), false);
		return true;
	}

	static void showManually(@NonNull MapActivity activity) {
		if (!canShow(activity) || dialogShowing) {
			return;
		}
		showStep(activity, 0, true);
	}

	private static void showStep(@NonNull MapActivity activity, int step, boolean reviewMode) {
		dialogShowing = true;
		manualReview = reviewMode;
		getPreferences(activity).edit().putInt(KEY_CURRENT_STEP, step).apply();

		LinearLayout content = RoadCrewUi.createPanel(activity,
				activity.getString(R.string.roadcrew_setup_title));
		TextView progress = RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_setup_step, step + 1, STEP_COUNT));
		progress.setTextColor(RoadCrewUi.SECONDARY_TEXT);
		RoadCrewUi.addSectionTitle(activity, content, getStepTitle(activity, step));
		RoadCrewUi.addBody(activity, content, getStepBody(activity, step));

		boolean ready = isStepReady(activity, step);
		TextView status = RoadCrewUi.addBody(activity, content, activity.getString(
				ready ? R.string.roadcrew_setup_status_ready : R.string.roadcrew_setup_status_incomplete));
		status.setTextColor(ready ? RoadCrewUi.PRIMARY : RoadCrewUi.DANGER);
		RoadCrewUi.addFullWidthButton(activity, content, getStepActionTitle(activity, step), true,
				v -> performStepAction(activity, step));
		if (step == STEP_COUNT - 1) {
			addOptionalLiveTruckMapConsent(activity, content);
		}

		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		currentDialog = dialog;
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		if (step > 0) {
			RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_back), false,
					v -> moveToStep(activity, dialog, step - 1));
		}
		Button next = RoadCrewUi.addButton(activity, buttons, activity.getString(
				step == STEP_COUNT - 1 ? R.string.roadcrew_button_done : R.string.roadcrew_button_next),
				true, v -> {
					if (!isStepReady(activity, step)) {
						activity.getApp().showToastMessage(R.string.roadcrew_setup_complete_current_step);
						return;
					}
					if (step < STEP_COUNT - 1) {
						moveToStep(activity, dialog, step + 1);
					} else if (areAllStepsReady(activity)) {
						getPreferences(activity).edit()
								.putBoolean(KEY_COMPLETED, true)
								.putInt(KEY_CURRENT_STEP, 0)
								.apply();
						dialog.dismiss();
						RoadCrewAppUpdater.checkForUpdatesIfNeeded(activity);
					}
				});
		next.setEnabled(ready);
		next.setAlpha(ready ? 1.0f : 0.45f);
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		dialog.setOnDismissListener(d -> {
			dialogShowing = false;
			if (currentDialog == dialog) {
				currentDialog = null;
			}
		});
		dialog.show();
		if (step == 3 && !ready) {
			monitorOfflineMapStep(activity, dialog, status, next);
		}
	}

	private static void addOptionalLiveTruckMapConsent(@NonNull MapActivity activity,
			@NonNull LinearLayout content) {
		RoadCrewUi.addSectionTitle(activity, content,
				activity.getString(R.string.roadcrew_live_truck_map_title));
		RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_live_truck_map_wizard_body));
		RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_trip_review_privacy));
		boolean observationEnabled = RoadCrewMapObservationConsent.isEnabled(activity);
		if (!manualReview && !RoadCrewMapObservationConsent.hasStoredChoice(activity)) {
			RoadCrewReportsLayer.setMapObservationEnabled(activity.getApp(), true);
			observationEnabled = true;
		}
		CheckBox consent = new CheckBox(activity);
		consent.setText(R.string.roadcrew_live_truck_map_consent);
		consent.setTextColor(RoadCrewUi.TEXT);
		consent.setChecked(observationEnabled);
		consent.setOnCheckedChangeListener((buttonView, isChecked) ->
				RoadCrewReportsLayer.setMapObservationEnabled(activity.getApp(), isChecked));
		content.addView(consent, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		TextView optional = RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_live_truck_map_optional));
		optional.setTextColor(RoadCrewUi.SECONDARY_TEXT);
	}

	private static void monitorOfflineMapStep(@NonNull MapActivity activity, @NonNull AlertDialog dialog,
			@NonNull TextView status, @NonNull Button next) {
		activity.getApp().runInUIThread(new Runnable() {
			@Override
			public void run() {
				if (!canShow(activity) || currentDialog != dialog || !dialog.isShowing()) {
					return;
				}
				if (RoadCrewSetupStatus.isOfflineMapReady(activity.getApp())) {
					status.setText(R.string.roadcrew_setup_status_ready);
					status.setTextColor(RoadCrewUi.PRIMARY);
					next.setEnabled(true);
					next.setAlpha(1.0f);
					return;
				}
				activity.getApp().runInUIThread(this, 1000);
			}
		}, 1000);
	}

	private static void moveToStep(@NonNull MapActivity activity, @NonNull AlertDialog dialog, int step) {
		boolean reviewMode = manualReview;
		dialog.setOnDismissListener(null);
		dialog.dismiss();
		dialogShowing = false;
		currentDialog = null;
		showStep(activity, clampStep(step), reviewMode);
	}

	private static void performStepAction(@NonNull MapActivity activity, int step) {
		if (currentDialog != null) {
			currentDialog.setOnDismissListener(null);
			currentDialog.dismiss();
			currentDialog = null;
		}
		dialogShowing = false;
		switch (step) {
			case 0:
				ActivityCompat.requestPermissions(activity, new String[] {
						Manifest.permission.ACCESS_FINE_LOCATION,
						Manifest.permission.ACCESS_COARSE_LOCATION},
						OsmAndLocationProvider.REQUEST_LOCATION_PERMISSION);
				break;
			case 1:
				AndroidUtils.startActivityIfSafe(activity,
						new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
				break;
			case 2:
				openNotificationPermission(activity);
				break;
			case 3:
				openMapDownload(activity);
				break;
			case 4:
				RoadCrewDriverProfileDialog.showForSetup(activity, activity.getApp(),
						() -> activity.getApp().runInUIThread(
								() -> continueAfterStepAction(activity, step), 250));
				break;
			case 5:
				RoadCrewVehicleParametersDialog.show(activity, activity.getApp(),
						() -> activity.getApp().runInUIThread(
								() -> continueAfterStepAction(activity, step), 250));
				break;
			default:
				break;
		}
	}

	private static void continueAfterStepAction(@NonNull MapActivity activity, int step) {
		if (!canShow(activity) || dialogShowing) {
			return;
		}
		if (!isStepReady(activity, step)) {
			showStep(activity, step, manualReview);
			return;
		}
		if (step < STEP_COUNT - 1) {
			showStep(activity, step + 1, manualReview);
		} else if (areAllStepsReady(activity)) {
			getPreferences(activity).edit()
					.putBoolean(KEY_COMPLETED, true)
					.putInt(KEY_CURRENT_STEP, 0)
					.apply();
			RoadCrewAppUpdater.checkForUpdatesIfNeeded(activity);
		}
	}

	static void runAfterVehicleSettingsClosed(@NonNull MapActivity activity,
			@NonNull Runnable onClosed) {
		activity.getApp().runInUIThread(new Runnable() {
			private boolean settingsSeen;
			private int attempts;

			@Override
			public void run() {
				if (!canShow(activity)) {
					return;
				}
				Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(
						SettingsScreenType.VEHICLE_PARAMETERS.fragmentName);
				if (fragment != null) {
					settingsSeen = true;
				} else if (settingsSeen || attempts >= 20) {
					onClosed.run();
					return;
				}
				attempts++;
				activity.getApp().runInUIThread(this, 400);
			}
		}, 400);
	}

	private static void reopenCurrentStep(@NonNull MapActivity activity) {
		if (!canShow(activity) || dialogShowing) {
			return;
		}
		int step = clampStep(getPreferences(activity).getInt(KEY_CURRENT_STEP, 0));
		showStep(activity, step, manualReview);
	}

	private static boolean canShow(@NonNull MapActivity activity) {
		return !activity.isFinishing()
				&& (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed())
				&& RoadCrewReportsLayer.isEnabled(activity.getApp());
	}

	private static boolean areAllStepsReady(@NonNull MapActivity activity) {
		for (int step = 0; step < STEP_COUNT; step++) {
			if (!isStepReady(activity, step)) {
				return false;
			}
		}
		return true;
	}

	private static int findFirstIncompleteStep(@NonNull MapActivity activity) {
		for (int step = 0; step < STEP_COUNT; step++) {
			if (!isStepReady(activity, step)) {
				return step;
			}
		}
		return STEP_COUNT - 1;
	}

	private static boolean isStepReady(@NonNull MapActivity activity, int step) {
		switch (step) {
			case 0:
				return RoadCrewSetupStatus.isLocationPermissionReady(activity);
			case 1:
				return RoadCrewSetupStatus.isDeviceLocationReady(activity);
			case 2:
				return RoadCrewSetupStatus.areNotificationsReady(activity);
			case 3:
				return RoadCrewSetupStatus.isOfflineMapReady(activity.getApp());
			case 4:
				return RoadCrewSetupStatus.isDriverProfileReady(activity.getApp());
			case 5:
				return RoadCrewSetupStatus.areTruckVehicleParametersReady(activity.getApp());
			default:
				return false;
		}
	}

	@NonNull
	private static String getStepTitle(@NonNull MapActivity activity, int step) {
		int stringId;
		switch (step) {
			case 0:
				stringId = R.string.roadcrew_setup_location_permission;
				break;
			case 1:
				stringId = R.string.roadcrew_setup_device_location;
				break;
			case 2:
				stringId = R.string.roadcrew_setup_notifications;
				break;
			case 3:
				stringId = R.string.roadcrew_setup_offline_map;
				break;
			case 4:
				stringId = R.string.roadcrew_profile_title;
				break;
			default:
				stringId = R.string.roadcrew_profile_vehicle_parameters;
				break;
		}
		return activity.getString(stringId);
	}

	@NonNull
	private static String getStepBody(@NonNull MapActivity activity, int step) {
		int stringId;
		switch (step) {
			case 0:
				stringId = R.string.roadcrew_setup_location_permission_body;
				break;
			case 1:
				stringId = R.string.roadcrew_setup_device_location_body;
				break;
			case 2:
				stringId = R.string.roadcrew_setup_notifications_body;
				break;
			case 3:
				stringId = R.string.roadcrew_setup_offline_map_body;
				break;
			case 4:
				stringId = R.string.roadcrew_setup_driver_profile_body;
				break;
			default:
				stringId = R.string.roadcrew_profile_vehicle_parameters_body;
				break;
		}
		return activity.getString(stringId);
	}

	@NonNull
	private static String getStepActionTitle(@NonNull MapActivity activity, int step) {
		int stringId;
		switch (step) {
			case 0:
				stringId = R.string.roadcrew_setup_enable_location;
				break;
			case 1:
				stringId = R.string.roadcrew_setup_open_settings;
				break;
			case 2:
				stringId = R.string.roadcrew_setup_enable_notifications;
				break;
			case 3:
				stringId = R.string.roadcrew_setup_download_map;
				break;
			case 4:
				stringId = R.string.roadcrew_setup_complete_profile;
				break;
			default:
				stringId = R.string.roadcrew_profile_vehicle_parameters_button;
				break;
		}
		return activity.getString(stringId);
	}

	private static int clampStep(int step) {
		return Math.max(0, Math.min(STEP_COUNT - 1, step));
	}

	@NonNull
	private static SharedPreferences getPreferences(@NonNull Context context) {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
