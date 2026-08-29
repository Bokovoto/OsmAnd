package net.osmand.plus.roadcrew;

import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

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
				mapActivity.getString(R.string.roadcrew_visual_style_title));
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_visual_style_body));
		RadioGroup visualStyles = new RadioGroup(mapActivity);
		visualStyles.setOrientation(RadioGroup.VERTICAL);
		RadioButton classicStyle = createStyleOption(mapActivity,
				R.string.roadcrew_visual_style_classic);
		RadioButton neonStyle = createStyleOption(mapActivity,
				R.string.roadcrew_visual_style_neon_beta);
		visualStyles.addView(classicStyle);
		visualStyles.addView(neonStyle);
		if (RoadCrewVisualStyle.isNeonBeta(app)) {
			neonStyle.setChecked(true);
		} else {
			classicStyle.setChecked(true);
		}
		visualStyles.setOnCheckedChangeListener((group, checkedId) -> {
			boolean neonEnabled = checkedId == neonStyle.getId();
			RoadCrewVisualStyle.setNeonBeta(app, neonEnabled);
			RoadCrewNeonHud.apply(mapActivity);
			mapActivity.getMapView().refreshMap();
		});
		content.addView(visualStyles, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

		RoadCrewUi.addSectionTitle(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_live_truck_map_title));
		RoadCrewUi.addFullWidthButton(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_trip_review_title), true, v -> {
					saveProfile.run();
					openingSettings[0] = true;
					dialog.dismiss();
					RoadCrewReportsLayer.requestSegmentValidation();
				});
		boolean observationEnabled = RoadCrewMapObservationConsent.isEnabled(app);
		TextView observationStatus = RoadCrewUi.addBody(mapActivity, content, "");
		TextView routingAccess = RoadCrewUi.addBody(mapActivity, content, "");
		TextView contribution = RoadCrewUi.addBody(mapActivity, content, "");
		TextView lastUpload = RoadCrewUi.addBody(mapActivity, content, "");
		TextView shadowSnapshot = RoadCrewUi.addBody(mapActivity, content, "");
		TextView shadowRouteDiagnostic = RoadCrewUi.addBody(mapActivity, content, "");
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_live_truck_map_profile_body));
		RoadCrewUi.addBody(mapActivity, content, mapActivity.getString(R.string.roadcrew_trip_review_privacy));
		CheckBox observationConsent = new CheckBox(mapActivity);
		observationConsent.setText(R.string.roadcrew_live_truck_map_consent);
		observationConsent.setTextColor(RoadCrewUi.TEXT);
		observationConsent.setChecked(observationEnabled);
		Runnable updateObservationStatus = () -> updateLiveTruckMapStatus(mapActivity, app,
				observationStatus, routingAccess, contribution, lastUpload, shadowSnapshot,
				shadowRouteDiagnostic);
		observationConsent.setOnCheckedChangeListener((buttonView, isChecked) -> {
			RoadCrewReportsLayer.setMapObservationEnabled(app, isChecked);
			updateObservationStatus.run();
		});
		content.addView(observationConsent, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		updateObservationStatus.run();
	}

	private static void updateLiveTruckMapStatus(@NonNull MapActivity activity,
			@NonNull OsmandApplication app, @NonNull TextView status,
			@NonNull TextView access, @NonNull TextView contribution,
			@NonNull TextView lastUpload, @NonNull TextView shadowSnapshot,
			@NonNull TextView shadowRouteDiagnostic) {
		RoadCrewMapObservationCoordinator.StatusSnapshot snapshot =
				RoadCrewMapObservationCoordinator.getStatus(app);
		status.setText(getLiveTruckMapStatusText(snapshot.status));
		status.setTextColor(getLiveTruckMapStatusColor(snapshot.status));
		access.setText(snapshot.communityRoutingAccess
				? R.string.roadcrew_live_truck_map_access_enabled
				: R.string.roadcrew_live_truck_map_access_disabled);
		access.setTextColor(snapshot.communityRoutingAccess
				? RoadCrewUi.PRIMARY : RoadCrewUi.SECONDARY_TEXT);
		contribution.setText(activity.getString(R.string.roadcrew_live_truck_map_contribution,
				snapshot.uploadedObservationCount, snapshot.pendingObservationCount,
				snapshot.rejectedObservationCount) + "\n\n" + activity.getString(R.string.roadcrew_trip_review_pending,
				RoadCrewTripJournal.stagedCount(app), RoadCrewTripJournal.waitingCount(app))
				+ (RoadCrewTripJournal.isFull(app) ? "\n" + activity.getString(R.string.roadcrew_trip_review_full) : ""));
		if (snapshot.lastUploadAtMillis > 0) {
			String formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
					.format(new Date(snapshot.lastUploadAtMillis));
			lastUpload.setText(activity.getString(R.string.roadcrew_live_truck_map_last_upload,
					formatted));
		} else {
			lastUpload.setText(R.string.roadcrew_live_truck_map_last_upload_none);
		}
		RoadCrewShadowSnapshotDownloader.Summary shadow =
				RoadCrewShadowSnapshotDownloader.getCachedSummary(app);
		if (shadow.available && snapshot.communityRoutingAccess) {
			String formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
					.format(new Date(shadow.generatedAtMillis));
			shadowSnapshot.setText(activity.getString(R.string.roadcrew_live_truck_map_shadow_snapshot,
					shadow.totalCount, shadow.collectingCount, shadow.candidateCount,
					shadow.matureCount, formatted));
		} else {
			shadowSnapshot.setText(R.string.roadcrew_live_truck_map_shadow_snapshot_none);
		}
		RoadCrewShadowRouteDiagnostics.Summary diagnostic =
				RoadCrewShadowRouteDiagnostics.getLastSummary(app);
		if (diagnostic.available && snapshot.communityRoutingAccess) {
			String evaluatedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
					.format(new Date(diagnostic.evaluatedAtMillis));
			String evaluatedKilometers = String.format(Locale.getDefault(), "%.1f",
					diagnostic.evaluatedDistanceMeters / 1000.0);
			String routeKilometers = String.format(Locale.getDefault(), "%.1f",
					diagnostic.routeDistanceMeters / 1000.0);
			shadowRouteDiagnostic.setText(activity.getString(
					R.string.roadcrew_live_truck_map_shadow_route_diagnostic,
					evaluatedKilometers, routeKilometers,
					Math.round(diagnostic.exactCoverage * 100),
					Math.round(diagnostic.matureCoverage * 100),
					Math.round(diagnostic.confidenceCoverage * 100),
					diagnostic.exactMatchCount, diagnostic.evaluatedSegmentCount, evaluatedAt));
		} else {
			shadowRouteDiagnostic.setText(
					R.string.roadcrew_live_truck_map_shadow_route_diagnostic_none);
		}
	}

	private static int getLiveTruckMapStatusText(
			@NonNull RoadCrewMapObservationCoordinator.CollectionStatus status) {
		switch (status) {
			case ACTIVE:
				return R.string.roadcrew_live_truck_map_status_active;
			case WAITING_FOR_GPS:
				return R.string.roadcrew_live_truck_map_status_waiting_gps;
			case TRUCK_PROFILE_REQUIRED:
				return R.string.roadcrew_live_truck_map_status_truck_required;
			case PAUSED:
				return R.string.roadcrew_live_truck_map_status_paused;
			case UPLOAD_WARNING:
				return R.string.roadcrew_live_truck_map_status_upload_warning;
			case UPLOAD_ERROR:
				return R.string.roadcrew_live_truck_map_status_upload_error;
			case OFF:
			default:
				return R.string.roadcrew_live_truck_map_status_disabled;
		}
	}

	private static int getLiveTruckMapStatusColor(
			@NonNull RoadCrewMapObservationCoordinator.CollectionStatus status) {
		switch (status) {
			case ACTIVE:
				return RoadCrewUi.PRIMARY;
			case UPLOAD_WARNING:
				return 0xffffb020;
			case UPLOAD_ERROR:
				return RoadCrewUi.DANGER;
			case WAITING_FOR_GPS:
			case TRUCK_PROFILE_REQUIRED:
				return 0xffffb020;
			case PAUSED:
			case OFF:
			default:
				return RoadCrewUi.SECONDARY_TEXT;
		}
	}

	@NonNull
	private static RadioButton createStyleOption(@NonNull MapActivity mapActivity, int titleRes) {
		RadioButton option = new RadioButton(mapActivity);
		option.setId(android.view.View.generateViewId());
		option.setText(titleRes);
		option.setTextColor(RoadCrewUi.TEXT);
		option.setTextSize(15);
		option.setMinHeight(RoadCrewUi.dp(mapActivity, 48));
		return option;
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
