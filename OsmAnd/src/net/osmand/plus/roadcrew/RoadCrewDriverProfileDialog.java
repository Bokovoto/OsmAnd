package net.osmand.plus.roadcrew;

import static net.osmand.router.GeneralRouter.MAX_AXLE_LOAD;
import static net.osmand.router.GeneralRouter.VEHICLE_HEIGHT;
import static net.osmand.router.GeneralRouter.VEHICLE_LENGTH;
import static net.osmand.router.GeneralRouter.VEHICLE_WEIGHT;
import static net.osmand.router.GeneralRouter.VEHICLE_WIDTH;
import static net.osmand.router.GeneralRouter.WEIGHT_RATING;

import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.RoutingHelperUtils;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.router.GeneralRouter;
import net.osmand.router.GeneralRouter.RoutingParameter;
import net.osmand.util.Algorithms;

import java.util.Map;

final class RoadCrewDriverProfileDialog {

	private static final int DRIVER_NAME_MAX_LENGTH = 60;
	private static final int PLATE_MAX_LENGTH = 20;
	private static final String[] REQUIRED_TRUCK_PARAMETERS = {
			VEHICLE_WEIGHT,
			WEIGHT_RATING,
			MAX_AXLE_LOAD,
			VEHICLE_HEIGHT,
			VEHICLE_WIDTH,
			VEHICLE_LENGTH
	};

	private RoadCrewDriverProfileDialog() {
	}

	static void show(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app) {
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
		CheckBox showTruckRestrictions = new CheckBox(mapActivity);
		showTruckRestrictions.setText(mapActivity.getString(R.string.roadcrew_profile_show_truck_restrictions));
		showTruckRestrictions.setTextColor(RoadCrewUi.TEXT);
		showTruckRestrictions.setChecked(showTruckRestrictionsPreference.get());
		content.addView(showTruckRestrictions, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		RoadCrewUi.addBody(mapActivity, content, mapActivity.getString(R.string.roadcrew_profile_show_truck_restrictions_body));

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		Runnable saveProfile = () -> {
			boolean restrictionsChanged = showTruckRestrictionsPreference.get() != showTruckRestrictions.isChecked();
			RoadCrewDriverProfile.save(app,
					driverName.getText().toString(),
					truckNumber.getText().toString(),
					trailerNumber.getText().toString(),
					plateAlertsEnabled.isChecked());
			showTruckRestrictionsPreference.set(showTruckRestrictions.isChecked());
			RoadCrewReportsSync.syncNow(app);
			if (restrictionsChanged) {
				mapActivity.getMapView().refreshMap();
			}
		};

		RoadCrewUi.addSectionTitle(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_profile_vehicle_parameters));
		boolean vehicleParametersComplete = areTruckVehicleParametersComplete(app);
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
					dialog.dismiss();
					BaseSettingsFragment.showInstance(mapActivity,
							SettingsScreenType.VEHICLE_PARAMETERS, ApplicationMode.TRUCK);
				});

		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_save), true, v -> {
			saveProfile.run();
			app.showToastMessage(R.string.roadcrew_profile_saved);
			dialog.dismiss();
		});
		dialog.show();
	}

	private static boolean areTruckVehicleParametersComplete(@NonNull OsmandApplication app) {
		GeneralRouter router = app.getRouter(ApplicationMode.TRUCK);
		if (router == null) {
			return false;
		}
		Map<String, RoutingParameter> parameters = RoutingHelperUtils.getParametersForDerivedProfile(
				ApplicationMode.TRUCK, router);
		for (String parameterId : REQUIRED_TRUCK_PARAMETERS) {
			RoutingParameter parameter = parameters.get(parameterId);
			if (parameter == null) {
				return false;
			}
			String value = app.getSettings()
					.getCustomRoutingProperty(parameterId, parameter.getDefaultString())
					.getModeValue(ApplicationMode.TRUCK);
			if (!isPositiveNumber(value)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isPositiveNumber(String value) {
		if (Algorithms.isEmpty(value) || "-".equals(value)) {
			return false;
		}
		try {
			return Double.parseDouble(value) > 0;
		} catch (NumberFormatException e) {
			return false;
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
