package net.osmand.plus.roadcrew;

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

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.views.controls.MapHudLayout;

public final class RoadCrewNeonHud {

	private static final String HUD_TAG = "roadcrew_neon_beta_hud";
	private static final String NAV_ICON_TAG_PREFIX = "roadcrew_neon_nav_icon_";
	private static final String NAV_TEXT_TAG_PREFIX = "roadcrew_neon_nav_text_";
	private static final String LIVE_STATUS_TAG = "roadcrew_live_truck_map_status";
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
			setNativeHudOffsets(mapHud, false);
			if (mapThemeChanged) {
				activity.updateMapSettings(true);
			}
			return;
		}
		boolean nightMode = RoadCrewVisualStyle.isNeonNight(activity);
		if (existing != null && (!(existing instanceof NeonHudRoot)
				|| ((NeonHudRoot) existing).nightMode != nightMode)) {
			mapHud.removeView(existing);
			existing = null;
		}
		if (existing == null) {
			mapHud.addView(createHud(activity, nightMode), new FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		}
		setNativeHudOffsets(mapHud, true);
		updateNavigationSelection(mapHud.findViewWithTag(HUD_TAG), activity);
		updateLiveMapStatus(mapHud.findViewWithTag(HUD_TAG), activity);
		if (mapThemeChanged) {
			activity.updateMapSettings(true);
		}
	}

	@NonNull
	private static View createHud(@NonNull MapActivity activity, boolean nightMode) {
		FrameLayout root = new NeonHudRoot(activity, nightMode);
		root.setTag(HUD_TAG);
		root.setClickable(false);
		root.setFocusable(false);

		LinearLayout header = createHeader(activity);
		FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 58), Gravity.TOP);
		headerParams.setMargins(dp(activity, 10), dp(activity, 6), dp(activity, 10), 0);
		root.addView(header, headerParams);

		LinearLayout footer = createFooter(activity);
		FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 68), Gravity.BOTTOM);
		root.addView(footer, footerParams);
		return root;
	}

	@NonNull
	private static LinearLayout createHeader(@NonNull MapActivity activity) {
		LinearLayout header = new LinearLayout(activity);
		header.setGravity(Gravity.CENTER_VERTICAL);
		header.setOrientation(LinearLayout.HORIZONTAL);
		header.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
		header.setBackground(roundRect(BACKGROUND, dp(activity, 6), 0xff3d6f22));

		header.addView(iconButton(activity, R.drawable.ic_navigation_drawer,
				activity.getString(R.string.backToMenu), v -> activity.toggleDrawer()),
				new LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 46)));

		TextView brand = new TextView(activity);
		SpannableString brandText = new SpannableString("RoadCrew");
		brandText.setSpan(new ForegroundColorSpan(PRIMARY), 4, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		brand.setText(brandText);
		brand.setTextColor(TEXT);
		brand.setTextSize(22);
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
				dp(activity, 64), dp(activity, 30));
		statusParams.rightMargin = dp(activity, 4);
		header.addView(liveStatus, statusParams);

		header.addView(iconButton(activity, R.drawable.ic_action_help,
				activity.getString(R.string.roadcrew_nearby_help_title),
				v -> RoadCrewReportsLayer.showNearbyHelpReports(activity, activity.getApp())),
				new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
		header.addView(iconButton(activity, R.drawable.ic_overflow_menu_white,
				activity.getString(R.string.shared_string_more), v -> activity.openDrawer()),
				new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
		return header;
	}

	@NonNull
	private static LinearLayout createFooter(@NonNull MapActivity activity) {
		LinearLayout footer = new LinearLayout(activity);
		footer.setOrientation(LinearLayout.HORIZONTAL);
		footer.setGravity(Gravity.CENTER);
		footer.setPadding(dp(activity, 4), dp(activity, 3), dp(activity, 4), dp(activity, 3));
		footer.setBackground(roundRect(BACKGROUND, 0, 0xff3d6f22));

		addNavigationItem(footer, activity, 0, R.drawable.ic_action_map_outlined,
				R.string.roadcrew_neon_nav_map, v -> {
					setNavigationSelection(footer, 0);
					activity.hideContextAndRouteInfoMenues();
				});
		addNavigationItem(footer, activity, 1, R.drawable.ic_action_map_routes,
				R.string.roadcrew_neon_nav_route, v -> {
					setNavigationSelection(footer, 1);
					activity.getMapActions().enterRoutePlanningModeGivenGpx(null, null, null, true, true);
				});
		addNavigationItem(footer, activity, 2, R.drawable.ic_roadcrew_report,
				R.string.roadcrew_neon_nav_reports, v -> showReports(activity));
		addNavigationItem(footer, activity, 3, R.drawable.ic_action_user_account,
				R.string.roadcrew_neon_nav_profile,
				v -> RoadCrewDriverProfileDialog.show(activity, activity.getApp()));
		addNavigationItem(footer, activity, 4, R.drawable.ic_overflow_menu_white,
				R.string.roadcrew_neon_nav_more, v -> activity.openDrawer());
		updateNavigationSelection(footer, activity);
		return footer;
	}

	private static void addNavigationItem(@NonNull LinearLayout footer, @NonNull MapActivity activity,
			int index, @DrawableRes int iconRes, int titleRes, @NonNull View.OnClickListener listener) {
		LinearLayout item = new LinearLayout(activity);
		item.setOrientation(LinearLayout.VERTICAL);
		item.setGravity(Gravity.CENTER);
		item.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2));
		item.setOnClickListener(listener);

		ImageView icon = new ImageView(activity);
		icon.setImageResource(iconRes);
		icon.setTag(NAV_ICON_TAG_PREFIX + index);
		item.addView(icon, new LinearLayout.LayoutParams(dp(activity, 25), dp(activity, 25)));

		TextView title = new TextView(activity);
		title.setText(titleRes);
		title.setTag(NAV_TEXT_TAG_PREFIX + index);
		title.setTextSize(10);
		title.setGravity(Gravity.CENTER);
		title.setMaxLines(1);
		LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		titleParams.topMargin = dp(activity, 2);
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

	private static void showReports(@NonNull MapActivity activity) {
		View reportButton = activity.findViewById(R.id.roadcrew_report_button);
		if (reportButton instanceof RoadCrewReportButton) {
			((RoadCrewReportButton) reportButton).showReportTypeDialog();
		}
	}

	private static void setNativeHudOffsets(@NonNull MapHudLayout mapHud, boolean enabled) {
		float topOffset = enabled ? dp(mapHud, 68) : 0;
		float bottomOffset = enabled ? -dp(mapHud, 68) : 0;
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
			@NonNull String description, @NonNull View.OnClickListener listener) {
		ImageButton button = new ImageButton(activity);
		button.setImageResource(iconRes);
		button.setColorFilter(TEXT);
		button.setContentDescription(description);
		button.setBackgroundColor(Color.TRANSPARENT);
		button.setPadding(dp(activity, 11), dp(activity, 11), dp(activity, 11), dp(activity, 11));
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

	private static int dp(@NonNull View view, float value) {
		return (int) (value * view.getResources().getDisplayMetrics().density);
	}

	private static int dp(@NonNull MapActivity activity, float value) {
		return (int) (value * activity.getResources().getDisplayMetrics().density);
	}

	private static int dpValue(float value) {
		return Math.max(1, (int) value);
	}

	private static final class NeonHudRoot extends FrameLayout {
		private static final long THEME_CHECK_INTERVAL_MS = 60_000L;
		private static final long STATUS_CHECK_INTERVAL_MS = 2_000L;

		private final MapActivity activity;
		private final boolean nightMode;
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
				updateLiveMapStatus(NeonHudRoot.this, activity);
				postDelayed(this, STATUS_CHECK_INTERVAL_MS);
			}
		};

		private NeonHudRoot(@NonNull MapActivity activity, boolean nightMode) {
			super(activity);
			this.activity = activity;
			this.nightMode = nightMode;
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
			super.onDetachedFromWindow();
		}
	}
}
