package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;

public final class RoadCrewVisualStyle {

	private static final String PREFS_NAME = "roadcrew_visual_style";
	private static final String KEY_STYLE = "selected_style";
	private static final String STYLE_CLASSIC = "CLASSIC";
	private static final String STYLE_NEON_BETA = "NEON_BETA";

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

	private static SharedPreferences preferences(@NonNull Context context) {
		Context appContext = context instanceof OsmandApplication
				? context
				: context.getApplicationContext();
		return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}
}
