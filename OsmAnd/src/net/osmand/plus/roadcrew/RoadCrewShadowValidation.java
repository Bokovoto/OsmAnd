package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.binary.RouteDataObject;
import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewDirectObservation;
import net.osmand.router.RoadCrewDirectPipeline;
import net.osmand.router.RoadCrewObservationOutbox;
import net.osmand.router.RoadCrewPassageDetector;
import net.osmand.router.RoadCrewSegmentIdentity;
import net.osmand.router.RoadCrewShadowOutbox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The comparison side of ROADMAP section 168 and 169.
 *
 * Both segmentations run over the same map matches; both copies are queued
 * here, and this queue is the only thing they touch. The production path -
 * legacy observations waiting on the phone for the driver's trip review - is
 * not altered in any way, because it is the control in the experiment.
 *
 * Everything in this class is best effort. A failure here must never reach the
 * drive, so every entry point swallows what it catches and says so in the log.
 */
public final class RoadCrewShadowValidation {

	private static final String TAG = "RoadCrewShadow";
	private static final String PREFS_NAME = "roadcrew_shadow_validation";
	/** The server's last answer about this installation, and when it was given. */
	private static final String KEY_VALIDATION_MODE = "validation_mode";
	private static final String KEY_CHECKED_AT = "validation_mode_checked_at";
	private static final String KEY_REFRESH_AFTER_MILLIS = "validation_mode_refresh_after";
	private static final String QUEUE_FILE_NAME = "roadcrew-shadow-observations.json";
	private static final String VALIDATION_MODE_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v2/truck-map/validation-mode";
	private static final long DEFAULT_REFRESH_MILLIS = 6 * 60 * 60 * 1_000L;
	/** Do not hammer the server when it is unreachable. */
	private static final long RETRY_AFTER_FAILURE_MILLIS = 30 * 60 * 1_000L;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;

	private static final Object LOCK = new Object();
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "roadcrew-validation-mode");
		thread.setDaemon(true);
		return thread;
	});
	private static RoadCrewShadowOutbox outbox;
	private static boolean unavailable;
	private static boolean refreshing;

	private RoadCrewShadowValidation() {
	}

	/**
	 * Whether this installation is in the validation programme.
	 *
	 * Deliberately not a setting the driver can find. The comparison is only
	 * worth anything when several trucks drive the same roads at the same time,
	 * and that cannot be arranged by asking each driver to switch something on.
	 * The server decides, through `validation_mode`, which is also the one place
	 * the whole thing can be stopped for everybody without a release.
	 *
	 * Off until the server has said otherwise: never assume enrolment.
	 */
	public static boolean isEnabled(@NonNull Context context) {
		return preferences(context).getBoolean(KEY_VALIDATION_MODE, false)
				&& RoadCrewMapObservationConsent.isEnabled(context);
	}

	/**
	 * Asks the server again if the last answer has expired. Cheap enough to call
	 * from the ordinary tick: it does nothing at all until the answer is stale.
	 */
	public static void refreshIfDue(@NonNull OsmandApplication app) {
		if (!RoadCrewMapObservationConsent.isEnabled(app)) {
			return;
		}
		SharedPreferences preferences = preferences(app);
		long checkedAt = preferences.getLong(KEY_CHECKED_AT, 0);
		long refreshAfter = preferences.getLong(KEY_REFRESH_AFTER_MILLIS, DEFAULT_REFRESH_MILLIS);
		long now = System.currentTimeMillis();
		// A clock moved backwards must not freeze the answer for ever.
		if (checkedAt > 0 && now >= checkedAt && now - checkedAt < refreshAfter) {
			return;
		}
		synchronized (LOCK) {
			if (refreshing) {
				return;
			}
			refreshing = true;
		}
		EXECUTOR.execute(() -> {
			try {
				refresh(app);
			} catch (Exception e) {
				// Keep the previous answer and try again later rather than
				// guessing; an unreachable server is not a change of programme.
				preferences(app).edit()
						.putLong(KEY_CHECKED_AT, System.currentTimeMillis())
						.putLong(KEY_REFRESH_AFTER_MILLIS, RETRY_AFTER_FAILURE_MILLIS)
						.apply();
				Log.w(TAG, "could not ask whether this phone is in the programme", e);
			} finally {
				synchronized (LOCK) {
					refreshing = false;
				}
			}
		});
	}

	private static void refresh(@NonNull OsmandApplication app) throws Exception {
		HttpURLConnection connection =
				(HttpURLConnection) new URL(VALIDATION_MODE_URL).openConnection();
		String body;
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			connection.setReadTimeout(READ_TIMEOUT_MILLIS);
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("Authorization", "Bearer "
					+ RoadCrewMapObservationUploader.getOrRegisterInstallationToken(app));
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				throw new IOException("validation mode check returned HTTP " + responseCode);
			}
			body = readFully(connection.getInputStream());
		} finally {
			connection.disconnect();
		}
		JSONObject json = new JSONObject(body);
		boolean enrolled = json.optBoolean("validationMode", false);
		long refreshAfter = Math.max(15 * 60_000L,
				json.optLong("refreshAfterSeconds", DEFAULT_REFRESH_MILLIS / 1_000) * 1_000L);
		boolean wasEnrolled = preferences(app).getBoolean(KEY_VALIDATION_MODE, false);
		preferences(app).edit()
				.putBoolean(KEY_VALIDATION_MODE, enrolled)
				.putLong(KEY_CHECKED_AT, System.currentTimeMillis())
				.putLong(KEY_REFRESH_AFTER_MILLIS, refreshAfter)
				.apply();
		if (wasEnrolled && !enrolled) {
			// Switched off centrally: nothing collected before is worth keeping,
			// and nothing further would be accepted anyway.
			clear(app);
		}
		if (wasEnrolled != enrolled) {
			Log.i(TAG, "validation programme " + (enrolled ? "joined" : "left") + " by the server");
		}
	}

	@NonNull
	private static String readFully(@NonNull java.io.InputStream stream) throws IOException {
		try (java.io.BufferedReader reader = new java.io.BufferedReader(
				new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
			StringBuilder result = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				result.append(line);
			}
			return result.toString();
		}
	}

	/** What the settings screen shows about the comparison queue. */
	public static final class QueueStatus {
		public final boolean enabled;
		public final int pendingCount;
		public final int legacyCount;
		public final int directCount;

		private QueueStatus(boolean enabled, int pendingCount, int legacyCount, int directCount) {
			this.enabled = enabled;
			this.pendingCount = pendingCount;
			this.legacyCount = legacyCount;
			this.directCount = directCount;
		}
	}

	@NonNull
	public static QueueStatus getStatus(@NonNull OsmandApplication app) {
		if (!isEnabled(app)) {
			return new QueueStatus(false, 0, 0, 0);
		}
		try {
			RoadCrewShadowOutbox queue = queue(app);
			return new QueueStatus(true, queue.pendingCount(),
					queue.pendingCount(RoadCrewShadowOutbox.PIPELINE_LEGACY),
					queue.pendingCount(RoadCrewShadowOutbox.PIPELINE_DIRECT));
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "could not read the comparison queue", e);
			return new QueueStatus(true, 0, 0, 0);
		}
	}

	/** A fresh group for one recording session; it identifies no driver. */
	@NonNull
	public static String newComparisonGroupId() {
		return UUID.randomUUID().toString();
	}

	/**
	 * The legacy branch, copied. It is queued at the moment it is detected, not
	 * when the driver confirms the course: waiting would leave the comparison
	 * to whichever trips happen to get confirmed, and those are not a random
	 * sample of driving.
	 */
	public static void captureLegacy(@NonNull OsmandApplication app,
			@NonNull RoadCrewPassageDetector.PassageEvidence evidence, long observedAtMillis,
			@Nullable String comparisonGroupId, long firstFixSequence, long lastFixSequence,
			@Nullable RouteDataObject road,
			@Nullable RoadCrewSegmentIdentity.SegmentBinding binding) {
		if (!isEnabled(app)) {
			return;
		}
		try {
			RoadCrewObservationOutbox.Record record =
					RoadCrewObservationOutbox.Record.capture(evidence, observedAtMillis);
			// The direction the matcher actually resolved. The legacy key does
			// not carry one, and guessing it from the ends of the piece would
			// put an unknown error into the denominator of the comparison.
			// Nothing about the key or the production path changes.
			String direction = binding == null ? null : RoadCrewDirectPipeline.canonicalDirection(
					road, binding.getStartPointIndex(), binding.getEndPointIndex());
			JSONObject json = legacyJson(record, comparisonGroupId,
					firstFixSequence, lastFixSequence, direction);
			queue(app).add(RoadCrewShadowOutbox.PIPELINE_LEGACY, comparisonGroupId, json.toString());
			Log.i(TAG, "rcs1 group=" + comparisonGroupId
					+ " fixes=" + firstFixSequence + "-" + lastFixSequence
					+ " way=" + record.getSegmentKey().getOsmWayId());
		} catch (IOException | JSONException | RuntimeException e) {
			Log.w(TAG, "could not queue the legacy copy; the drive is unaffected", e);
		}
	}

	/** The directed branch, from the same matches. */
	public static void captureDirect(@NonNull OsmandApplication app,
			@NonNull List<RoadCrewDirectObservation> observations,
			@Nullable String comparisonGroupId) {
		if (!isEnabled(app) || observations.isEmpty()) {
			return;
		}
		try {
			RoadCrewShadowOutbox queue = queue(app);
			for (RoadCrewDirectObservation observation : observations) {
				queue.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, comparisonGroupId,
						directJson(observation, comparisonGroupId).toString());
			}
			RoadCrewDirectObservation first = observations.get(0);
			Log.i(TAG, "rcs2 group=" + comparisonGroupId
					+ " fixes=" + first.firstFixSequence + "-" + first.lastFixSequence
					+ " way=" + first.osmWayId + " parts=" + observations.size()
					+ " metres=" + Math.round(first.getLengthMeters()));
		} catch (IOException | JSONException | RuntimeException e) {
			Log.w(TAG, "could not queue the directed copy; the drive is unaffected", e);
		}
	}

	/** Sends if the flush rule says so; called after a capture and on a tick. */
	public static void flushIfDue(@NonNull OsmandApplication app) {
		if (!isEnabled(app)) {
			return;
		}
		try {
			RoadCrewShadowOutbox queue = queue(app);
			if (queue.shouldFlush(System.currentTimeMillis())) {
				RoadCrewShadowUploader.schedule(app, queue);
			}
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "could not consider a flush", e);
		}
	}

	/** Sends whatever is waiting: the drive stopped, or the app went away. */
	public static void flushNow(@NonNull OsmandApplication app) {
		if (!isEnabled(app)) {
			return;
		}
		try {
			RoadCrewShadowUploader.schedule(app, queue(app));
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "could not flush the comparison queue", e);
		}
	}

	public static void clear(@NonNull Context context) {
		synchronized (LOCK) {
			try {
				if (outbox != null) {
					outbox.clear();
				}
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "could not empty the comparison queue", e);
			}
			outbox = null;
			unavailable = false;
		}
		File file = getQueueFile(context);
		deleteIfPresent(file);
		deleteIfPresent(new File(file.getPath() + ".bak"));
		deleteIfPresent(new File(file.getPath() + ".tmp"));
	}

	@NonNull
	static File getQueueFile(@NonNull Context context) {
		return new File(context.getFilesDir(), QUEUE_FILE_NAME);
	}

	@NonNull
	private static RoadCrewShadowOutbox queue(@NonNull OsmandApplication app) throws IOException {
		synchronized (LOCK) {
			if (unavailable) {
				throw new IOException("The comparison queue could not be opened");
			}
			if (outbox == null) {
				try {
					outbox = RoadCrewShadowOutbox.open(getQueueFile(app));
				} catch (IOException e) {
					// Try once, then stop asking: a phone that cannot write this
					// file is not a phone whose ordinary recording should suffer.
					unavailable = true;
					throw e;
				}
			}
			return outbox;
		}
	}

	@NonNull
	private static JSONObject legacyJson(@NonNull RoadCrewObservationOutbox.Record record,
			@Nullable String comparisonGroupId, long firstFixSequence, long lastFixSequence,
			@Nullable String matcherDirection) throws JSONException {
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
		if (matcherDirection != null) {
			// Telemetry for the comparison only. The server ignores it, and it
			// is no part of any identity.
			json.put("shadowDirection", matcherDirection);
		}
		putComparison(json, comparisonGroupId, firstFixSequence, lastFixSequence);
		return json;
	}

	@NonNull
	private static JSONObject directJson(@NonNull RoadCrewDirectObservation observation,
			@Nullable String comparisonGroupId) throws JSONException {
		JSONObject json = new JSONObject();
		json.put("id", UUID.randomUUID().toString());
		JSONObject segment = new JSONObject();
		segment.put("version", RoadCrewDirectObservation.SEGMENT_KEY_VERSION);
		segment.put("osmWayId", Long.toString(observation.osmWayId));
		segment.put("direction", observation.getDirection());
		segment.put("canonicalId", observation.getCanonicalId());
		segment.put("region", observation.region);
		segment.put("fromMeasureMeters", observation.fromMeasureMeters);
		segment.put("toMeasureMeters", observation.toMeasureMeters);
		segment.put("fromLatitude", observation.fromLatitude);
		segment.put("fromLongitude", observation.fromLongitude);
		segment.put("toLatitude", observation.toLatitude);
		segment.put("toLongitude", observation.toLongitude);
		segment.put("startPointIndex", observation.startPointIndex);
		segment.put("endPointIndex", observation.endPointIndex);
		segment.put("mapVersion", observation.mapVersion);
		segment.put("geometryFingerprint", observation.geometryFingerprint);
		segment.put("geometryFingerprintAlgorithm", observation.geometryFingerprintAlgorithm);
		json.put("segmentKey", segment);
		json.put("observedAtBucketMillis", observation.observedAtBucketMillis);
		json.put("fixCount", observation.fixCount);
		json.put("durationMillis", observation.durationMillis);
		json.put("forwardMovementMeters", observation.forwardMovementMeters);
		json.put("maximumDistanceMeters", observation.maximumDistanceMeters);
		json.put("maximumHeadingDifferenceDegrees", observation.maximumHeadingDifferenceDegrees);
		putComparison(json, comparisonGroupId,
				observation.firstFixSequence, observation.lastFixSequence);
		return json;
	}

	private static void putComparison(@NonNull JSONObject json, @Nullable String comparisonGroupId,
			long firstFixSequence, long lastFixSequence) throws JSONException {
		if (comparisonGroupId != null && !comparisonGroupId.isEmpty()) {
			json.put("comparisonGroupId", comparisonGroupId);
		}
		if (firstFixSequence > 0 && lastFixSequence >= firstFixSequence) {
			json.put("firstFixSequence", firstFixSequence);
			json.put("lastFixSequence", lastFixSequence);
		}
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
