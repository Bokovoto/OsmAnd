package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewShadowOutbox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.GZIPOutputStream;

/**
 * Sends the comparison sample to the shadow endpoint. Nothing it uploads can
 * become evidence: the server stores it under its own prefix, never queues it,
 * and expires it after a fortnight (ROADMAP section 168).
 *
 * It is separate from the production uploader on purpose. The two must not
 * share a retry state, a backoff, or a failure counter, because a fault in the
 * experiment would then be able to delay the real thing.
 */
final class RoadCrewShadowUploader {

	private static final String TAG = "RoadCrewShadowUpload";
	private static final String SHADOW_CHUNK_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v2/truck-map/shadow-chunks";
	/** Which client produced the sample, so two of them cannot be mixed into one result. */
	private static final int PIPELINE_VERSION = 1;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;
	private static final int MAX_BATCHES_PER_RUN = 4;

	private static final ScheduledExecutorService EXECUTOR =
			Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "roadcrew-shadow-upload");
				thread.setDaemon(true);
				return thread;
			});
	private static boolean running;

	private RoadCrewShadowUploader() {
	}

	static synchronized void schedule(@NonNull OsmandApplication app,
			@NonNull RoadCrewShadowOutbox outbox) {
		if (running) {
			return;
		}
		running = true;
		EXECUTOR.execute(() -> {
			try {
				upload(app, outbox);
			} catch (RuntimeException e) {
				Log.w(TAG, "comparison upload run failed", e);
			} finally {
				synchronized (RoadCrewShadowUploader.class) {
					running = false;
				}
			}
		});
	}

	private static void upload(@NonNull OsmandApplication app,
			@NonNull RoadCrewShadowOutbox outbox) {
		for (int index = 0; index < MAX_BATCHES_PER_RUN; index++) {
			if (!RoadCrewShadowValidation.isEnabled(app)) {
				return;
			}
			long now = System.currentTimeMillis();
			RoadCrewShadowOutbox.Batch batch;
			try {
				batch = outbox.nextBatch(now, RoadCrewShadowOutbox.MAX_BATCH_RECORDS);
			} catch (IOException e) {
				Log.w(TAG, "could not read the comparison queue", e);
				return;
			}
			if (batch.isEmpty()) {
				return;
			}
			try {
				post(app, batch);
				outbox.markUploaded(batch.getIds());
				Log.i(TAG, "sent " + batch.getPipeline() + " chunk=" + batch.getBatchId()
						+ " records=" + batch.getRecords().size()
						+ " remaining=" + outbox.pendingCount());
			} catch (IOException | JSONException e) {
				try {
					outbox.markFailed(batch.getIds(), now);
				} catch (IOException persistError) {
					Log.w(TAG, "could not record the comparison retry state", persistError);
				}
				Log.w(TAG, "comparison chunk " + batch.getBatchId() + " for "
						+ batch.getPipeline() + " stays queued; failures for that branch: "
						+ outbox.consecutiveFailures(batch.getPipeline()), e);
				return;
			}
		}
	}

	private static void post(@NonNull OsmandApplication app,
			@NonNull RoadCrewShadowOutbox.Batch batch) throws IOException, JSONException {
		JSONArray observations = new JSONArray();
		for (RoadCrewShadowOutbox.Record record : batch.getRecords()) {
			observations.put(new JSONObject(record.getPayload()));
		}
		JSONObject body = new JSONObject();
		body.put("schemaVersion", 2);
		body.put("chunkId", batch.getBatchId());
		body.put("observations", observations);
		// The server ignores unknown fields and stores the body verbatim, so the
		// diagnostics ride along without a second endpoint.
		String diagnostics = RoadCrewShadowValidation.diagnosticsJson();
		if (diagnostics != null) {
			body.put("diagnostics", new JSONObject(diagnostics));
		}
		byte[] compressed = gzip(body.toString().getBytes(StandardCharsets.UTF_8));

		String url = SHADOW_CHUNK_URL + "?pipeline=" + batch.getPipeline()
				+ "&pipelineVersion=" + PIPELINE_VERSION;
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		try {
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			connection.setReadTimeout(READ_TIMEOUT_MILLIS);
			connection.setRequestProperty("Content-Type",
					"application/vnd.roadcrew.truck-map+gzip");
			connection.setRequestProperty("Authorization",
					"Bearer " + RoadCrewMapObservationUploader.getOrRegisterInstallationToken(app));
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode(compressed.length);
			try (OutputStream output = connection.getOutputStream()) {
				output.write(compressed);
			}
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				throw new IOException("shadow chunk refused with HTTP " + responseCode);
			}
		} finally {
			connection.disconnect();
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
}
