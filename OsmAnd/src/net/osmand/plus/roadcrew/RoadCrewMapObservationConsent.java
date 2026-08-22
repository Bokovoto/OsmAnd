package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;

final class RoadCrewMapObservationConsent {

	static final int CURRENT_CONSENT_VERSION = 2;
	private static final String PREFS_NAME = "roadcrew_live_truck_map";
	private static final String KEY_ENABLED = "observation_enabled";
	private static final String KEY_CONSENT_VERSION = "consent_version";
	private static final String OUTBOX_FILE_NAME = "roadcrew-map-observations.json";

	private RoadCrewMapObservationConsent() {
	}

	static boolean isEnabled(@NonNull Context context) {
		SharedPreferences preferences = preferences(context);
		return preferences.getBoolean(KEY_ENABLED, false)
				&& preferences.getInt(KEY_CONSENT_VERSION, 0) == CURRENT_CONSENT_VERSION;
	}

	static void setEnabled(@NonNull Context context, boolean enabled) {
		preferences(context).edit()
				.putBoolean(KEY_ENABLED, enabled)
				.putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)
				.apply();
	}

	@NonNull
	static File getOutboxFile(@NonNull Context context) {
		return new File(context.getFilesDir(), OUTBOX_FILE_NAME);
	}

	static void deleteLocalObservations(@NonNull Context context) {
		File outbox = getOutboxFile(context);
		deleteIfPresent(outbox);
		deleteIfPresent(new File(outbox.getPath() + ".bak"));
		deleteIfPresent(new File(outbox.getPath() + ".tmp"));
	}

	private static void deleteIfPresent(@NonNull File file) {
		if (file.exists() && !file.delete()) {
			file.deleteOnExit();
		}
	}

	@NonNull
	private static SharedPreferences preferences(@NonNull Context context) {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}
}
