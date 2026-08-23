package net.osmand.plus.roadcrew;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.utils.FormattedValue;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.utils.OsmAndFormatterParams;
import net.osmand.plus.views.controls.MapHudLayout;
import net.osmand.plus.views.mapwidgets.TurnDrawable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RoadCrewNeonHud {

	private static final String HUD_TAG = "roadcrew_neon_beta_hud";
	private static final String HEADER_TAG = "roadcrew_neon_header";
	private static final String NAV_ICON_TAG_PREFIX = "roadcrew_neon_nav_icon_";
	private static final String NAV_TEXT_TAG_PREFIX = "roadcrew_neon_nav_text_";
	private static final String LIVE_STATUS_TAG = "roadcrew_live_truck_map_status";
	private static final String FOOTER_TAG = "roadcrew_neon_footer";
	private static final String LEFT_RAIL_TAG = "roadcrew_landscape_left_rail";
	private static final String RIGHT_RAIL_TAG = "roadcrew_landscape_right_rail";
	private static final String TURN_ICON_TAG = "roadcrew_landscape_turn_icon";
	private static final String TURN_DISTANCE_TAG = "roadcrew_landscape_turn_distance";
	private static final String CURRENT_SPEED_TAG = "roadcrew_landscape_current_speed";
	private static final String CURRENT_SPEED_UNIT_TAG = "roadcrew_landscape_current_speed_unit";
	private static final String SPEED_LIMIT_TAG = "roadcrew_landscape_speed_limit";
	private static final String DISTANCE_LEFT_TAG = "roadcrew_landscape_distance_left";
	private static final String TIME_LEFT_TAG = "roadcrew_landscape_time_left";
	private static final String LIVE_DOT_TAG = "roadcrew_landscape_live_dot";
	private static final int BACKGROUND = 0xf213171a;
	private static final int SURFACE = 0xf21c2226;
	private static final int PRIMARY = 0xff75d02c;
	private static final int TEXT = 0xfff4f7f5;
	private static final int SECONDARY_TEXT = 0xffb5bcb8;
	private static final int WAITING = 0xffffb020;
	private static final int ERROR = 0xffef5350;

	private RoadCrewNeonHud() {
	}

	public static void apply(@NonNull MapActivity activity) {
		MapHudLayout mapHud = activity.findViewById(R.id.map_hud_layout);
		if (mapHud == null) {
			return;
		}
		boolean mapThemeChanged = RoadCrewVisualStyle.syncMapTheme(activity);
		View existing = mapHud.findViewWithTag(HUD_TAG);
		boolean enabled = RoadCrewVisualStyle.isNeonBeta(activity);
		if (!enabled) {
			if (existing != null) {
				mapHud.removeView(existing);
			}
			setNativeHudOffsets(mapHud, false, false, false);
			if (mapThemeChanged) {
				activity.updateMapSettings(true);
			}
			return;
		}
		boolean nightMode = RoadCrewVisualStyle.isNeonNight(activity);
		boolean landscape = isLandscape(activity);
		if (existing != null && (!(existing instanceof NeonHudRoot)
				|| ((NeonHudRoot) existing).nightMode != nightMode
				|| ((NeonHudRoot) existing).landscape != landscape)) {
			mapHud.removeView(existing);
			existing = null;
		}
		if (existing == null) {
			mapHud.addView(createHud(activity, nightMode, landscape), new FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		}
		View root = mapHud.findViewWithTag(HUD_TAG);
		updateNavigationSelection(root, activity);
		updateResponsiveLayout(root, activity);
		updateLiveMapStatus(root, activity);
		if (mapThemeChanged) {
			activity.updateMapSettings(true);
		}
	}

	@NonNull
	private static View createHud(@NonNull MapActivity activity, boolean nightMode, boolean landscape) {
		FrameLayout root = new NeonHudRoot(activity, nightMode, landscape);
		root.setTag(HUD_TAG);
		root.setClickable(false);
		root.setFocusable(false);

		LinearLayout header = createHeader(activity, landscape);
		header.setTag(HEADER_TAG);
		FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, landscape ? 44 : 58), Gravity.TOP);
		headerParams.setMargins(dp(activity, landscape ? 8 : 10), dp(activity, landscape ? 4 : 6),
				dp(activity, landscape ? 8 : 10), 0);
		root.addView(header, headerParams);

		LinearLayout footer = createFooter(activity, landscape);
		footer.setTag(FOOTER_TAG);
		FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, landscape ? 50 : 68), Gravity.BOTTOM);
		root.addView(footer, footerParams);

		if (landscape) {
			root.addView(createLeftCockpitRail(activity), cockpitRailParams(activity, Gravity.START));
			root.addView(createRightCockpitRail(activity), cockpitRailParams(activity, Gravity.END));
		}
		return root;
	}

	@NonNull
	private static FrameLayout.LayoutParams cockpitRailParams(@NonNull MapActivity activity, int horizontalGravity) {
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
				dp(activity, 86), ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER_VERTICAL | horizontalGravity);
		params.setMargins(dp(activity, 8), dp(activity, 4), dp(activity, 8), dp(activity, 4));
		return params;
	}

	@NonNull
	private static LinearLayout createLeftCockpitRail(@NonNull MapActivity activity) {
		LinearLayout rail = createCockpitRail(activity);
		rail.setTag(LEFT_RAIL_TAG);

		ImageView turnIcon = new ImageView(activity);
		turnIcon.setTag(TURN_ICON_TAG);
		turnIcon.setContentDescription(activity.getString(R.string.map_widget_next_turn));
		rail.addView(turnIcon, centeredParams(activity, 48, 48));

		TextView turnDistance = cockpitText(activity, 16, Typeface.BOLD, TEXT);
		turnDistance.setTag(TURN_DISTANCE_TAG);
		rail.addView(turnDistance, matchWrapParams(activity, 0, 2));

		View divider = new View(activity);
		divider.setBackgroundColor(0x553d6f22);
		LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1));
		dividerParams.setMargins(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 5));
		rail.addView(divider, dividerParams);

		TextView speed = cockpitText(activity, 24, Typeface.BOLD, TEXT);
		speed.setTag(CURRENT_SPEED_TAG);
		rail.addView(speed, matchWrapParams(activity, 0, 0));

		TextView speedUnit = cockpitText(activity, 9, Typeface.NORMAL, SECONDARY_TEXT);
		speedUnit.setTag(CURRENT_SPEED_UNIT_TAG);
		rail.addView(speedUnit, matchWrapParams(activity, 0, 2));

		TextView speedLimit = cockpitText(activity, 15, Typeface.BOLD, Color.BLACK);
		speedLimit.setTag(SPEED_LIMIT_TAG);
		speedLimit.setGravity(Gravity.CENTER);
		speedLimit.setBackground(circle(Color.WHITE, 0xffd32f2f, dp(activity, 3)));
		LinearLayout.LayoutParams limitParams = centeredParams(activity, 40, 40);
		limitParams.topMargin = dp(activity, 5);
		rail.addView(speedLimit, limitParams);
		return rail;
	}

	@NonNull
	private static LinearLayout createRightCockpitRail(@NonNull MapActivity activity) {
		LinearLayout rail = createCockpitRail(activity);
		rail.setTag(RIGHT_RAIL_TAG);

		TextView liveDot = new TextView(activity);
		liveDot.setTag(LIVE_DOT_TAG);
		liveDot.setContentDescription(activity.getString(R.string.roadcrew_live_truck_map_indicator_off));
		rail.addView(liveDot, centeredParams(activity, 12, 12));

		TextView distanceLeft = cockpitText(activity, 14, Typeface.BOLD, TEXT);
		distanceLeft.setTag(DISTANCE_LEFT_TAG);
		LinearLayout.LayoutParams distanceParams = matchWrapParams(activity, 3, 0);
		rail.addView(distanceLeft, distanceParams);

		TextView timeLeft = cockpitText(activity, 12, Typeface.NORMAL, SECONDARY_TEXT);
		timeLeft.setTag(TIME_LEFT_TAG);
		rail.addView(timeLeft, matchWrapParams(activity, 2, 5));

		ImageButton report = iconButton(activity, R.drawable.ic_roadcrew_report,
				activity.getString(R.string.roadcrew_report_button_content_description),
				v -> showReports(activity), 10);
		report.setBackground(roundRect(0xff0b6b3b, dp(activity, 24), 0xff75d02c));
		rail.addView(report, centeredParams(activity, 48, 48));
		return rail;
	}

	@NonNull
	private static LinearLayout createCockpitRail(@NonNull MapActivity activity) {
		LinearLayout rail = new LinearLayout(activity);
		rail.setOrientation(LinearLayout.VERTICAL);
		rail.setGravity(Gravity.CENTER);
		rail.setPadding(dp(activity, 7), dp(activity, 8), dp(activity, 7), dp(activity, 8));
		rail.setBackground(roundRect(0xe613171a, dp(activity, 6), 0xff3d6f22));
		rail.setVisibility(View.GONE);
		return rail;
	}

	@NonNull
	private static TextView cockpitText(@NonNull MapActivity activity, int sizeSp, int style, int color) {
		TextView text = new TextView(activity);
		text.setTextColor(color);
		text.setTextSize(sizeSp);
		text.setTypeface(Typeface.DEFAULT, style);
		text.setGravity(Gravity.CENTER);
		text.setMaxLines(2);
		return text;
	}

	@NonNull
	private static LinearLayout.LayoutParams centeredParams(@NonNull MapActivity activity,
			int widthDp, int heightDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				dp(activity, widthDp), dp(activity, heightDp));
		params.gravity = Gravity.CENTER_HORIZONTAL;
		return params;
	}

	@NonNull
	private static LinearLayout.LayoutParams matchWrapParams(@NonNull MapActivity activity,
			int topMarginDp, int bottomMarginDp) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = dp(activity, topMarginDp);
		params.bottomMargin = dp(activity, bottomMarginDp);
		return params;
	}

	@NonNull
	private static LinearLayout createHeader(@NonNull MapActivity activity, boolean landscape) {
		LinearLayout header = new LinearLayout(activity);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
		header.setBackground(roundRect(BACKGROUND, dp(activity, 6), 0xff3d6f22));

		header.addView(iconButton(activity, R.drawable.ic_navigation_drawer,
				activity.getString(R.string.backToMenu), v -> activity.toggleDrawer(), landscape ? 8 : 11),
				new LinearLayout.LayoutParams(dp(activity, landscape ? 36 : 46),
						dp(activity, landscape ? 36 : 46)));

		TextView brand = new TextView(activity);
		SpannableString brandText = new SpannableString("RoadCrew");
		brandText.setSpan(new ForegroundColorSpan(PRIMARY), 4, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		brand.setText(brandText);
		brand.setTextColor(TEXT);
		brand.setTextSize(landscape ? 18 : 22);
		brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		brand.setGravity(Gravity.CENTER);
		LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.MATCH_PARENT, 1f);
		header.addView(brand, brandParams);

		TextView liveStatus = new TextView(activity);
		liveStatus.setTag(LIVE_STATUS_TAG);
		liveStatus.setTextSize(10);
		liveStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		liveStatus.setGravity(Gravity.CENTER);
		liveStatus.setOnClickListener(v ->
				RoadCrewDriverProfileDialog.show(activity, activity.getApp()));
		LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
				dp(activity, landscape ? 56 : 64), dp(activity, landscape ? 26 : 30));
		statusParams.rightMargin = dp(activity, 4);
		header.addView(liveStatus, statusParams);

		header.addView(iconButton(activity, R.drawable.ic_action_help,
				activity.getString(R.string.roadcrew_nearby_help_title),
				v -> RoadCrewReportsLayer.showNearbyHelpReports(activity, activity.getApp()), landscape ? 8 : 11),
				new LinearLayout.LayoutParams(dp(activity, landscape ? 36 : 44),
						dp(activity, landscape ? 36 : 44)));
		header.addView(iconButton(activity, R.drawable.ic_overflow_menu_white,
				activity.getString(R.string.shared_string_more), v -> activity.openDrawer(), landscape ? 8 : 11),
				new LinearLayout.LayoutParams(dp(activity, landscape ? 36 : 44),
						dp(activity, landscape ? 36 : 44)));
		return header;
	}

	@NonNull
	private static LinearLayout createFooter(@NonNull MapActivity activity, boolean landscape) {
		LinearLayout footer = new LinearLayout(activity);
		footer.setOrientation(LinearLayout.HORIZONTAL);
		footer.setGravity(Gravity.CENTER);
		footer.setPadding(dp(activity, 4), dp(activity, 3), dp(activity, 4), dp(activity, 3));
		footer.setBackground(roundRect(BACKGROUND, 0, 0xff3d6f22));

		addNavigationItem(footer, activity, landscape, 0, R.drawable.ic_action_map_outlined,
				R.string.roadcrew_neon_nav_map, v -> {
					setNavigationSelection(footer, 0);
					activity.hideContextAndRouteInfoMenues();
				});
		addNavigationItem(footer, activity, landscape, 1, R.drawable.ic_action_map_routes,
				R.string.roadcrew_neon_nav_route, v -> {
					setNavigationSelection(footer, 1);
					activity.getMapActions().enterRoutePlanningModeGivenGpx(null, null, null, true, true);
				});
		addNavigationItem(footer, activity, landscape, 2, R.drawable.ic_roadcrew_report,
				R.string.roadcrew_neon_nav_reports, v -> showReports(activity));
		addNavigationItem(footer, activity, landscape, 3, R.drawable.ic_action_user_account,
				R.string.roadcrew_neon_nav_profile,
				v -> RoadCrewDriverProfileDialog.show(activity, activity.getApp()));
		addNavigationItem(footer, activity, landscape, 4, R.drawable.ic_overflow_menu_white,
				R.string.roadcrew_neon_nav_more, v -> activity.openDrawer());
		updateNavigationSelection(footer, activity);
		return footer;
	}

	private static void addNavigationItem(@NonNull LinearLayout footer, @NonNull MapActivity activity,
			boolean landscape, int index, @DrawableRes int iconRes, int titleRes,
			@NonNull View.OnClickListener listener) {
		LinearLayout item = new LinearLayout(activity);
		item.setOrientation(LinearLayout.VERTICAL);
		item.setGravity(Gravity.CENTER);
		item.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2));
		item.setOnClickListener(listener);

		ImageView icon = new ImageView(activity);
		icon.setImageResource(iconRes);
		icon.setTag(NAV_ICON_TAG_PREFIX + index);
		item.addView(icon, new LinearLayout.LayoutParams(dp(activity, landscape ? 20 : 25),
				dp(activity, landscape ? 20 : 25)));

		TextView title = new TextView(activity);
		title.setText(titleRes);
		title.setTag(NAV_TEXT_TAG_PREFIX + index);
		title.setTextSize(landscape ? 9 : 10);
		title.setGravity(Gravity.CENTER);
		title.setMaxLines(1);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		titleParams.topMargin = dp(activity, landscape ? 1 : 2);
		item.addView(title, titleParams);

		footer.addView(item, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.MATCH_PARENT, 1f));
	}

	private static void updateNavigationSelection(@Nullable View root, @NonNull MapActivity activity) {
		if (root != null) {
			boolean routeActive = activity.getRoutingHelper().isFollowingMode()
					|| activity.getRoutingHelper().isRoutePlanningMode();
			setNavigationSelection(root, routeActive ? 1 : 0);
		}
	}

	private static void setNavigationSelection(@NonNull View root, int activeIndex) {
		for (int index = 0; index < 5; index++) {
			ImageView icon = root.findViewWithTag(NAV_ICON_TAG_PREFIX + index);
			TextView text = root.findViewWithTag(NAV_TEXT_TAG_PREFIX + index);
			boolean active = index == activeIndex;
			if (icon != null) {
				icon.setColorFilter(active ? PRIMARY : TEXT);
			}
			if (text != null) {
				text.setTextColor(active ? PRIMARY : SECONDARY_TEXT);
			}
		}
	}

	private static void updateLiveMapStatus(@Nullable View root, @NonNull MapActivity activity) {
		if (root == null) {
			return;
		}
		TextView statusView = root.findViewWithTag(LIVE_STATUS_TAG);
		if (statusView == null) {
			return;
		}
		RoadCrewMapObservationCoordinator.StatusSnapshot snapshot =
				RoadCrewMapObservationCoordinator.getStatus(activity.getApp());
		int textRes;
		int color;
		switch (snapshot.status) {
			case ACTIVE:
				textRes = R.string.roadcrew_live_truck_map_indicator_active;
				color = PRIMARY;
				break;
			case WAITING_FOR_GPS:
			case PAUSED:
				textRes = R.string.roadcrew_live_truck_map_indicator_waiting;
				color = WAITING;
				break;
			case TRUCK_PROFILE_REQUIRED:
				textRes = R.string.roadcrew_live_truck_map_indicator_truck;
				color = WAITING;
				break;
			case UPLOAD_ERROR:
				textRes = R.string.roadcrew_live_truck_map_indicator_error;
				color = ERROR;
				break;
			case OFF:
			default:
				textRes = R.string.roadcrew_live_truck_map_indicator_off;
				color = SECONDARY_TEXT;
				break;
		}
		statusView.setText(textRes);
		statusView.setTextColor(color);
		statusView.setBackground(roundRect(0x0013171a, dp(activity, 4), color));
		statusView.setContentDescription(activity.getString(textRes));
	}

	private static void updateResponsiveLayout(@Nullable View root, @NonNull MapActivity activity) {
		if (root == null) {
			return;
		}
		boolean landscape = isLandscape(activity);
		boolean cockpitMode = landscape && activity.getRoutingHelper().isFollowingMode();
		boolean layoutChanged = true;
		if (root instanceof NeonHudRoot) {
			layoutChanged = ((NeonHudRoot) root).setCockpitMode(cockpitMode);
		}
		if (layoutChanged) {
			View header = root.findViewWithTag(HEADER_TAG);
			View footer = root.findViewWithTag(FOOTER_TAG);
			View leftRail = root.findViewWithTag(LEFT_RAIL_TAG);
			View rightRail = root.findViewWithTag(RIGHT_RAIL_TAG);
			if (header != null) {
				header.setVisibility(cockpitMode ? View.GONE : View.VISIBLE);
			}
			if (footer != null) {
				footer.setVisibility(cockpitMode ? View.GONE : View.VISIBLE);
			}
			if (leftRail != null) {
				leftRail.setVisibility(cockpitMode ? View.VISIBLE : View.GONE);
			}
			if (rightRail != null) {
				rightRail.setVisibility(cockpitMode ? View.VISIBLE : View.GONE);
			}
			MapHudLayout mapHud = activity.findViewById(R.id.map_hud_layout);
			if (mapHud != null) {
				setNativeHudOffsets(mapHud, !cockpitMode, landscape, cockpitMode);
			}
		}
		if (cockpitMode) {
			updateCockpitData(root, activity);
		}
	}

	private static void updateCockpitData(@NonNull View root, @NonNull MapActivity activity) {
		RoutingHelper routingHelper = activity.getRoutingHelper();
		NextDirectionInfo direction = routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), true);
		ImageView turnIcon = root.findViewWithTag(TURN_ICON_TAG);
		TextView turnDistance = root.findViewWithTag(TURN_DISTANCE_TAG);
		if (direction != null && direction.directionInfo != null) {
			if (turnIcon != null) {
				TurnDrawable drawable = turnIcon.getDrawable() instanceof TurnDrawable
						? (TurnDrawable) turnIcon.getDrawable()
						: createTurnDrawable(activity);
				drawable.setTurnType(direction.directionInfo.getTurnType());
				drawable.setTurnImminent(direction.imminent, routingHelper.isDeviatedFromRoute());
				turnIcon.setImageDrawable(drawable);
				turnIcon.setVisibility(View.VISIBLE);
			}
			if (turnDistance != null) {
				turnDistance.setText(OsmAndFormatter.getFormattedDistance(direction.distanceTo,
						activity.getApp(), OsmAndFormatterParams.USE_LOWER_BOUNDS));
			}
		} else {
			if (turnIcon != null) {
				turnIcon.setVisibility(View.INVISIBLE);
			}
			if (turnDistance != null) {
				turnDistance.setText("-");
			}
		}

		Location location = activity.getApp().getLocationProvider().getLastKnownLocation();
		FormattedValue speed = OsmAndFormatter.getFormattedSpeedValue(
				location != null && location.hasSpeed() ? location.getSpeed() : 0, activity.getApp());
		setText(root, CURRENT_SPEED_TAG, speed.value);
		setText(root, CURRENT_SPEED_UNIT_TAG, speed.unit);

		float speedLimitMetersPerSecond = routingHelper.getCurrentMaxSpeed();
		String speedLimit = "-";
		if (speedLimitMetersPerSecond > 0 && !Float.isInfinite(speedLimitMetersPerSecond)
				&& !Float.isNaN(speedLimitMetersPerSecond)) {
			speedLimit = OsmAndFormatter.getFormattedSpeedValue(
					speedLimitMetersPerSecond, activity.getApp()).value;
		}
		setText(root, SPEED_LIMIT_TAG, speedLimit);
		setText(root, DISTANCE_LEFT_TAG, OsmAndFormatter.getFormattedDistance(
				routingHelper.getLeftDistance(), activity.getApp(), OsmAndFormatterParams.USE_LOWER_BOUNDS));
		setText(root, TIME_LEFT_TAG, OsmAndFormatter.getFormattedDuration(
				routingHelper.getLeftTime(), activity.getApp()));
		updateCockpitLiveDot(root, activity);
	}

	@NonNull
	private static TurnDrawable createTurnDrawable(@NonNull MapActivity activity) {
		TurnDrawable drawable = new TurnDrawable(activity, false);
		drawable.setBounds(0, 0, dp(activity, 48), dp(activity, 48));
		drawable.updateColors(true);
		return drawable;
	}

	private static void updateCockpitLiveDot(@NonNull View root, @NonNull MapActivity activity) {
		View liveDot = root.findViewWithTag(LIVE_DOT_TAG);
		if (liveDot == null) {
			return;
		}
		RoadCrewMapObservationCoordinator.StatusSnapshot snapshot =
				RoadCrewMapObservationCoordinator.getStatus(activity.getApp());
		int color;
		int description;
		switch (snapshot.status) {
			case ACTIVE:
				color = PRIMARY;
				description = R.string.roadcrew_live_truck_map_indicator_active;
				break;
			case WAITING_FOR_GPS:
			case PAUSED:
			case TRUCK_PROFILE_REQUIRED:
				color = WAITING;
				description = R.string.roadcrew_live_truck_map_indicator_waiting;
				break;
			case UPLOAD_ERROR:
				color = ERROR;
				description = R.string.roadcrew_live_truck_map_indicator_error;
				break;
			case OFF:
			default:
				color = SECONDARY_TEXT;
				description = R.string.roadcrew_live_truck_map_indicator_off;
				break;
		}
		liveDot.setBackground(circle(color, Color.WHITE, dp(activity, 1)));
		liveDot.setContentDescription(activity.getString(description));
	}

	private static void setText(@NonNull View root, @NonNull String tag, @NonNull String value) {
		TextView text = root.findViewWithTag(tag);
		if (text != null) {
			text.setText(value);
		}
	}

	private static void showReports(@NonNull MapActivity activity) {
		View reportButton = activity.findViewById(R.id.roadcrew_report_button);
		if (reportButton instanceof RoadCrewReportButton) {
			((RoadCrewReportButton) reportButton).showReportTypeDialog();
		}
	}

	private static void setNativeHudOffsets(@NonNull MapHudLayout mapHud, boolean enabled,
			boolean landscape, boolean footerHidden) {
		float topOffset = enabled ? dp(mapHud, landscape ? 52 : 68) : 0;
		float bottomOffset = enabled && !footerHidden ? -dp(mapHud, landscape ? 50 : 68) : 0;
		setTranslationY(mapHud.findViewById(R.id.top_widgets_panel), topOffset);
		setTranslationY(mapHud.findViewById(R.id.map_left_widgets_panel), topOffset);
		setTranslationY(mapHud.findViewById(R.id.map_right_widgets_panel), topOffset);
		setTranslationY(mapHud.findViewById(R.id.MapHudButtonsOverlayBottom), bottomOffset);
		setTranslationY(mapHud.findViewById(R.id.map_bottom_widgets_panel), bottomOffset);
	}

	private static void setTranslationY(@Nullable View view, float translation) {
		if (view != null) {
			view.setTranslationY(translation);
		}
	}

	@NonNull
	private static ImageButton iconButton(@NonNull MapActivity activity, @DrawableRes int iconRes,
			@NonNull String description, @NonNull View.OnClickListener listener, int paddingDp) {
		ImageButton button = new ImageButton(activity);
		button.setImageResource(iconRes);
		button.setColorFilter(TEXT);
		button.setContentDescription(description);
		button.setBackgroundColor(Color.TRANSPARENT);
		button.setPadding(dp(activity, paddingDp), dp(activity, paddingDp),
				dp(activity, paddingDp), dp(activity, paddingDp));
		button.setOnClickListener(listener);
		return button;
	}

	@NonNull
	private static GradientDrawable roundRect(int color, int radius, int strokeColor) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(color);
		drawable.setCornerRadius(radius);
		if (strokeColor != 0) {
			drawable.setStroke(dpValue(1), strokeColor);
		}
		return drawable;
	}

	@NonNull
	private static GradientDrawable circle(int color, int strokeColor, int strokeWidth) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setShape(GradientDrawable.OVAL);
		drawable.setColor(color);
		if (strokeColor != 0 && strokeWidth > 0) {
			drawable.setStroke(strokeWidth, strokeColor);
		}
		return drawable;
	}

	private static int dp(@NonNull View view, float value) {
		return (int) (value * view.getResources().getDisplayMetrics().density);
	}

	private static int dp(@NonNull MapActivity activity, float value) {
		return (int) (value * activity.getResources().getDisplayMetrics().density);
	}

	private static int dpValue(float value) {
		return Math.max(1, (int) value);
	}

	private static boolean isLandscape(@NonNull MapActivity activity) {
		return activity.getResources().getConfiguration().orientation
				== Configuration.ORIENTATION_LANDSCAPE;
	}

	private static final class NeonHudRoot extends FrameLayout {
		private static final long THEME_CHECK_INTERVAL_MS = 60_000L;
		private static final long STATUS_CHECK_INTERVAL_MS = 1_000L;
		private static final int[] COCKPIT_HIDDEN_VIEW_IDS = {
				R.id.top_widgets_panel,
				R.id.map_left_widgets_panel,
				R.id.map_right_widgets_panel,
				R.id.map_bottom_widgets_panel,
				R.id.speedometer_widget,
				R.id.map_zoom_in_button,
				R.id.map_zoom_out_button,
				R.id.roadcrew_report_button,
				R.id.map_layers_button,
				R.id.map_search_button,
				R.id.map_compass_button,
				R.id.map_my_location_button,
				R.id.map_menu_button,
				R.id.map_route_info_button,
				R.id.map_3d_button
		};

		private final MapActivity activity;
		private final boolean nightMode;
		private final boolean landscape;
		private final Map<View, NativeViewState> savedNativeViewState = new LinkedHashMap<>();
		private boolean cockpitMode;
		private boolean cockpitModeInitialized;
		private final Runnable themeCheck = new Runnable() {
			@Override
			public void run() {
				if (!isAttachedToWindow()) {
					return;
				}
				if (nightMode != RoadCrewVisualStyle.isNeonNight(activity)) {
					RoadCrewNeonHud.apply(activity);
				} else {
					postDelayed(this, THEME_CHECK_INTERVAL_MS);
				}
			}
		};
		private final Runnable statusCheck = new Runnable() {
			@Override
			public void run() {
				if (!isAttachedToWindow()) {
					return;
				}
				updateResponsiveLayout(NeonHudRoot.this, activity);
				updateLiveMapStatus(NeonHudRoot.this, activity);
				postDelayed(this, STATUS_CHECK_INTERVAL_MS);
			}
		};

		private NeonHudRoot(@NonNull MapActivity activity, boolean nightMode, boolean landscape) {
			super(activity);
			this.activity = activity;
			this.nightMode = nightMode;
			this.landscape = landscape;
		}

		private boolean setCockpitMode(boolean enabled) {
			if (cockpitModeInitialized && cockpitMode == enabled) {
				return false;
			}
			cockpitModeInitialized = true;
			if (enabled) {
				savedNativeViewState.clear();
				cockpitMode = true;
				for (int id : COCKPIT_HIDDEN_VIEW_IDS) {
					View view = activity.findViewById(id);
					if (view != null && !savedNativeViewState.containsKey(view)) {
						savedNativeViewState.put(view, new NativeViewState(view));
						view.setVisibility(View.INVISIBLE);
						view.setAlpha(0f);
						view.setTranslationX(-Math.max(getResources().getDisplayMetrics().widthPixels * 2f,
								dp(this, 2000)));
						view.setClickable(false);
						view.setEnabled(false);
					}
				}
			} else {
				restoreNativeVisibility();
			}
			return true;
		}

		private void restoreNativeVisibility() {
			if (!cockpitMode) {
				return;
			}
			for (Map.Entry<View, NativeViewState> entry : savedNativeViewState.entrySet()) {
				entry.getValue().restore(entry.getKey());
			}
			savedNativeViewState.clear();
			cockpitMode = false;
		}

		private static final class NativeViewState {
			private final int visibility;
			private final float alpha;
			private final float translationX;
			private final boolean clickable;
			private final boolean enabled;

			private NativeViewState(@NonNull View view) {
				visibility = view.getVisibility();
				alpha = view.getAlpha();
				translationX = view.getTranslationX();
				clickable = view.isClickable();
				enabled = view.isEnabled();
			}

			private void restore(@NonNull View view) {
				view.setAlpha(alpha);
				view.setTranslationX(translationX);
				view.setClickable(clickable);
				view.setEnabled(enabled);
				view.setVisibility(visibility);
			}
		}

		@Override
		protected void onAttachedToWindow() {
			super.onAttachedToWindow();
			postDelayed(themeCheck, THEME_CHECK_INTERVAL_MS);
			post(statusCheck);
		}

		@Override
		protected void onDetachedFromWindow() {
			removeCallbacks(themeCheck);
			removeCallbacks(statusCheck);
			restoreNativeVisibility();
			super.onDetachedFromWindow();
		}
	}
}
