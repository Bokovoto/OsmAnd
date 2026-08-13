package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
	public List<String> getTruckPlateHashes() {
		return plateHashesForLookup(truckNumber);
	}

	@NonNull
	public String getTrailerPlateHash() {
		return plateHash(trailerNumber);
	}

	@NonNull
	public List<String> getTrailerPlateHashes() {
		return plateHashesForLookup(trailerNumber);
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
	public static List<String> plateHashesForLookup(@NonNull String normalizedPlate) {
		Set<String> hashes = new LinkedHashSet<>();
		String canonicalHash = plateHash(normalizedPlate);
		if (!canonicalHash.isEmpty()) {
			hashes.add(canonicalHash);
		}
		String legacyCyrillicPlate = toLegacyCyrillicPlate(normalizedPlate);
		String legacyHash = plateHash(legacyCyrillicPlate);
		if (!legacyHash.isEmpty()) {
			hashes.add(legacyHash);
		}
		return new ArrayList<>(hashes);
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
		String upperValue = value.toUpperCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(upperValue.length());
		for (int i = 0; i < upperValue.length(); i++) {
			char c = upperValue.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				builder.append(toCanonicalPlateChar(c));
			}
		}
		return cleanText(builder.toString(), MAX_PLATE_LENGTH);
	}

	private static char toCanonicalPlateChar(char c) {
		switch (c) {
			case 'А':
				return 'A';
			case 'В':
				return 'B';
			case 'Е':
				return 'E';
			case 'К':
				return 'K';
			case 'М':
				return 'M';
			case 'Н':
				return 'H';
			case 'О':
				return 'O';
			case 'Р':
				return 'P';
			case 'С':
				return 'C';
			case 'Т':
				return 'T';
			case 'У':
				return 'Y';
			case 'Х':
				return 'X';
			default:
				return c;
		}
	}

	@NonNull
	private static String toLegacyCyrillicPlate(@NonNull String normalizedPlate) {
		StringBuilder builder = new StringBuilder(normalizedPlate.length());
		for (int i = 0; i < normalizedPlate.length(); i++) {
			char c = normalizedPlate.charAt(i);
			switch (c) {
				case 'A':
					builder.append('А');
					break;
				case 'B':
					builder.append('В');
					break;
				case 'E':
					builder.append('Е');
					break;
				case 'K':
					builder.append('К');
					break;
				case 'M':
					builder.append('М');
					break;
				case 'H':
					builder.append('Н');
					break;
				case 'O':
					builder.append('О');
					break;
				case 'P':
					builder.append('Р');
					break;
				case 'C':
					builder.append('С');
					break;
				case 'T':
					builder.append('Т');
					break;
				case 'Y':
					builder.append('У');
					break;
				case 'X':
					builder.append('Х');
					break;
				default:
					builder.append(c);
					break;
			}
		}
		return builder.toString();
	}
}
