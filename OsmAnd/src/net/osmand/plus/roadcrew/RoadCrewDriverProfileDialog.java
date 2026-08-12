package net.osmand.plus.roadcrew;

import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;

final class RoadCrewDriverProfileDialog {

	private static final int DRIVER_NAME_MAX_LENGTH = 60;
	private static final int PLATE_MAX_LENGTH = 20;

	private RoadCrewDriverProfileDialog() {
	}

	static void show(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app) {
		RoadCrewDriverProfile profile = RoadCrewDriverProfile.load(app);
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, "Driver profile");

		EditText driverName = createProfileInput(mapActivity, "Driver name", DRIVER_NAME_MAX_LENGTH);
		driverName.setText(profile.getDriverName());
		content.addView(driverName, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		EditText truckNumber = createProfileInput(mapActivity, "Truck number", PLATE_MAX_LENGTH);
		truckNumber.setText(profile.getTruckNumber());
		content.addView(truckNumber, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		EditText trailerNumber = createProfileInput(mapActivity, "Trailer number", PLATE_MAX_LENGTH);
		trailerNumber.setText(profile.getTrailerNumber());
		content.addView(trailerNumber, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		CheckBox plateAlertsEnabled = new CheckBox(mapActivity);
		plateAlertsEnabled.setText("Allow safety alerts by truck/trailer number");
		plateAlertsEnabled.setTextColor(RoadCrewUi.TEXT);
		plateAlertsEnabled.setChecked(profile.isPlateAlertsEnabled());
		content.addView(plateAlertsEnabled, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, "Cancel", false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, "Save", true, v -> {
			RoadCrewDriverProfile.save(app,
					driverName.getText().toString(),
					truckNumber.getText().toString(),
					trailerNumber.getText().toString(),
					plateAlertsEnabled.isChecked());
			RoadCrewReportsSync.syncNow(app);
			app.showToastMessage("Driver profile saved.");
			dialog.dismiss();
		});
		dialog.show();
	}

	@NonNull
	private static EditText createProfileInput(@NonNull MapActivity mapActivity, @NonNull String hint, int maxLength) {
		EditText input = RoadCrewUi.createInput(mapActivity, hint, false);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
		input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maxLength)});
		return input;
	}
}
