package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
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

	static String getExistingInstallationToken(OsmandApplication app) {
		return app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(INSTALLATION_TOKEN, "");
	}
	private static final String QUEUE_RECOVERY_VERSION = "queue_recovery_version";
	private static final int CURRENT_QUEUE_RECOVERY_VERSION = 1;
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
	private static long scheduledAtMillis = Long.MAX_VALUE;
	private static OsmandApplication pendingApp;
	private static RoadCrewObservationOutbox pendingOutbox;
	private static boolean networkCallbackRegistered;
	private static final ConnectivityManager.NetworkCallback NETWORK_CALLBACK =
			new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(@NonNull Network network) {
					retryPendingAfterNetworkAvailable();
				}
			};

	private RoadCrewMapObservationUploader() {
	}

	static synchronized void schedule(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		pendingApp = app;
		pendingOutbox = outbox;
		ensureNetworkCallback(app);
		if (running) {
			return;
		}
		RoadCrewObservationOutbox.Snapshot snapshot = outbox.snapshot();
		if (snapshot.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		long delay = uploadDelayMillis(app, outbox, snapshot, now);
		long targetAtMillis = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
		if (scheduled != null && !scheduled.isDone()) {
			if (scheduledAtMillis <= targetAtMillis) {
				return;
			}
			scheduled.cancel(false);
		}
		scheduledAtMillis = targetAtMillis;
		scheduled = EXECUTOR.schedule(RoadCrewMapObservationUploader::runScheduled,
				delay, TimeUnit.MILLISECONDS);
	}

	static void retryNow(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		try {
			outbox.makeRetryRecordsEligibleNow();
		} catch (IOException e) {
			Log.w(TAG, "Cannot make queued Live Truck Map observations eligible", e);
		}
		flushNow(app, outbox);
	}

	static synchronized void flushNow(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		pendingApp = app;
		pendingOutbox = outbox;
		ensureNetworkCallback(app);
		if (scheduled != null && !scheduled.isDone()) {
			scheduled.cancel(false);
		}
		scheduledAtMillis = Long.MAX_VALUE;
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
			scheduledAtMillis = Long.MAX_VALUE;
			if (running) {
				return;
			}
			running = true;
			app = pendingApp;
			outbox = pendingOutbox;
		}
		try {
			if (app != null && outbox != null && RoadCrewMapObservationConsent.isEnabled(app)) {
				prepareQueueRecovery(app, outbox);
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

	private static long uploadDelayMillis(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox,
			@NonNull RoadCrewObservationOutbox.Snapshot snapshot, long now) {
		if (snapshot.size() >= IMMEDIATE_BATCH_THRESHOLD || needsQueueRecovery(app, outbox)) {
			return 0;
		}
		long delay = NORMAL_FLUSH_DELAY_MILLIS;
		for (RoadCrewObservationOutbox.Record record : snapshot.getRecords()) {
			if (record.getAttemptCount() > 0) {
				delay = Math.min(delay, Math.max(0, record.getNextAttemptAtMillis() - now));
			}
		}
		return delay;
	}

	private static synchronized void ensureNetworkCallback(@NonNull OsmandApplication app) {
		if (networkCallbackRegistered) {
			return;
		}
		ConnectivityManager manager = (ConnectivityManager) app.getSystemService(
				Context.CONNECTIVITY_SERVICE);
		if (manager == null) {
			return;
		}
		try {
			manager.registerDefaultNetworkCallback(NETWORK_CALLBACK);
			networkCallbackRegistered = true;
		} catch (RuntimeException e) {
			Log.w(TAG, "Cannot observe network recovery for Live Truck Map", e);
		}
	}

	private static void retryPendingAfterNetworkAvailable() {
		OsmandApplication app;
		RoadCrewObservationOutbox outbox;
		synchronized (RoadCrewMapObservationUploader.class) {
			app = pendingApp;
			outbox = pendingOutbox;
		}
		if (app != null && outbox != null && RoadCrewMapObservationConsent.isEnabled(app)) {
			EXECUTOR.execute(() -> retryNow(app, outbox));
		}
	}

	private static boolean needsQueueRecovery(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		return !outbox.snapshot().isEmpty()
				&& app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
						.getInt(QUEUE_RECOVERY_VERSION, 0) < CURRENT_QUEUE_RECOVERY_VERSION;
	}

	private static void prepareQueueRecovery(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		SharedPreferences preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
		if (preferences.getInt(QUEUE_RECOVERY_VERSION, 0) >= CURRENT_QUEUE_RECOVERY_VERSION) {
			return;
		}
		try {
			int resetCount = outbox.resetRetrySchedule();
			preferences.edit().putInt(QUEUE_RECOVERY_VERSION,
					CURRENT_QUEUE_RECOVERY_VERSION).apply();
			if (resetCount > 0) {
				Log.i(TAG, "Reset retry schedule for " + resetCount
						+ " queued Live Truck Map observations");
			}
		} catch (IOException e) {
			Log.w(TAG, "Cannot prepare queued Live Truck Map recovery", e);
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
				UploadBatchResult result = postBatchRecoveringPermanentFailures(app, batch);
				if (!RoadCrewMapObservationConsent.isEnabled(app)) {
					return;
				}
				Set<String> completedIds = new HashSet<>(result.acceptedIds);
				completedIds.addAll(result.rejectedIds);
				if (completedIds.size() != attemptedIds.size()
						|| !completedIds.containsAll(attemptedIds)) {
					throw new IOException("RoadCrew server did not acknowledge the complete observation chunk");
				}
				if (!result.acceptedIds.isEmpty()) {
					outbox.markUploaded(result.acceptedIds);
					RoadCrewMapObservationConsent.recordUploadSuccess(app,
							result.acceptedIds.size(), outbox.snapshot().size());
				}
				if (!result.rejectedIds.isEmpty()) {
					outbox.markRejected(result.rejectedIds);
					RoadCrewMapObservationConsent.recordRejectedObservations(app,
							result.rejectedIds.size(), outbox.snapshot().size());
					Log.w(TAG, "Quarantined " + result.rejectedIds.size()
							+ " permanently rejected Live Truck Map observations");
				}
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
	private static UploadBatchResult postBatchRecoveringPermanentFailures(
			@NonNull OsmandApplication app,
			@NonNull List<RoadCrewObservationOutbox.Record> records)
			throws IOException, JSONException {
		try {
			Set<String> acceptedIds = postBatch(app, records);
			List<String> attemptedIds = recordIds(records);
			if (acceptedIds.size() != attemptedIds.size()
					|| !acceptedIds.containsAll(attemptedIds)) {
				throw new IOException("RoadCrew server did not acknowledge the complete observation chunk");
			}
			return UploadBatchResult.accepted(acceptedIds);
		} catch (HttpStatusException e) {
			if (!isPermanentRecordFailure(e.responseCode)) {
				throw e;
			}
			if (records.size() == 1) {
				Log.w(TAG, "Live Truck Map observation rejected permanently: "
						+ records.get(0).getId() + " (HTTP " + e.responseCode + ")");
				return UploadBatchResult.rejected(records.get(0).getId());
			}
			int middle = records.size() / 2;
			UploadBatchResult first = postBatchRecoveringPermanentFailures(app,
					records.subList(0, middle));
			UploadBatchResult second = postBatchRecoveringPermanentFailures(app,
					records.subList(middle, records.size()));
			return first.combine(second);
		}
	}

	private static boolean isPermanentRecordFailure(int responseCode) {
		return responseCode == HttpURLConnection.HTTP_BAD_REQUEST
				|| responseCode == HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
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
				throw new HttpStatusException(responseCode, responseBody);
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

	private static final class HttpStatusException extends IOException {
		private final int responseCode;

		private HttpStatusException(int responseCode, @NonNull String responseBody) {
			super("RoadCrew truck map API failed with HTTP " + responseCode + ": "
					+ responseBody);
			this.responseCode = responseCode;
		}
	}

	private static final class UploadBatchResult {
		private final Set<String> acceptedIds;
		private final Set<String> rejectedIds;

		private UploadBatchResult(@NonNull Set<String> acceptedIds,
				@NonNull Set<String> rejectedIds) {
			this.acceptedIds = acceptedIds;
			this.rejectedIds = rejectedIds;
		}

		@NonNull
		private static UploadBatchResult accepted(@NonNull Set<String> ids) {
			return new UploadBatchResult(new HashSet<>(ids), new HashSet<>());
		}

		@NonNull
		private static UploadBatchResult rejected(@NonNull String id) {
			Set<String> ids = new HashSet<>();
			ids.add(id);
			return new UploadBatchResult(new HashSet<>(), ids);
		}

		@NonNull
		private UploadBatchResult combine(@NonNull UploadBatchResult other) {
			Set<String> accepted = new HashSet<>(acceptedIds);
			accepted.addAll(other.acceptedIds);
			Set<String> rejected = new HashSet<>(rejectedIds);
			rejected.addAll(other.rejectedIds);
			return new UploadBatchResult(accepted, rejected);
		}
	}

	@NonNull
	static String getOrRegisterInstallationToken(@NonNull OsmandApplication app)
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
