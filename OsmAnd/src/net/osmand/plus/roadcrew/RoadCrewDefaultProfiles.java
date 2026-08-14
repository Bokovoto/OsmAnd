package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;

public final class RoadCrewDefaultProfiles {

	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final String PREFS_NAME = "roadcrew_default_profiles";
	private static final String KEY_APPLIED = "applied";
	private static final String KEY_APPLIED_VERSION = "applied_version";
	private static final int CURRENT_VERSION = 2;
	private static final String ROADCREW_DEFAULT_MODES = "car,truck,";

	private RoadCrewDefaultProfiles() {
	}

	public static void apply(@NonNull OsmandApplication app) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		SharedPreferences preferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		if (preferences.getInt(KEY_APPLIED_VERSION, 0) >= CURRENT_VERSION) {
			return;
		}
		OsmandSettings settings = app.getSettings();
		settings.AVAILABLE_APP_MODES.set(ROADCREW_DEFAULT_MODES);
		settings.DEFAULT_APPLICATION_MODE.set(ApplicationMode.TRUCK);
		settings.LAST_ROUTE_APPLICATION_MODE.set(ApplicationMode.TRUCK);
		if (!ApplicationMode.getModesForRouting(app).contains(settings.APPLICATION_MODE.get())) {
			settings.setApplicationMode(ApplicationMode.TRUCK);
		}
		preferences.edit()
				.putBoolean(KEY_APPLIED, true)
				.putInt(KEY_APPLIED_VERSION, CURRENT_VERSION)
				.apply();
	}
}
