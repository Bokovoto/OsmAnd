package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.enums.DayNightMode;
import net.osmand.shared.routing.ColoringType;
import net.osmand.util.SunriseSunset;

import java.util.Calendar;

public final class RoadCrewVisualStyle {

	private static final String PREFS_NAME = "roadcrew_visual_style";
	private static final String KEY_STYLE = "selected_style";
	private static final String STYLE_CLASSIC = "CLASSIC";
	private static final String STYLE_NEON_BETA = "NEON_BETA";
	private static final String KEY_PREVIOUS_DAY_NIGHT_PREFIX = "previous_day_night_";
	private static final String KEY_PREVIOUS_ROUTE_COLOR_DAY_PREFIX = "previous_route_color_day_";
	private static final String KEY_PREVIOUS_ROUTE_COLOR_NIGHT_PREFIX = "previous_route_color_night_";
	private static final String KEY_PREVIOUS_ROUTE_COLORING_PREFIX = "previous_route_coloring_";
	private static final int NEON_DAY_ROUTE_COLOR = 0xffa1ff3d;
	private static final int NEON_DAY_ROUTE_OUTLINE_COLOR = 0xe612351b;
	private static final int NEON_DAY_ROUTE_ARROW_COLOR = 0xff17351a;
	private static final int NEON_DAY_CONTROL_COLOR = 0xff146b3a;

	private RoadCrewVisualStyle() {
	}

	public static boolean isNeonBeta(@NonNull Context context) {
		return STYLE_NEON_BETA.equals(preferences(context).getString(KEY_STYLE, STYLE_CLASSIC));
	}

	public static void setNeonBeta(@NonNull Context context, boolean enabled) {
		preferences(context).edit()
				.putString(KEY_STYLE, enabled ? STYLE_NEON_BETA : STYLE_CLASSIC)
				.apply();
	}

	public static boolean syncMapTheme(@NonNull Context context) {
		Context appContext = context instanceof OsmandApplication
				? context
				: context.getApplicationContext();
		if (!(appContext instanceof OsmandApplication)) {
			return false;
		}
		OsmandApplication app = (OsmandApplication) appContext;
		OsmandSettings settings = app.getSettings();
		SharedPreferences preferences = preferences(app);
		if (isNeonBeta(app)) {
			return applyNeonTheme(app, settings, preferences);
		}
		return restoreClassicTheme(settings, preferences);
	}

	public static boolean isNeonNight(@NonNull Context context) {
		Context appContext = context instanceof OsmandApplication
				? context
				: context.getApplicationContext();
		if (appContext instanceof OsmandApplication) {
			try {
				SunriseSunset sunriseSunset = ((OsmandApplication) appContext)
						.getDaynightHelper().getSunriseSunset();
				if (sunriseSunset != null) {
					return !sunriseSunset.isDaytime();
				}
			} catch (RuntimeException ignored) {
				// Fall back to local time when location is unavailable.
			}
		}
		int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
		return hour < 7 || hour >= 19;
	}

	public static boolean isNeonDay(@NonNull Context context) {
		return isNeonBeta(context) && !isNeonNight(context);
	}

	public static int getNeonDayRouteColor() {
		return NEON_DAY_ROUTE_COLOR;
	}

	public static int getNeonDayRouteOutlineColor() {
		return NEON_DAY_ROUTE_OUTLINE_COLOR;
	}

	public static int getNeonDayRouteArrowColor() {
		return NEON_DAY_ROUTE_ARROW_COLOR;
	}

	public static int getNeonDayControlColor() {
		return NEON_DAY_CONTROL_COLOR;
	}

	private static boolean applyNeonTheme(@NonNull OsmandApplication app,
			@NonNull OsmandSettings settings, @NonNull SharedPreferences preferences) {
		ApplicationMode mode = settings.getApplicationMode();
		String modeKey = mode.getStringKey();
		SharedPreferences.Editor editor = preferences.edit();
		if (!preferences.contains(previousDayNightKey(mode))) {
			editor.putString(previousDayNightKey(mode),
					settings.DAYNIGHT_MODE.getModeValue(mode).name());
		}
		if (!preferences.contains(previousRouteColorDayKey(modeKey))) {
			editor.putInt(previousRouteColorDayKey(modeKey),
					settings.CUSTOM_ROUTE_COLOR_DAY.getModeValue(mode));
		}
		if (!preferences.contains(previousRouteColorNightKey(modeKey))) {
			editor.putInt(previousRouteColorNightKey(modeKey),
					settings.CUSTOM_ROUTE_COLOR_NIGHT.getModeValue(mode));
		}
		if (!preferences.contains(previousRouteColoringKey(modeKey))) {
			editor.putString(previousRouteColoringKey(modeKey),
					settings.ROUTE_COLORING_TYPE.getModeValue(mode).name());
		}
		editor.apply();

		boolean changed = false;
		if (settings.DAYNIGHT_MODE.getModeValue(mode) != DayNightMode.NIGHT) {
			settings.DAYNIGHT_MODE.setModeValue(mode, DayNightMode.NIGHT);
			changed = true;
		}

		boolean actualNight = isNeonNight(app);
		ColoringType desiredColoring = actualNight
				? ColoringType.DEFAULT
				: ColoringType.CUSTOM_COLOR;
		if (settings.ROUTE_COLORING_TYPE.getModeValue(mode) != desiredColoring) {
			settings.ROUTE_COLORING_TYPE.setModeValue(mode, desiredColoring);
			changed = true;
		}
		if (!actualNight) {
			if (settings.CUSTOM_ROUTE_COLOR_DAY.getModeValue(mode) != NEON_DAY_ROUTE_COLOR) {
				settings.CUSTOM_ROUTE_COLOR_DAY.setModeValue(mode, NEON_DAY_ROUTE_COLOR);
				changed = true;
			}
			if (settings.CUSTOM_ROUTE_COLOR_NIGHT.getModeValue(mode) != NEON_DAY_ROUTE_COLOR) {
				settings.CUSTOM_ROUTE_COLOR_NIGHT.setModeValue(mode, NEON_DAY_ROUTE_COLOR);
				changed = true;
			}
		}
		return changed;
	}

	private static boolean restoreClassicTheme(@NonNull OsmandSettings settings,
			@NonNull SharedPreferences preferences) {
		boolean changed = false;
		SharedPreferences.Editor editor = preferences.edit();
		for (ApplicationMode mode : ApplicationMode.allPossibleValues()) {
			String modeKey = mode.getStringKey();
			String previousKey = previousDayNightKey(mode);
			String storedMode = preferences.getString(previousKey, null);
			if (storedMode != null) {
				DayNightMode previousMode = parseDayNightMode(storedMode);
				if (settings.DAYNIGHT_MODE.getModeValue(mode) != previousMode) {
					settings.DAYNIGHT_MODE.setModeValue(mode, previousMode);
					changed = true;
				}
				editor.remove(previousKey);
			}
			String colorDayKey = previousRouteColorDayKey(modeKey);
			if (preferences.contains(colorDayKey)) {
				int color = preferences.getInt(colorDayKey,
						settings.CUSTOM_ROUTE_COLOR_DAY.getModeValue(mode));
				if (settings.CUSTOM_ROUTE_COLOR_DAY.getModeValue(mode) != color) {
					settings.CUSTOM_ROUTE_COLOR_DAY.setModeValue(mode, color);
					changed = true;
				}
				editor.remove(colorDayKey);
			}
			String colorNightKey = previousRouteColorNightKey(modeKey);
			if (preferences.contains(colorNightKey)) {
				int color = preferences.getInt(colorNightKey,
						settings.CUSTOM_ROUTE_COLOR_NIGHT.getModeValue(mode));
				if (settings.CUSTOM_ROUTE_COLOR_NIGHT.getModeValue(mode) != color) {
					settings.CUSTOM_ROUTE_COLOR_NIGHT.setModeValue(mode, color);
					changed = true;
				}
				editor.remove(colorNightKey);
			}
			String coloringKey = previousRouteColoringKey(modeKey);
			String storedColoring = preferences.getString(coloringKey, null);
			if (storedColoring != null) {
				ColoringType coloringType = parseColoringType(storedColoring);
				if (settings.ROUTE_COLORING_TYPE.getModeValue(mode) != coloringType) {
					settings.ROUTE_COLORING_TYPE.setModeValue(mode, coloringType);
					changed = true;
				}
				editor.remove(coloringKey);
			}
		}
		editor.apply();
		return changed;
	}

	@NonNull
	private static DayNightMode parseDayNightMode(@NonNull String value) {
		try {
			return DayNightMode.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return DayNightMode.AUTO;
		}
	}

	@NonNull
	private static ColoringType parseColoringType(@NonNull String value) {
		try {
			return ColoringType.valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return ColoringType.DEFAULT;
		}
	}

	@NonNull
	private static String previousDayNightKey(@NonNull ApplicationMode mode) {
		return KEY_PREVIOUS_DAY_NIGHT_PREFIX + mode.getStringKey();
	}

	@NonNull
	private static String previousRouteColorDayKey(@NonNull String modeKey) {
		return KEY_PREVIOUS_ROUTE_COLOR_DAY_PREFIX + modeKey;
	}

	@NonNull
	private static String previousRouteColorNightKey(@NonNull String modeKey) {
		return KEY_PREVIOUS_ROUTE_COLOR_NIGHT_PREFIX + modeKey;
	}

	@NonNull
	private static String previousRouteColoringKey(@NonNull String modeKey) {
		return KEY_PREVIOUS_ROUTE_COLORING_PREFIX + modeKey;
	}

	private static SharedPreferences preferences(@NonNull Context context) {
		Context appContext = context instanceof OsmandApplication
				? context
				: context.getApplicationContext();
		return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}
}
