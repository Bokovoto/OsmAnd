package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewDirectObservation;
import net.osmand.router.RoadCrewObservationOutbox;
import net.osmand.router.RoadCrewPassageDetector;
import net.osmand.router.RoadCrewSegmentIdentity;
import net.osmand.router.RoadCrewShadowOutbox;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

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
	private static final String KEY_ENABLED = "enabled";
	private static final String QUEUE_FILE_NAME = "roadcrew-shadow-observations.json";

	private static final Object LOCK = new Object();
	private static RoadCrewShadowOutbox outbox;
	private static boolean unavailable;

	private RoadCrewShadowValidation() {
	}

	/**
	 * Off unless the phone has been put in the programme. The flag is local, so
	 * a fault found in the field can be stopped without waiting for a release.
	 */
	public static boolean isEnabled(@NonNull Context context) {
		return preferences(context).getBoolean(KEY_ENABLED, false)
				&& RoadCrewMapObservationConsent.isEnabled(context);
	}

	public static void setEnabled(@NonNull Context context, boolean enabled) {
		preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
		if (!enabled) {
			clear(context);
		}
		Log.i(TAG, "validation programme " + (enabled ? "joined" : "left"));
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
			@Nullable String comparisonGroupId, long firstFixSequence, long lastFixSequence) {
		if (!isEnabled(app)) {
			return;
		}
		try {
			RoadCrewObservationOutbox.Record record =
					RoadCrewObservationOutbox.Record.capture(evidence, observedAtMillis);
			JSONObject json = legacyJson(record, comparisonGroupId,
					firstFixSequence, lastFixSequence);
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
			@Nullable String comparisonGroupId, long firstFixSequence, long lastFixSequence)
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
