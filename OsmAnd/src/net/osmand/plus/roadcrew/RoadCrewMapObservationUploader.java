package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewObservationOutbox;
import net.osmand.router.RoadCrewSegmentIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/** Uploads only aggregate segment evidence from the consent-gated local outbox. */
final class RoadCrewMapObservationUploader {

	private static final String TAG = "RoadCrewMapUploader";
	private static final String REGISTER_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v2/installations/register";
	private static final String CHUNK_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v2/truck-map/chunks";
	private static final String PREFERENCES = "roadcrew_truck_map_ingest_v2";
	private static final String INSTALLATION_TOKEN = "installation_token";
	private static final int SCHEMA_VERSION = 2;
	private static final int BATCH_SIZE = 100;
	private static final int IMMEDIATE_BATCH_THRESHOLD = 100;
	private static final int MAX_BATCHES_PER_RUN = 4;
	private static final long NORMAL_FLUSH_DELAY_MILLIS = 10 * 60 * 1_000L;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;

	private static final ScheduledExecutorService EXECUTOR =
			Executors.newSingleThreadScheduledExecutor();
	private static boolean running;
	private static ScheduledFuture<?> scheduled;
	private static OsmandApplication pendingApp;
	private static RoadCrewObservationOutbox pendingOutbox;

	private RoadCrewMapObservationUploader() {
	}

	static synchronized void schedule(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		pendingApp = app;
		pendingOutbox = outbox;
		if (running) {
			return;
		}
		boolean immediate = outbox.snapshot().size() >= IMMEDIATE_BATCH_THRESHOLD;
		if (scheduled != null && !scheduled.isDone()) {
			if (!immediate) {
				return;
			}
			scheduled.cancel(false);
		}
		long delay = immediate ? 0 : NORMAL_FLUSH_DELAY_MILLIS;
		scheduled = EXECUTOR.schedule(RoadCrewMapObservationUploader::runScheduled,
				delay, TimeUnit.MILLISECONDS);
	}

	static synchronized void flushNow(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		pendingApp = app;
		pendingOutbox = outbox;
		if (scheduled != null && !scheduled.isDone()) {
			scheduled.cancel(false);
		}
		if (!running) {
			scheduled = EXECUTOR.schedule(RoadCrewMapObservationUploader::runScheduled,
					0, TimeUnit.MILLISECONDS);
		}
	}

	private static void runScheduled() {
		OsmandApplication app;
		RoadCrewObservationOutbox outbox;
		synchronized (RoadCrewMapObservationUploader.class) {
			scheduled = null;
			if (running) {
				return;
			}
			running = true;
			app = pendingApp;
			outbox = pendingOutbox;
		}
		try {
			if (app != null && outbox != null && RoadCrewMapObservationConsent.isEnabled(app)) {
				uploadAvailable(app, outbox);
			}
		} finally {
			synchronized (RoadCrewMapObservationUploader.class) {
				running = false;
			}
			if (app != null && outbox != null && !outbox.snapshot().isEmpty()
					&& RoadCrewMapObservationConsent.isEnabled(app)) {
				schedule(app, outbox);
			}
		}
	}

	private static void uploadAvailable(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		for (int batchIndex = 0; batchIndex < MAX_BATCHES_PER_RUN; batchIndex++) {
			if (!RoadCrewMapObservationConsent.isEnabled(app)) {
				return;
			}
			long now = System.currentTimeMillis();
			List<RoadCrewObservationOutbox.Record> batch = outbox.getEligibleBatch(now, BATCH_SIZE);
			if (batch.isEmpty()) {
				RoadCrewMapObservationConsent.recordPendingCount(app, outbox.snapshot().size());
				return;
			}
			List<String> attemptedIds = recordIds(batch);
			try {
				Set<String> acceptedIds = postBatch(app, batch);
				if (!RoadCrewMapObservationConsent.isEnabled(app)) {
					return;
				}
				if (acceptedIds.size() != attemptedIds.size() || !acceptedIds.containsAll(attemptedIds)) {
					throw new IOException("RoadCrew server did not acknowledge the complete observation chunk");
				}
				outbox.markUploaded(acceptedIds);
				RoadCrewMapObservationConsent.recordUploadSuccess(app, acceptedIds.size(),
						outbox.snapshot().size());
			} catch (IOException | JSONException e) {
				Log.w(TAG, "Live Truck Map chunk upload failed; observations remain queued", e);
				if (!RoadCrewMapObservationConsent.isEnabled(app)) {
					return;
				}
				try {
					outbox.markFailed(attemptedIds, now);
					RoadCrewMapObservationConsent.recordUploadFailure(app, outbox.snapshot().size());
				} catch (IOException persistError) {
					Log.e(TAG, "Cannot persist Live Truck Map retry state", persistError);
				}
				return;
			}
		}
	}

	@NonNull
	private static Set<String> postBatch(@NonNull OsmandApplication app,
			@NonNull List<RoadCrewObservationOutbox.Record> records)
			throws IOException, JSONException {
		JSONObject body = new JSONObject();
		body.put("schemaVersion", SCHEMA_VERSION);
		body.put("chunkId", deterministicChunkId(records));
		JSONArray observations = new JSONArray();
		for (RoadCrewObservationOutbox.Record record : records) {
			observations.put(toJson(record));
		}
		body.put("observations", observations);
		byte[] compressed = gzip(body.toString().getBytes(StandardCharsets.UTF_8));

		for (int attempt = 0; attempt < 2; attempt++) {
			String token = getOrRegisterInstallationToken(app);
			HttpURLConnection connection = (HttpURLConnection) new URL(CHUNK_URL).openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			connection.setReadTimeout(READ_TIMEOUT_MILLIS);
			connection.setRequestProperty("Content-Type", "application/vnd.roadcrew.truck-map+gzip");
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("Authorization", "Bearer " + token);
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode(compressed.length);
			try (OutputStream output = connection.getOutputStream()) {
				output.write(compressed);
			}
			int responseCode = connection.getResponseCode();
			String responseBody = readResponse(connection, responseCode);
			connection.disconnect();
			if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0) {
				clearInstallationToken(app);
				continue;
			}
			if (responseCode < 200 || responseCode >= 300) {
				throw new IOException("RoadCrew truck map API failed with HTTP "
						+ responseCode + ": " + responseBody);
			}
			JSONArray accepted = new JSONObject(responseBody).optJSONArray("acceptedIds");
			if (accepted == null) {
				throw new IOException("RoadCrew truck map API returned no acknowledgements");
			}
			Set<String> acceptedIds = new HashSet<>();
			for (int index = 0; index < accepted.length(); index++) {
				String id = accepted.optString(index, "");
				if (!id.isEmpty()) {
					acceptedIds.add(id);
				}
			}
			return acceptedIds;
		}
		throw new IOException("RoadCrew installation authentication failed");
	}

	@NonNull
	private static String getOrRegisterInstallationToken(@NonNull OsmandApplication app)
			throws IOException, JSONException {
		SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
		String existing = preferences.getString(INSTALLATION_TOKEN, "");
		if (existing != null && !existing.isEmpty()) {
			return existing;
		}
		HttpURLConnection connection = (HttpURLConnection) new URL(REGISTER_URL).openConnection();
		connection.setRequestMethod("POST");
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("Accept", "application/json");
		connection.setDoOutput(true);
		connection.setFixedLengthStreamingMode(0);
		try (OutputStream ignored = connection.getOutputStream()) {
			// Empty by design: the anonymous token is not tied to profile data.
		}
		int responseCode = connection.getResponseCode();
		String responseBody = readResponse(connection, responseCode);
		connection.disconnect();
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("RoadCrew installation registration failed with HTTP "
					+ responseCode + ": " + responseBody);
		}
		String token = new JSONObject(responseBody).optString("installationToken", "");
		if (token.isEmpty()) {
			throw new IOException("RoadCrew installation registration returned no token");
		}
		preferences.edit().putString(INSTALLATION_TOKEN, token).apply();
		return token;
	}

	private static void clearInstallationToken(@NonNull OsmandApplication app) {
		app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
				.edit().remove(INSTALLATION_TOKEN).apply();
	}

	@NonNull
	private static String deterministicChunkId(
			@NonNull List<RoadCrewObservationOutbox.Record> records) throws IOException {
		List<String> ids = recordIds(records);
		Collections.sort(ids);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String id : ids) {
				digest.update(id.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			StringBuilder result = new StringBuilder(64);
			for (byte value : digest.digest()) {
				result.append(String.format("%02x", value & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	@NonNull
	private static byte[] gzip(@NonNull byte[] bytes) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
			gzip.write(bytes);
		}
		return output.toByteArray();
	}

	@NonNull
	private static JSONObject toJson(@NonNull RoadCrewObservationOutbox.Record record)
			throws JSONException {
		JSONObject json = new JSONObject();
		json.put("id", record.getId());
		RoadCrewSegmentIdentity.SegmentKey key = record.getSegmentKey();
		JSONObject segment = new JSONObject();
		segment.put("version", key.getVersion());
		segment.put("osmWayId", Long.toString(key.getOsmWayId()));
		segment.put("region", key.getRegion());
		segment.put("fromLatitude", key.getFromLatitude());
		segment.put("fromLongitude", key.getFromLongitude());
		segment.put("toLatitude", key.getToLatitude());
		segment.put("toLongitude", key.getToLongitude());
		segment.put("geometryFingerprint", key.getGeometryFingerprint());
		segment.put("lengthMeters", key.getLengthMeters());
		segment.put("canonicalId", key.getCanonicalId());
		json.put("segmentKey", segment);
		json.put("observedAtBucketMillis", record.getObservedAtBucketMillis());
		json.put("fixCount", record.getFixCount());
		json.put("durationMillis", record.getDurationMillis());
		json.put("forwardMovementMeters", record.getForwardMovementMeters());
		json.put("maximumDistanceMeters", record.getMaximumDistanceMeters());
		json.put("maximumHeadingDifferenceDegrees", record.getMaximumHeadingDifferenceDegrees());
		return json;
	}

	@NonNull
	private static List<String> recordIds(@NonNull List<RoadCrewObservationOutbox.Record> records) {
		List<String> ids = new ArrayList<>(records.size());
		for (RoadCrewObservationOutbox.Record record : records) {
			ids.add(record.getId());
		}
		return ids;
	}

	@NonNull
	private static String readResponse(@NonNull HttpURLConnection connection, int responseCode)
			throws IOException {
		InputStream stream = responseCode >= 200 && responseCode < 300
				? connection.getInputStream() : connection.getErrorStream();
		if (stream == null) {
			return "";
		}
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder result = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				result.append(line);
			}
			return result.toString();
		}
	}
}
