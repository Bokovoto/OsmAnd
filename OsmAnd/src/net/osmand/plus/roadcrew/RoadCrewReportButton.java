package net.osmand.plus.roadcrew;

import static net.osmand.plus.quickaction.ButtonAppearanceParams.BIG_SIZE_DP;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.OPAQUE_ALPHA;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.ROUND_RADIUS_DP;
import static net.osmand.shared.grid.ButtonPositionSize.POS_BOTTOM;
import static net.osmand.shared.grid.ButtonPositionSize.POS_RIGHT;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.quickaction.ButtonAppearanceParams;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.views.controls.maphudbuttons.MapButton;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;
import net.osmand.shared.grid.ButtonPositionSize;

public class RoadCrewReportButton extends MapButton {

	private static final String BUTTON_ID = "roadcrew_report_button";
	private static final int HELP_DETAILS_MAX_LENGTH = 180;
	private static final int PLATE_MAX_LENGTH = 20;
	private static final int PLATE_ALERT_MESSAGE_MAX_LENGTH = 160;
	private static final String[] PLATE_ALERT_CATEGORIES = {
			"TRAILER_DOOR_OPEN",
			"LOOSE_STRAP",
			"TIRE_ISSUE",
			"SMOKE",
			"SPARKS",
			"LOAD_MOVING",
			"OTHER"
	};

	private final ButtonPositionSize defaultPositionSize;

	public RoadCrewReportButton(@NonNull Context context) {
		this(context, null);
	}

	public RoadCrewReportButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public RoadCrewReportButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		defaultPositionSize = createDefaultPositionSize();
		setContentDescription(context.getString(R.string.roadcrew_report_button_content_description));
		setAlwaysVisible(true);
		setOnClickListener(v -> showReportTypeDialog());
	}

	@NonNull
	@Override
	public String getButtonId() {
		return BUTTON_ID;
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return null;
	}

	@NonNull
	@Override
	public ButtonAppearanceParams createDefaultAppearanceParams() {
		return new ButtonAppearanceParams("ic_roadcrew_report", BIG_SIZE_DP, OPAQUE_ALPHA, ROUND_RADIUS_DP);
	}

	@Nullable
	@Override
	public ButtonPositionSize getDefaultPositionSize() {
		return defaultPositionSize;
	}

	@Override
	protected boolean shouldShow() {
		return !routeDialogOpened && RoadCrewReportsLayer.isEnabled(app);
	}

	@Override
	protected void updateColors(boolean nightMode) {
		setIconColor(0);
		setBackgroundColors(0xff19a974, ColorUtilities.getColorWithAlpha(0xff19a974, 0.75f));
	}

	private ButtonPositionSize createDefaultPositionSize() {
		ButtonPositionSize position = new ButtonPositionSize(BUTTON_ID);
		position.setPositionHorizontal(POS_RIGHT);
		position.setPositionVertical(POS_BOTTOM);
		position.setMoveVertical();
		position.setMarginX(0);
		position.setMarginY(0);
		int size = BIG_SIZE_DP / 8 + 1;
		position.setSize(size, size);
		return position;
	}

	private void showReportTypeDialog() {
		if (mapActivity == null) {
			return;
		}
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_menu_title));

		GridLayout grid = new GridLayout(mapActivity);
		grid.setColumnCount(3);
		grid.setUseDefaultMargins(false);
		LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		gridParams.topMargin = dp(18);
		content.addView(grid, gridParams);

		AlertDialog[] dialogHolder = new AlertDialog[1];
		addMenuTile(grid, mapActivity.getString(R.string.roadcrew_report_type_help), R.drawable.roadcrew_menu_help,
				() -> showHelpDetailsDialog(), dialogHolder);
		addMenuTile(grid, RoadCrewReportType.DAI.getTitle(mapActivity), R.drawable.roadcrew_menu_police,
				() -> addReport(RoadCrewReportType.DAI, ""), dialogHolder);
		addMenuTile(grid, RoadCrewReportType.WEIGH_STATION.getTitle(mapActivity), R.drawable.roadcrew_menu_scale,
				() -> addReport(RoadCrewReportType.WEIGH_STATION, ""), dialogHolder);
		addMenuTile(grid, RoadCrewReportType.DANGER.getTitle(mapActivity), R.drawable.roadcrew_menu_danger,
				() -> addReport(RoadCrewReportType.DANGER, ""), dialogHolder);
		addMenuTile(grid, RoadCrewReportType.CAMERA.getTitle(mapActivity), R.drawable.roadcrew_menu_camera,
				() -> addReport(RoadCrewReportType.CAMERA, ""), dialogHolder);
		addMenuTile(grid, mapActivity.getString(R.string.roadcrew_menu_warn_driver), R.drawable.roadcrew_menu_warn_driver,
				this::showPlateAlertNumberDialog, dialogHolder);
		addMenuTile(grid, mapActivity.getString(R.string.roadcrew_menu_check_update), R.drawable.roadcrew_menu_update,
				() -> RoadCrewAppUpdater.checkForUpdatesNow(mapActivity), dialogHolder);
		addMenuTile(grid, mapActivity.getString(R.string.roadcrew_menu_driver_profile), R.drawable.roadcrew_menu_profile,
				this::showDriverProfileDialog, dialogHolder);

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		dialogHolder[0] = dialog;
		dialog.show();
	}

	private void addMenuTile(@NonNull GridLayout grid, @NonNull String label, int iconRes,
			@NonNull Runnable action, @NonNull AlertDialog[] dialogHolder) {
		LinearLayout tile = new LinearLayout(mapActivity);
		tile.setOrientation(LinearLayout.VERTICAL);
		tile.setGravity(Gravity.CENTER);
		tile.setPadding(dp(4), dp(8), dp(4), dp(8));
		tile.setMinimumHeight(dp(136));
		tile.setOnClickListener(v -> {
			if (dialogHolder[0] != null) {
				dialogHolder[0].dismiss();
			}
			action.run();
		});

		ImageView iconBackground = new ImageView(mapActivity);
		iconBackground.setImageResource(iconRes);
		iconBackground.setBackground(RoadCrewUi.oval(RoadCrewUi.SURFACE_LIGHT));
		iconBackground.setPadding(dp(12), dp(12), dp(12), dp(12));
		LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(84), dp(84));
		iconParams.gravity = Gravity.CENTER_HORIZONTAL;
		tile.addView(iconBackground, iconParams);

		TextView text = new TextView(mapActivity);
		text.setText(label);
		text.setTextColor(RoadCrewUi.TEXT);
		text.setTextSize(14);
		text.setGravity(Gravity.CENTER);
		text.setTypeface(text.getTypeface(), android.graphics.Typeface.BOLD);
		text.setMaxLines(2);
		LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		textParams.topMargin = dp(8);
		tile.addView(text, textParams);

		GridLayout.LayoutParams params = new GridLayout.LayoutParams();
		params.width = 0;
		params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
		params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
		params.setMargins(dp(3), dp(4), dp(3), dp(8));
		grid.addView(tile, params);
	}

	private void showPlateAlertNumberDialog() {
		if (mapActivity == null) {
			return;
		}
		EditText plateInput = createProfileInput(mapActivity.getString(R.string.roadcrew_warn_driver_search_hint), PLATE_MAX_LENGTH);
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_warn_driver_title));
		RoadCrewUi.addBody(mapActivity, content, mapActivity.getString(R.string.roadcrew_warn_driver_search_body));
		RoadCrewUi.addInput(mapActivity, content, plateInput);
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_next), true, v -> {
			String normalizedPlate = RoadCrewDriverProfile.normalizePlateNumber(plateInput.getText().toString());
			if (normalizedPlate.isEmpty()) {
				app.showToastMessage(R.string.roadcrew_warn_driver_empty_plate);
				return;
			}
			dialog.dismiss();
			showPlateAlertCategoryDialog(normalizedPlate);
		});
		dialog.show();
	}

	private void showPlateAlertCategoryDialog(@NonNull String normalizedPlate) {
		if (mapActivity == null) {
			return;
		}
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_warn_driver_problem_title));
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		String[] plateAlertTitles = mapActivity.getResources().getStringArray(R.array.roadcrew_plate_alert_titles);
		for (int i = 0; i < plateAlertTitles.length; i++) {
			final int index = i;
			RoadCrewUi.addFullWidthButton(mapActivity, content, plateAlertTitles[i], false, v -> {
				dialog.dismiss();
				showPlateAlertMessageDialog(normalizedPlate, PLATE_ALERT_CATEGORIES[index]);
			});
		}
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		dialog.show();
	}

	private void showPlateAlertMessageDialog(@NonNull String normalizedPlate, @NonNull String category) {
		if (mapActivity == null) {
			return;
		}
		EditText messageInput = createProfileInput(mapActivity.getString(R.string.roadcrew_warn_driver_optional_message), PLATE_ALERT_MESSAGE_MAX_LENGTH);
		messageInput.setMinLines(2);
		messageInput.setMaxLines(4);
		messageInput.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_warn_driver_add_message));
		RoadCrewUi.addInput(mapActivity, content, messageInput);
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_skip), false, v -> {
			dialog.dismiss();
			sendPlateSafetyAlert(normalizedPlate, category, "");
		});
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_send), true, v -> {
			dialog.dismiss();
			sendPlateSafetyAlert(normalizedPlate, category, messageInput.getText().toString());
		});
		dialog.show();
	}

	private void sendPlateSafetyAlert(@NonNull String normalizedPlate, @NonNull String category,
			@NonNull String message) {
		RoadCrewReportsSync.sendPlateSafetyAlert(app, normalizedPlate, category, message,
				new RoadCrewReportsSync.HelpResolveCallback() {
					@Override
					public void onSuccess() {
						app.showToastMessage(R.string.roadcrew_warn_driver_sent);
					}

					@Override
					public void onError(@NonNull Exception error) {
						String errorMessage = error.getMessage();
						app.showToastMessage(errorMessage == null || errorMessage.isEmpty()
								? mapActivity.getString(R.string.roadcrew_warn_driver_no_driver)
								: errorMessage);
					}
				});
	}

	private void showDriverProfileDialog() {
		if (mapActivity == null) {
			return;
		}
		RoadCrewDriverProfileDialog.show(mapActivity, app);
	}

	@NonNull
	private EditText createProfileInput(@NonNull String hint, int maxLength) {
		EditText input = RoadCrewUi.createInput(mapActivity, hint, false);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
		input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maxLength)});
		return input;
	}

	private void showHelpDetailsDialog() {
		if (mapActivity == null) {
			return;
		}
		EditText input = RoadCrewUi.createInput(mapActivity,
				mapActivity.getString(R.string.roadcrew_help_request_hint), true);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(HELP_DETAILS_MAX_LENGTH)});
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_help_request_title));
		RoadCrewUi.addInput(mapActivity, content, input);
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_send), true, v -> {
			dialog.dismiss();
			addReport(RoadCrewReportType.HELP, input.getText().toString().trim());
		});
		dialog.show();
	}

	private void addReport(@NonNull RoadCrewReportType type, @NonNull String details) {
		boolean usedGpsLocation = true;
		LatLon location = getPhoneLocation();
		if (location == null) {
			usedGpsLocation = false;
			RotatedTileBox tileBox = getMapView().getCurrentRotatedTileBox();
			location = tileBox.getCenterLatLon();
		}
		RoadCrewReportsRepository.addReport(app, RoadCrewReport.createLocal(type, location,
				System.currentTimeMillis(), RoadCrewReportsRepository.getLocalDeviceId(app), details));
		RoadCrewReportsSync.syncNow(app);
		getMapView().refreshMap();
		if (usedGpsLocation) {
			app.showToastMessage(mapActivity.getString(R.string.roadcrew_report_added, type.getTitle(mapActivity)));
		} else {
			app.showToastMessage(R.string.roadcrew_report_no_gps);
		}
	}

	@Nullable
	private LatLon getPhoneLocation() {
		Location location = app.getLocationProvider().getLastKnownLocation();
		if (location == null) {
			return null;
		}
		return new LatLon(location.getLatitude(), location.getLongitude());
	}

	private int dp(float value) {
		return (int) (value * getResources().getDisplayMetrics().density);
	}
}
