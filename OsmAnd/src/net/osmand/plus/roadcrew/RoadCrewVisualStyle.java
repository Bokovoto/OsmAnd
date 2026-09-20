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
	/**
	 * What a phone gets when nobody has chosen.
	 *
	 * Neon is what RoadCrew looks like, and a driver who installs it should see
	 * it without hunting for a setting. Anyone who has explicitly picked classic
	 * has a stored value and keeps it - changing a default must not overrule a
	 * choice somebody made on purpose.
	 */
	private static final String DEFAULT_STYLE = STYLE_NEON_BETA;
	/**
	 * How the map itself is coloured, kept apart from which panels are on
	 * screen. Galin, 20.09: older drivers cannot make out anything on the dark
	 * map and need the ordinary light OpenStreetMap one - and that has nothing
	 * to do with whether they want RoadCrew's panels or OsmAnd's. Tying the two
	 * together was the mistake: a driver may want the neon panels with a light
	 * map, or the plain panels with a dark one.
	 */
	private static final String KEY_MAP_COLOURS = "map_colours";
	private static final String MAP_COLOURS_DARK = "DARK";
	private static final String MAP_COLOURS_LIGHT = "LIGHT";
	/** Dark, because that is what the app has always looked like; the button changes it. */
	private static final String DEFAULT_MAP_COLOURS = MAP_COLOURS_DARK;
	private static final String KEY_PREVIOUS_DAY_NIGHT_PREFIX = "previous_day_night_";
	private static final String KEY_PREVIOUS_ROUTE_COLOR_DAY_PREFIX = "previous_route_color_day_";
	private static final String KEY_PREVIOUS_ROUTE_COLOR_NIGHT_PREFIX = "previous_route_color_night_";
	private static final String KEY_PREVIOUS_ROUTE_COLORING_PREFIX = "previous_route_coloring_";
	private static final int NEON_DAY_ROUTE_COLOR = 0xffa1ff3d;
	private static final int NEON_DAY_ROUTE_OUTLINE_COLOR = 0x6676ff03;
	private static final int NEON_DAY_ROUTE_ARROW_COLOR = 0xff17351a;
	private static final int NEON_DAY_CONTROL_COLOR = 0xff146b3a;

	private RoadCrewVisualStyle() {
	}

	public static boolean isNeonBeta(@NonNull Context context) {
		return STYLE_NEON_BETA.equals(preferences(context).getString(KEY_STYLE, DEFAULT_STYLE));
	}

	public static void setNeonBeta(@NonNull Context context, boolean enabled) {
		preferences(context).edit()
				.putString(KEY_STYLE, enabled ? STYLE_NEON_BETA : STYLE_CLASSIC)
				.apply();
	}

	/** Whether the map is drawn dark. Independent of which panels are on screen. */
	public static boolean isDarkMap(@NonNull Context context) {
		return MAP_COLOURS_DARK.equals(
				preferences(context).getString(KEY_MAP_COLOURS, DEFAULT_MAP_COLOURS));
	}

	public static void setDarkMap(@NonNull Context context, boolean dark) {
		preferences(context).edit()
				.putString(KEY_MAP_COLOURS, dark ? MAP_COLOURS_DARK : MAP_COLOURS_LIGHT)
				.apply();
	}

	/**
	 * Brings the map's colours in line with the driver's choice. Note what it
	 * asks: the map colour setting, not which panels are showing.
	 */
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
		if (isDarkMap(app)) {
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

	/**
	 * The bright route colour is there to stand out on the dark map in daylight,
	 * so it follows the map's colours and not the panels: on the light map the
	 * ordinary OsmAnd route colour is the readable one.
	 */
	public static boolean isNeonDay(@NonNull Context context) {
		return isDarkMap(context) && !isNeonNight(context);
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
			// Classic is not a second dark look: it is the plain OpenStreetMap
			// map, light by day. That is the whole reason it exists - Galin,
			// 20.09: older drivers cannot make out anything on the dark map, and
			// need the ordinary one, the way a phone map normally looks.
			//
			// So the day/night setting is put to AUTO outright, not back to
			// whatever was noted down before. Restoring the note was the earlier
			// attempt and it failed exactly where it mattered: if the note itself
			// said NIGHT, classic came back dark and the switch looked broken.
			// AUTO still gives a dark map after sunset, when dark is the kind
			// thing to do.
			editor.remove(previousDayNightKey(mode));
			if (settings.DAYNIGHT_MODE.getModeValue(mode) != DayNightMode.AUTO) {
				settings.DAYNIGHT_MODE.setModeValue(mode, DayNightMode.AUTO);
				changed = true;
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
			// Whatever the stored notes said, no mode may keep neon's own route
			// colour once neon is off: that green is the thing people recognise
			// as "still neon" even after the panels are gone.
			if (settings.CUSTOM_ROUTE_COLOR_DAY.getModeValue(mode) == NEON_DAY_ROUTE_COLOR) {
				settings.CUSTOM_ROUTE_COLOR_DAY.resetModeToDefault(mode);
				changed = true;
			}
			if (settings.CUSTOM_ROUTE_COLOR_NIGHT.getModeValue(mode) == NEON_DAY_ROUTE_COLOR) {
				settings.CUSTOM_ROUTE_COLOR_NIGHT.resetModeToDefault(mode);
				changed = true;
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
