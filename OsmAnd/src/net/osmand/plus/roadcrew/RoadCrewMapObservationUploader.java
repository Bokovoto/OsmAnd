package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewObservationOutbox;
import net.osmand.router.RoadCrewSegmentIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Uploads only aggregate segment evidence from the consent-gated local outbox. */
final class RoadCrewMapObservationUploader {

	private static final String TAG = "RoadCrewMapUploader";
	private static final String API_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v1/truck-map/observations";
	private static final String DEVICE_ID_HEADER = "X-RoadCrew-Device-Id";
	private static final int SCHEMA_VERSION = 1;
	private static final int BATCH_SIZE = 50;
	private static final int MAX_BATCHES_PER_RUN = 4;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 15_000;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
	private static boolean running;
	private static boolean requested;
	private static OsmandApplication pendingApp;
	private static RoadCrewObservationOutbox pendingOutbox;

	private RoadCrewMapObservationUploader() {
	}

	static synchronized void schedule(@NonNull OsmandApplication app,
			@NonNull RoadCrewObservationOutbox outbox) {
		pendingApp = app;
		pendingOutbox = outbox;
		requested = true;
		if (!running) {
			running = true;
			EXECUTOR.execute(RoadCrewMapObservationUploader::drainRequests);
		}
	}

	private static void drainRequests() {
		while (true) {
			OsmandApplication app;
			RoadCrewObservationOutbox outbox;
			synchronized (RoadCrewMapObservationUploader.class) {
				if (!requested) {
					running = false;
					return;
				}
				requested = false;
				app = pendingApp;
				outbox = pendingOutbox;
			}
			if (app != null && outbox != null && RoadCrewMapObservationConsent.isEnabled(app)) {
				uploadAvailable(app, outbox);
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
				return;
			}
			List<String> attemptedIds = recordIds(batch);
			try {
				Set<String> acceptedIds = postBatch(app, batch);
				if (!RoadCrewMapObservationConsent.isEnabled(app)) {
					return;
				}
				if (acceptedIds.size() != attemptedIds.size() || !acceptedIds.containsAll(attemptedIds)) {
					throw new IOException("RoadCrew server did not acknowledge the complete observation batch");
				}
				outbox.markUploaded(acceptedIds);
			} catch (IOException | JSONException e) {
				Log.w(TAG, "Live Truck Map upload failed; observations remain queued", e);
				if (!RoadCrewMapObservationConsent.isEnabled(app)) {
					return;
				}
				try {
					outbox.markFailed(attemptedIds, now);
				} catch (IOException persistError) {
					Log.e(TAG, "Cannot persist Live Truck Map retry state", persistError);
				}
				return;
			}
		}
		schedule(app, outbox);
	}

	@NonNull
	private static Set<String> postBatch(@NonNull OsmandApplication app,
			@NonNull List<RoadCrewObservationOutbox.Record> records)
			throws IOException, JSONException {
		JSONObject body = new JSONObject();
		body.put("schemaVersion", SCHEMA_VERSION);
		JSONArray observations = new JSONArray();
		for (RoadCrewObservationOutbox.Record record : records) {
			observations.put(toJson(record));
		}
		body.put("observations", observations);

		HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
		connection.setRequestMethod("POST");
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty(DEVICE_ID_HEADER,
				RoadCrewReportsRepository.getLocalDeviceId(app));
		connection.setDoOutput(true);
		byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
		connection.setFixedLengthStreamingMode(bytes.length);
		try (OutputStream output = connection.getOutputStream()) {
			output.write(bytes);
		}

		int responseCode = connection.getResponseCode();
		String responseBody = readResponse(connection, responseCode);
		connection.disconnect();
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
