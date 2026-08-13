package net.osmand.plus.roadcrew;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.CommonPreference;

final class RoadCrewSettings {

	private static final String SHOW_TRUCK_RESTRICTIONS = "roadcrew_show_truck_restrictions";

	private RoadCrewSettings() {
	}

	@NonNull
	static CommonPreference<Boolean> showTruckRestrictions(@NonNull OsmandApplication app) {
		CommonPreference<Boolean> preference = app.getSettings()
				.registerBooleanPreference(SHOW_TRUCK_RESTRICTIONS, false)
				.makeProfile()
				.cache();
		preference.setModeDefaultValue(ApplicationMode.CAR, false);
		preference.setModeDefaultValue(ApplicationMode.TRUCK, true);
		return preference;
	}

	static boolean shouldShowTruckRestrictions(@NonNull OsmandApplication app) {
		return showTruckRestrictions(app).get();
	}
}
