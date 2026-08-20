package net.osmand.plus.roadcrew;

import static net.osmand.router.GeneralRouter.VEHICLE_HEIGHT;
import static net.osmand.router.GeneralRouter.VEHICLE_LENGTH;
import static net.osmand.router.GeneralRouter.VEHICLE_WEIGHT;
import static net.osmand.router.GeneralRouter.VEHICLE_WIDTH;

import android.text.Editable;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.RoutingHelperUtils;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.widgets.tools.SimpleTextWatcher;
import net.osmand.router.GeneralRouter;
import net.osmand.router.GeneralRouter.RoutingParameter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class RoadCrewVehicleParametersDialog {

	private static final ApplicationMode TRUCK_MODE = ApplicationMode.TRUCK;

	private RoadCrewVehicleParametersDialog() {
	}

	static void show(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
			@Nullable Runnable onClosed) {
		GeneralRouter router = app.getRouter(TRUCK_MODE);
		Map<String, RoutingParameter> parameters = router == null
				? new LinkedHashMap<>()
				: RoutingHelperUtils.getParametersForDerivedProfile(TRUCK_MODE, router);

		LinearLayout content = RoadCrewUi.createPanel(mapActivity,
				mapActivity.getString(R.string.roadcrew_profile_vehicle_parameters));
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_setup_vehicle_parameters_body));

		LinkedHashMap<String, EditText> fields = new LinkedHashMap<>();
		addField(mapActivity, app, content, parameters, fields, VEHICLE_HEIGHT,
				R.string.roadcrew_vehicle_height_m);
		addField(mapActivity, app, content, parameters, fields, VEHICLE_WIDTH,
				R.string.roadcrew_vehicle_width_m);
		addField(mapActivity, app, content, parameters, fields, VEHICLE_WEIGHT,
				R.string.roadcrew_vehicle_weight_t);
		addField(mapActivity, app, content, parameters, fields, VEHICLE_LENGTH,
				R.string.roadcrew_vehicle_length_m);

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		Button saveButton = RoadCrewUi.addFullWidthButton(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_button_save_continue), true, v -> {
					for (Map.Entry<String, EditText> entry : fields.entrySet()) {
						RoutingParameter parameter = parameters.get(entry.getKey());
						if (parameter != null) {
							String value = normalizeValue(entry.getValue().getText().toString());
							app.getSettings().getCustomRoutingProperty(entry.getKey(), parameter.getDefaultString())
									.setModeValue(TRUCK_MODE, value);
						}
					}
					app.getRoutingHelper().onSettingsChanged(TRUCK_MODE);
					app.showToastMessage(R.string.roadcrew_profile_saved);
					dialog.dismiss();
				});

		SimpleTextWatcher watcher = new SimpleTextWatcher() {
			@Override
			public void afterTextChanged(Editable s) {
				updateSaveButton(saveButton, fields, parameters);
			}
		};
		for (EditText field : fields.values()) {
			field.addTextChangedListener(watcher);
		}
		updateSaveButton(saveButton, fields, parameters);
		dialog.setOnDismissListener(d -> {
			if (onClosed != null) {
				onClosed.run();
			}
		});
		dialog.show();
	}

	private static void addField(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
			@NonNull LinearLayout content, @NonNull Map<String, RoutingParameter> parameters,
			@NonNull Map<String, EditText> fields, @NonNull String parameterId, int labelId) {
		RoutingParameter parameter = parameters.get(parameterId);
		if (parameter == null) {
			return;
		}
		RoadCrewUi.addBody(mapActivity, content, mapActivity.getString(labelId));
		EditText input = RoadCrewUi.createInput(mapActivity, "", false);
		input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		String value = app.getSettings().getCustomRoutingProperty(parameterId, parameter.getDefaultString())
				.getModeValue(TRUCK_MODE);
		if (isPositiveNumber(value)) {
			input.setText(formatValue(Double.parseDouble(value)));
		}
		content.addView(input, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		fields.put(parameterId, input);
	}

	private static void updateSaveButton(@NonNull Button saveButton,
			@NonNull Map<String, EditText> fields, @NonNull Map<String, RoutingParameter> parameters) {
		boolean enabled = fields.size() == 4;
		for (Map.Entry<String, EditText> entry : fields.entrySet()) {
			enabled &= parameters.containsKey(entry.getKey())
					&& isPositiveNumber(entry.getValue().getText().toString());
		}
		saveButton.setEnabled(enabled);
		saveButton.setAlpha(enabled ? 1.0f : 0.45f);
	}

	private static boolean isPositiveNumber(@Nullable String value) {
		try {
			double number = Double.parseDouble(normalizeValue(value));
			return Double.isFinite(number) && number > 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@NonNull
	private static String normalizeValue(@Nullable String value) {
		return value == null ? "" : value.trim().replace(',', '.');
	}

	@NonNull
	private static String formatValue(double value) {
		return String.format(Locale.US, "%.2f", value).replaceAll("\\.?0+$", "");
	}
}
