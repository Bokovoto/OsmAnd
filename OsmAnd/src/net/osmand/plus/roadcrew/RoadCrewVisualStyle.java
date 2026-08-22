package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.enums.DayNightMode;

public final class RoadCrewVisualStyle {

	private static final String PREFS_NAME = "roadcrew_visual_style";
	private static final String KEY_STYLE = "selected_style";
	private static final String STYLE_CLASSIC = "CLASSIC";
	private static final String STYLE_NEON_BETA = "NEON_BETA";
	private static final String KEY_PREVIOUS_DAY_NIGHT_PREFIX = "previous_day_night_";

	private RoadCrewVisualStyle() {
	}

	public static boolean isNeonBeta(@NonNull Context context) {
		return STYLE_NEON_BETA.equals(preferences(context).getString(KEY_STYLE, STYLE_CLASSIC));
	}

	public static void setNeonBeta(@NonNull Context context, boolean enabled) {
		preferences(context).edit()
				.putString(KEY_STYLE, enabled ? STYLE_NEON_BETA : STYLE_CLASSIC)
				.apply();
		syncMapTheme(context);
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
		// Test 41 forced NIGHT while Neon was enabled. Restore each profile once,
		// then let OsmAnd's normal day/night setting control both map variants.
		boolean changed = false;
		SharedPreferences.Editor editor = preferences.edit();
		for (ApplicationMode mode : ApplicationMode.allPossibleValues()) {
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
	private static String previousDayNightKey(@NonNull ApplicationMode mode) {
		return KEY_PREVIOUS_DAY_NIGHT_PREFIX + mode.getStringKey();
	}

	private static SharedPreferences preferences(@NonNull Context context) {
		Context appContext = context instanceof OsmandApplication
				? context
				: context.getApplicationContext();
		return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}
}
