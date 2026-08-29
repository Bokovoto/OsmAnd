package net.osmand.plus.roadcrew;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewShadowIndex;
import net.osmand.util.MapUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads bounded evidence; routing separately consumes only validated, exact preferences. */
final class RoadCrewShadowSnapshotDownloader {

	private static final String TAG = "RoadCrewShadow";
	private static final String API_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v1/truck-map/shadow-segments";
	private static final String DEVICE_ID_HEADER = "X-RoadCrew-Device-Id";
	private static final double SNAPSHOT_RADIUS_METERS = 15_000;
	private static final double MIN_REFRESH_DISTANCE_METERS = 5_000;
	private static final long MIN_REFRESH_INTERVAL_MILLIS = 15 * 60_000L;
	private static final int SEGMENT_LIMIT = 500;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;
	private static final int MAX_RESPONSE_CHARS = 2_000_000;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	private static boolean running;
	private static long lastSuccessfulRequestAtMillis;
	private static double lastSuccessfulLatitude = Double.NaN;
	private static double lastSuccessfulLongitude = Double.NaN;

	private RoadCrewShadowSnapshotDownloader() {
	}

	static synchronized void schedule(@NonNull OsmandApplication app,
			double latitude, double longitude) {
		if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)
				|| !isCoordinate(latitude, longitude) || running) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastSuccessfulRequestAtMillis > 0
				&& now - lastSuccessfulRequestAtMillis < MIN_REFRESH_INTERVAL_MILLIS
				&& MapUtils.getDistance(lastSuccessfulLatitude, lastSuccessfulLongitude,
						latitude, longitude) < MIN_REFRESH_DISTANCE_METERS) {
			return;
		}
		running = true;
		EXECUTOR.execute(() -> download(app, latitude, longitude));
	}

	private static void download(@NonNull OsmandApplication app,
			double latitude, double longitude) {
		try {
			if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
				return;
			}
			String response = requestSnapshot(app, latitude, longitude);
			RoadCrewShadowIndex index = parseSnapshot(response);
			if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
				return;
			}
			persist(app, response);
			synchronized (RoadCrewShadowSnapshotDownloader.class) {
				lastSuccessfulRequestAtMillis = System.currentTimeMillis();
				lastSuccessfulLatitude = latitude;
				lastSuccessfulLongitude = longitude;
			}
			Log.i(TAG, "Cached read-only Shadow snapshot with " + index.size() + " segments");
		} catch (IOException | JSONException | IllegalArgumentException e) {
			Log.w(TAG, "Read-only Shadow snapshot refresh failed", e);
		} finally {
			synchronized (RoadCrewShadowSnapshotDownloader.class) {
				running = false;
			}
		}
	}

	@NonNull
	private static String requestSnapshot(@NonNull OsmandApplication app,
			double latitude, double longitude) throws IOException {
		double latitudeDelta = SNAPSHOT_RADIUS_METERS / 111_320.0;
		double longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(latitude)));
		double longitudeDelta = SNAPSHOT_RADIUS_METERS / (111_320.0 * longitudeScale);
		String query = String.format(Locale.US,
				"?minLat=%.6f&maxLat=%.6f&minLon=%.6f&maxLon=%.6f&limit=%d",
				latitude - latitudeDelta, latitude + latitudeDelta,
				longitude - longitudeDelta, longitude + longitudeDelta, SEGMENT_LIMIT);
		HttpURLConnection connection = (HttpURLConnection) new URL(API_URL + query).openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty(DEVICE_ID_HEADER,
				RoadCrewReportsRepository.getLocalDeviceId(app));
		int responseCode = connection.getResponseCode();
		String response = readResponse(connection, responseCode);
		connection.disconnect();
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("RoadCrew Shadow API failed with HTTP " + responseCode);
		}
		return response;
	}

	@NonNull
	static RoadCrewShadowIndex parseSnapshot(@NonNull String body) throws JSONException {
		JSONObject root = new JSONObject(body);
		if (!root.has("truncated")) {
			throw new JSONException("Missing RoadCrew Shadow completeness flag");
		}
		JSONArray segments = root.optJSONArray("segments");
		if (segments == null || segments.length() > SEGMENT_LIMIT) {
			throw new JSONException("Invalid RoadCrew Shadow segment collection");
		}
		List<RoadCrewShadowIndex.Entry> entries = new ArrayList<>(segments.length());
		for (int index = 0; index < segments.length(); index++) {
			JSONObject segment = segments.getJSONObject(index);
			entries.add(new RoadCrewShadowIndex.Entry(
					segment.getString("segmentId"),
					segment.getString("canonicalId"),
					segment.getString("geometryFingerprint"),
					RoadCrewShadowIndex.Level.parse(segment.getString("shadowLevel")),
					segment.getDouble("confidence"),
					segment.getInt("passageCount"),
					segment.getInt("distinctObserverCount"),
					segment.getInt("activeDayCount")));
		}
		JSONObject bounds = root.getJSONObject("bounds");
		return RoadCrewShadowIndex.create(root.getInt("schemaVersion"),
				root.getLong("generatedAt"), root.getString("routingEffect"),
				new RoadCrewShadowIndex.Bounds(
						bounds.getDouble("minLatitude"), bounds.getDouble("maxLatitude"),
						bounds.getDouble("minLongitude"), bounds.getDouble("maxLongitude")),
				entries);
	}

	@NonNull
	static Summary getCachedSummary(@NonNull Context context) {
		RoadCrewShadowIndex index = getCachedIndex(context);
		if (index != null) {
			return new Summary(true, index.getGeneratedAtMillis(), index.size(),
					index.count(RoadCrewShadowIndex.Level.COLLECTING),
					index.count(RoadCrewShadowIndex.Level.CANDIDATE),
					index.count(RoadCrewShadowIndex.Level.MATURE_SHADOW));
		}
		return Summary.empty();
	}

	@Nullable
	static RoadCrewShadowIndex getCachedIndex(@NonNull Context context) {
		for (File candidate : cacheCandidates(context)) {
			if (!candidate.isFile()) {
				continue;
			}
			try {
				return parseSnapshot(readFile(candidate));
			} catch (IOException | JSONException | IllegalArgumentException e) {
				Log.w(TAG, "Ignoring invalid cached Shadow snapshot", e);
			}
		}
		return null;
	}

	private static void persist(@NonNull Context context, @NonNull String body) throws IOException {
		File primary = RoadCrewMapObservationConsent.getShadowSnapshotFile(context);
		File temporary = new File(primary.getPath() + ".tmp");
		File backup = new File(primary.getPath() + ".bak");
		try (FileOutputStream stream = new FileOutputStream(temporary, false);
				BufferedWriter writer = new BufferedWriter(
						new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
			writer.write(body);
			writer.flush();
			stream.getFD().sync();
		}
		boolean rotated = false;
		if (primary.exists()) {
			if (backup.exists() && !backup.delete()) {
				throw new IOException("Cannot replace RoadCrew Shadow backup");
			}
			if (!primary.renameTo(backup)) {
				throw new IOException("Cannot rotate RoadCrew Shadow snapshot");
			}
			rotated = true;
		}
		if (!temporary.renameTo(primary)) {
			if (rotated && !backup.renameTo(primary)) {
				throw new IOException("Cannot publish or restore RoadCrew Shadow snapshot");
			}
			throw new IOException("Cannot publish RoadCrew Shadow snapshot");
		}
	}

	@NonNull
	private static List<File> cacheCandidates(@NonNull Context context) {
		File primary = RoadCrewMapObservationConsent.getShadowSnapshotFile(context);
		List<File> result = new ArrayList<>(2);
		result.add(primary);
		result.add(new File(primary.getPath() + ".bak"));
		return result;
	}

	@NonNull
	private static String readFile(@NonNull File file) throws IOException {
		try (FileInputStream stream = new FileInputStream(file)) {
			return readStream(stream);
		}
	}

	@NonNull
	private static String readResponse(@NonNull HttpURLConnection connection, int responseCode)
			throws IOException {
		InputStream stream = responseCode >= 200 && responseCode < 300
				? connection.getInputStream() : connection.getErrorStream();
		return stream == null ? "" : readStream(stream);
	}

	@NonNull
	private static String readStream(@NonNull InputStream stream) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder result = new StringBuilder();
			char[] buffer = new char[8_192];
			int read;
			while ((read = reader.read(buffer)) >= 0) {
				if (result.length() + read > MAX_RESPONSE_CHARS) {
					throw new IOException("RoadCrew Shadow response is too large");
				}
				result.append(buffer, 0, read);
			}
			return result.toString();
		}
	}

	private static boolean isCoordinate(double latitude, double longitude) {
		return Double.isFinite(latitude) && Double.isFinite(longitude)
				&& latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
	}

	static final class Summary {
		final boolean available;
		final long generatedAtMillis;
		final int totalCount;
		final int collectingCount;
		final int candidateCount;
		final int matureCount;

		private Summary(boolean available, long generatedAtMillis, int totalCount,
				int collectingCount, int candidateCount, int matureCount) {
			this.available = available;
			this.generatedAtMillis = generatedAtMillis;
			this.totalCount = totalCount;
			this.collectingCount = collectingCount;
			this.candidateCount = candidateCount;
			this.matureCount = matureCount;
		}

		private static Summary empty() {
			return new Summary(false, 0, 0, 0, 0, 0);
		}
	}
}
