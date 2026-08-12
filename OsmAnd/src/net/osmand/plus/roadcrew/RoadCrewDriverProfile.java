package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class RoadCrewDriverProfile {

	private static final String PREFS_NAME = "roadcrew_driver_profile";
	private static final String KEY_DRIVER_NAME = "driver_name";
	private static final String KEY_TRUCK_NUMBER = "truck_number";
	private static final String KEY_TRAILER_NUMBER = "trailer_number";
	private static final String KEY_PLATE_ALERTS_ENABLED = "plate_alerts_enabled";

	private static final int MAX_DRIVER_NAME_LENGTH = 60;
	private static final int MAX_PLATE_LENGTH = 20;

	@NonNull
	private final String driverName;
	@NonNull
	private final String truckNumber;
	@NonNull
	private final String trailerNumber;
	private final boolean plateAlertsEnabled;

	private RoadCrewDriverProfile(@NonNull String driverName, @NonNull String truckNumber,
			@NonNull String trailerNumber, boolean plateAlertsEnabled) {
		this.driverName = driverName;
		this.truckNumber = truckNumber;
		this.trailerNumber = trailerNumber;
		this.plateAlertsEnabled = plateAlertsEnabled;
	}

	@NonNull
	public static RoadCrewDriverProfile load(@NonNull OsmandApplication app) {
		SharedPreferences preferences = getPreferences(app);
		return new RoadCrewDriverProfile(
				preferences.getString(KEY_DRIVER_NAME, ""),
				preferences.getString(KEY_TRUCK_NUMBER, ""),
				preferences.getString(KEY_TRAILER_NUMBER, ""),
				preferences.getBoolean(KEY_PLATE_ALERTS_ENABLED, false)
		);
	}

	public static void save(@NonNull OsmandApplication app, @NonNull String driverName,
			@NonNull String truckNumber, @NonNull String trailerNumber, boolean plateAlertsEnabled) {
		getPreferences(app).edit()
				.putString(KEY_DRIVER_NAME, cleanText(driverName, MAX_DRIVER_NAME_LENGTH))
				.putString(KEY_TRUCK_NUMBER, normalizePlate(truckNumber))
				.putString(KEY_TRAILER_NUMBER, normalizePlate(trailerNumber))
				.putBoolean(KEY_PLATE_ALERTS_ENABLED, plateAlertsEnabled)
				.commit();
	}

	@NonNull
	public String getDriverName() {
		return driverName;
	}

	@NonNull
	public String getTruckNumber() {
		return truckNumber;
	}

	@NonNull
	public String getTrailerNumber() {
		return trailerNumber;
	}

	public boolean isPlateAlertsEnabled() {
		return plateAlertsEnabled;
	}

	@NonNull
	public String getDisplayName() {
		if (!driverName.isEmpty()) {
			return driverName;
		}
		if (!truckNumber.isEmpty()) {
			return truckNumber;
		}
		return "";
	}

	public boolean hasPlateIdentity() {
		return !truckNumber.isEmpty() || !trailerNumber.isEmpty();
	}

	@NonNull
	public String getTruckPlateHash() {
		return plateHash(truckNumber);
	}

	@NonNull
	public String getTrailerPlateHash() {
		return plateHash(trailerNumber);
	}

	@NonNull
	public static String normalizePlateNumber(@NonNull String value) {
		return normalizePlate(value);
	}

	@NonNull
	public static String plateHash(@NonNull String normalizedPlate) {
		if (normalizedPlate.isEmpty()) {
			return "";
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(normalizedPlate.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(bytes.length * 2);
			for (byte value : bytes) {
				builder.append(String.format("%02x", value & 0xff));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			return "";
		}
	}

	@NonNull
	private static SharedPreferences getPreferences(@NonNull OsmandApplication app) {
		return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	@NonNull
	private static String cleanText(@NonNull String value, int maxLength) {
		String cleanValue = value.replaceAll("\\s+", " ").trim();
		return cleanValue.substring(0, Math.min(cleanValue.length(), maxLength));
	}

	@NonNull
	private static String normalizePlate(@NonNull String value) {
		return cleanText(value.replaceAll("[^\\p{Alnum}]", "").toUpperCase(), MAX_PLATE_LENGTH);
	}
}
