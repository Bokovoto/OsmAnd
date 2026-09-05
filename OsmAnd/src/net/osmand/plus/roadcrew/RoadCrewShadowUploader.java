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
		schedule(app, outbox, false);
	}

	/** Used at the end of a course, when the queue may already be empty. */
	static synchronized void scheduleWithFinalDiagnostics(@NonNull OsmandApplication app,
			@NonNull RoadCrewShadowOutbox outbox) {
		schedule(app, outbox, true);
	}

	private static synchronized void schedule(@NonNull OsmandApplication app,
			@NonNull RoadCrewShadowOutbox outbox, boolean finalDiagnostics) {
		if (running) {
			return;
		}
		running = true;
		EXECUTOR.execute(() -> {
			try {
				boolean sentSomething = upload(app, outbox);
				if (finalDiagnostics && !sentSomething) {
					postDiagnosticsOnly(app);
				}
			} catch (RuntimeException e) {
				Log.w(TAG, "comparison upload run failed", e);
			} finally {
				synchronized (RoadCrewShadowUploader.class) {
					running = false;
				}
			}
		});
	}

	/** @return whether anything at all was sent, and so carried the diagnostics */
	private static boolean upload(@NonNull OsmandApplication app,
			@NonNull RoadCrewShadowOutbox outbox) {
		boolean sent = false;
		for (int index = 0; index < MAX_BATCHES_PER_RUN; index++) {
			if (!RoadCrewShadowValidation.isEnabled(app)) {
				return sent;
			}
			long now = System.currentTimeMillis();
			RoadCrewShadowOutbox.Batch batch;
			try {
				batch = outbox.nextBatch(now, RoadCrewShadowOutbox.MAX_BATCH_RECORDS);
			} catch (IOException e) {
				Log.w(TAG, "could not read the comparison queue", e);
				return sent;
			}
			if (batch.isEmpty()) {
				return sent;
			}
			try {
				post(app, batch);
				sent = true;
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
				return sent;
			}
		}
		return sent;
	}

	/**
	 * A chunk carrying only the final snapshot. A course can end with nothing
	 * left to upload, and that is exactly when the reason it ended matters: the
	 * last snapshot is the one holding COURSE_END and the conditions it
	 * depended on. The shadow endpoint accepts an empty observation list for
	 * this alone, and refuses one that carries no diagnostics either.
	 */
	private static void postDiagnosticsOnly(@NonNull OsmandApplication app) {
		String diagnostics = RoadCrewShadowValidation.diagnosticsJson();
		if (diagnostics == null) {
			return;
		}
		try {
			JSONObject body = new JSONObject();
			body.put("schemaVersion", 2);
			body.put("chunkId", java.util.UUID.randomUUID().toString());
			body.put("observations", new JSONArray());
			body.put("diagnostics", new JSONObject(diagnostics));
			send(app, "RCS2", gzip(body.toString().getBytes(StandardCharsets.UTF_8)));
			Log.i(TAG, "sent the final diagnostics with no observations to carry them");
		} catch (IOException | JSONException e) {
			Log.w(TAG, "could not send the final diagnostics", e);
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
		send(app, batch.getPipeline(), gzip(body.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static void send(@NonNull OsmandApplication app, @NonNull String pipeline,
			@NonNull byte[] compressed) throws IOException, JSONException {
		String url = SHADOW_CHUNK_URL + "?pipeline=" + pipeline
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
