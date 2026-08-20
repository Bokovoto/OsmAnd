package net.osmand.plus.roadcrew;

import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.plus.widgets.tools.SimpleTextWatcher;

final class RoadCrewDriverProfileDialog {

	private static final int DRIVER_NAME_MAX_LENGTH = 60;
	private static final int PLATE_MAX_LENGTH = 20;
	private RoadCrewDriverProfileDialog() {
	}

	static void show(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app) {
		show(mapActivity, app, null);
	}

	static void show(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
			@Nullable Runnable onClosed) {
		show(mapActivity, app, onClosed, false);
	}

	static void showForSetup(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
			@Nullable Runnable onClosed) {
		show(mapActivity, app, onClosed, true);
	}

	private static void show(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
			@Nullable Runnable onClosed, boolean setupMode) {
		RoadCrewDriverProfile profile = RoadCrewDriverProfile.load(app);
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_profile_title));

		EditText driverName = createProfileInput(mapActivity, mapActivity.getString(R.string.roadcrew_profile_driver_name), DRIVER_NAME_MAX_LENGTH);
		driverName.setText(profile.getDriverName());
		content.addView(driverName, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		EditText truckNumber = createProfileInput(mapActivity, mapActivity.getString(R.string.roadcrew_profile_truck_number), PLATE_MAX_LENGTH);
		truckNumber.setText(profile.getTruckNumber());
		content.addView(truckNumber, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		EditText trailerNumber = createProfileInput(mapActivity, mapActivity.getString(R.string.roadcrew_profile_trailer_number), PLATE_MAX_LENGTH);
		trailerNumber.setText(profile.getTrailerNumber());
		content.addView(trailerNumber, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		CheckBox plateAlertsEnabled = new CheckBox(mapActivity);
		plateAlertsEnabled.setText(mapActivity.getString(R.string.roadcrew_profile_allow_plate_alerts));
		plateAlertsEnabled.setTextColor(RoadCrewUi.TEXT);
		plateAlertsEnabled.setChecked(profile.isPlateAlertsEnabled());
		content.addView(plateAlertsEnabled, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		CommonPreference<Boolean> showTruckRestrictionsPreference = RoadCrewSettings.showTruckRestrictions(app);
		CheckBox showTruckRestrictions = setupMode ? null : new CheckBox(mapActivity);
		if (showTruckRestrictions != null) {
			showTruckRestrictions.setText(mapActivity.getString(R.string.roadcrew_profile_show_truck_restrictions));
			showTruckRestrictions.setTextColor(RoadCrewUi.TEXT);
			showTruckRestrictions.setChecked(showTruckRestrictionsPreference.get());
			content.addView(showTruckRestrictions, new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			RoadCrewUi.addBody(mapActivity, content,
					mapActivity.getString(R.string.roadcrew_profile_show_truck_restrictions_body));
		}

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		boolean[] openingSettings = {false};
		Runnable saveProfile = () -> {
			boolean restrictionsChanged = showTruckRestrictions != null
					&& showTruckRestrictionsPreference.get() != showTruckRestrictions.isChecked();
			RoadCrewDriverProfile.save(app,
					driverName.getText().toString(),
					truckNumber.getText().toString(),
					trailerNumber.getText().toString(),
					plateAlertsEnabled.isChecked());
			if (showTruckRestrictions != null) {
				showTruckRestrictionsPreference.set(showTruckRestrictions.isChecked());
			}
			RoadCrewReportsSync.syncNow(app);
			if (restrictionsChanged) {
				mapActivity.getMapView().refreshMap();
			}
		};

		if (!setupMode) {
			addAdvancedSections(mapActivity, app, content, dialog, openingSettings, saveProfile, onClosed);
		}

		Button saveButton;
		if (setupMode) {
			saveButton = RoadCrewUi.addFullWidthButton(mapActivity, content,
					mapActivity.getString(R.string.roadcrew_button_save_continue), true, v -> {
						saveProfile.run();
						app.showToastMessage(R.string.roadcrew_profile_saved);
						dialog.dismiss();
					});
			SimpleTextWatcher watcher = new SimpleTextWatcher() {
				@Override
				public void afterTextChanged(Editable s) {
					updateSetupSaveButton(saveButton, driverName, truckNumber, trailerNumber, plateAlertsEnabled);
				}
			};
			driverName.addTextChangedListener(watcher);
			truckNumber.addTextChangedListener(watcher);
			trailerNumber.addTextChangedListener(watcher);
			plateAlertsEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
					updateSetupSaveButton(saveButton, driverName, truckNumber, trailerNumber, plateAlertsEnabled));
			updateSetupSaveButton(saveButton, driverName, truckNumber, trailerNumber, plateAlertsEnabled);
		} else {
			LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
			RoadCrewUi.addButton(mapActivity, buttons,
					mapActivity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
			saveButton = RoadCrewUi.addButton(mapActivity, buttons,
					mapActivity.getString(R.string.roadcrew_button_save), true, v -> {
						saveProfile.run();
						app.showToastMessage(R.string.roadcrew_profile_saved);
						dialog.dismiss();
					});
		}
		dialog.setOnDismissListener(d -> {
			if (onClosed != null && !openingSettings[0]) {
				onClosed.run();
			}
		});
		dialog.show();
	}

	private static void addAdvancedSections(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
			@NonNull LinearLayout content, @NonNull AlertDialog dialog, @NonNull boolean[] openingSettings,
			@NonNull Runnable saveProfile, @Nullable Runnable onClosed) {
		RoadCrewUi.addSectionTitle(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_setup_status));
		boolean phoneSetupComplete = RoadCrewSetupStatus.isPhoneSetupReady(mapActivity, app);
		String setupStatusText = phoneSetupComplete
				? mapActivity.getString(R.string.roadcrew_profile_setup_ready)
				: mapActivity.getString(R.string.roadcrew_profile_setup_incomplete,
						getMissingSetupItems(mapActivity, app));
		TextView setupStatus = RoadCrewUi.addBody(mapActivity, content, setupStatusText);
		setupStatus.setTextColor(phoneSetupComplete ? RoadCrewUi.PRIMARY : RoadCrewUi.DANGER);
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_setup_body));
		RoadCrewUi.addFullWidthButton(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_setup_button), true, v -> {
					saveProfile.run();
					openingSettings[0] = true;
					dialog.dismiss();
					RoadCrewStartupSetup.showManually(mapActivity);
				});

		RoadCrewUi.addSectionTitle(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_vehicle_parameters));
		boolean vehicleParametersComplete = RoadCrewSetupStatus.areTruckVehicleParametersReady(app);
		TextView vehicleStatus = RoadCrewUi.addBody(mapActivity, content, mapActivity.getString(
				vehicleParametersComplete
						? R.string.roadcrew_profile_vehicle_parameters_ready
						: R.string.roadcrew_profile_vehicle_parameters_incomplete));
		vehicleStatus.setTextColor(vehicleParametersComplete ? RoadCrewUi.PRIMARY : RoadCrewUi.DANGER);
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_vehicle_parameters_body));
		RoadCrewUi.addFullWidthButton(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_vehicle_parameters_button), true, v -> {
					saveProfile.run();
					openingSettings[0] = true;
					dialog.dismiss();
					if (onClosed != null) {
						RoadCrewStartupSetup.runAfterVehicleSettingsClosed(mapActivity, onClosed);
					}
					BaseSettingsFragment.showInstance(mapActivity,
							SettingsScreenType.VEHICLE_PARAMETERS, ApplicationMode.TRUCK);
				});
	}

	private static void updateSetupSaveButton(@NonNull Button saveButton, @NonNull EditText driverName,
			@NonNull EditText truckNumber, @NonNull EditText trailerNumber,
			@NonNull CheckBox plateAlertsEnabled) {
		boolean hasName = !driverName.getText().toString().trim().isEmpty();
		boolean hasPlate = !RoadCrewDriverProfile.normalizePlateNumber(truckNumber.getText().toString()).isEmpty()
				|| !RoadCrewDriverProfile.normalizePlateNumber(trailerNumber.getText().toString()).isEmpty();
		boolean enabled = hasName && hasPlate && plateAlertsEnabled.isChecked();
		saveButton.setEnabled(enabled);
		saveButton.setAlpha(enabled ? 1.0f : 0.45f);
	}

	@NonNull
	private static String getMissingSetupItems(@NonNull MapActivity activity,
			@NonNull OsmandApplication app) {
		StringBuilder missing = new StringBuilder();
		appendMissing(missing, RoadCrewSetupStatus.isLocationPermissionReady(activity),
				activity.getString(R.string.roadcrew_setup_location_permission));
		appendMissing(missing, RoadCrewSetupStatus.isDeviceLocationReady(activity),
				activity.getString(R.string.roadcrew_setup_device_location));
		appendMissing(missing, RoadCrewSetupStatus.areNotificationsReady(activity),
				activity.getString(R.string.roadcrew_setup_notifications));
		appendMissing(missing, RoadCrewSetupStatus.isOfflineMapReady(app),
				activity.getString(R.string.roadcrew_setup_offline_map));
		return missing.toString();
	}

	private static void appendMissing(@NonNull StringBuilder missing, boolean ready,
			@NonNull String title) {
		if (!ready) {
			if (missing.length() > 0) {
				missing.append(", ");
			}
			missing.append(title);
		}
	}

	@NonNull
	private static EditText createProfileInput(@NonNull MapActivity mapActivity, @NonNull String hint, int maxLength) {
		EditText input = RoadCrewUi.createInput(mapActivity, hint, false);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
		input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maxLength)});
		return input;
	}
}
