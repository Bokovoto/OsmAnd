package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;

public final class RoadCrewMapObservationConsent {

	static final int CURRENT_CONSENT_VERSION = 2;
	private static final String PREFS_NAME = "roadcrew_live_truck_map";
	private static final String KEY_ENABLED = "observation_enabled";
	private static final String KEY_CONSENT_VERSION = "consent_version";
	private static final String KEY_LAST_UPLOAD_AT = "last_upload_at";
	private static final String KEY_LAST_UPLOAD_FAILURE_AT = "last_upload_failure_at";
	private static final String KEY_UPLOADED_OBSERVATION_COUNT = "uploaded_observation_count";
	private static final String KEY_PENDING_OBSERVATION_COUNT = "pending_observation_count";
	private static final String OUTBOX_FILE_NAME = "roadcrew-map-observations.json";

	private RoadCrewMapObservationConsent() {
	}

	public static boolean isEnabled(@NonNull Context context) {
		SharedPreferences preferences = preferences(context);
		return preferences.getBoolean(KEY_ENABLED, false)
				&& preferences.getInt(KEY_CONSENT_VERSION, 0) == CURRENT_CONSENT_VERSION;
	}

	public static boolean hasCommunityRoutingAccess(@NonNull Context context) {
		return isEnabled(context);
	}

	static void setEnabled(@NonNull Context context, boolean enabled) {
		SharedPreferences.Editor editor = preferences(context).edit()
				.putBoolean(KEY_ENABLED, enabled)
				.putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION);
		if (!enabled) {
			editor.putInt(KEY_PENDING_OBSERVATION_COUNT, 0);
		}
		editor.apply();
	}

	static void recordUploadSuccess(@NonNull Context context, int uploadedCount, int pendingCount) {
		SharedPreferences preferences = preferences(context);
		preferences.edit()
				.putLong(KEY_LAST_UPLOAD_AT, System.currentTimeMillis())
				.putLong(KEY_LAST_UPLOAD_FAILURE_AT, 0)
				.putInt(KEY_UPLOADED_OBSERVATION_COUNT,
						preferences.getInt(KEY_UPLOADED_OBSERVATION_COUNT, 0)
								+ Math.max(0, uploadedCount))
				.putInt(KEY_PENDING_OBSERVATION_COUNT, Math.max(0, pendingCount))
				.apply();
	}

	static void recordUploadFailure(@NonNull Context context, int pendingCount) {
		preferences(context).edit()
				.putLong(KEY_LAST_UPLOAD_FAILURE_AT, System.currentTimeMillis())
				.putInt(KEY_PENDING_OBSERVATION_COUNT, Math.max(0, pendingCount))
				.apply();
	}

	static void recordPendingCount(@NonNull Context context, int pendingCount) {
		preferences(context).edit()
				.putInt(KEY_PENDING_OBSERVATION_COUNT, Math.max(0, pendingCount))
				.apply();
	}

	static long getLastUploadAt(@NonNull Context context) {
		return preferences(context).getLong(KEY_LAST_UPLOAD_AT, 0);
	}

	static boolean hasUploadError(@NonNull Context context) {
		SharedPreferences preferences = preferences(context);
		return preferences.getLong(KEY_LAST_UPLOAD_FAILURE_AT, 0)
				> preferences.getLong(KEY_LAST_UPLOAD_AT, 0);
	}

	static int getUploadedObservationCount(@NonNull Context context) {
		return preferences(context).getInt(KEY_UPLOADED_OBSERVATION_COUNT, 0);
	}

	static int getPendingObservationCount(@NonNull Context context) {
		return preferences(context).getInt(KEY_PENDING_OBSERVATION_COUNT, 0);
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
