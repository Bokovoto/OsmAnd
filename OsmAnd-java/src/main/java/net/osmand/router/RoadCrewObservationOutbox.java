package net.osmand.router;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable local queue for minimal RoadCrew passage observations. It deliberately
 * stores no raw GPS fixes, precise event timestamp, driver ID, or vehicle ID.
 */
public final class RoadCrewObservationOutbox {

	public static final int SCHEMA_VERSION = 2;
	public static final long OBSERVATION_BUCKET_MILLIS = 15 * 60 * 1_000L;
	public static final int DEFAULT_MAX_RECORDS = 2_000;
	public static final int DEFAULT_MAX_ACKNOWLEDGED_KEYS = 4_000;
	public static final long DEFAULT_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1_000;
	public static final long RETRY_BASE_DELAY_MILLIS = 30_000;
	public static final long RETRY_MAX_DELAY_MILLIS = 15L * 60 * 1_000;

	private static final int MAX_ID_LENGTH = 128;
	private static final int MAX_ATTEMPT_COUNT = 100_000;
	private static final Gson GSON = new Gson();
	private static final Comparator<Record> RECORD_ORDER = Comparator
			.comparingLong(Record::getObservedAtBucketMillis)
			.thenComparing(Record::getId);

	private final File file;
	private final File temporaryFile;
	private final File backupFile;
	private final Clock clock;
	private final IdGenerator idGenerator;
	private final int maxRecords;
	private final long maxAgeMillis;
	private final List<Record> records;
	private final LinkedHashMap<String, Long> acknowledgedKeys;

	private RoadCrewObservationOutbox(File file, Clock clock, IdGenerator idGenerator,
			int maxRecords, long maxAgeMillis, LoadResult loadResult) throws IOException {
		this.file = file;
		this.temporaryFile = sibling(file, ".tmp");
		this.backupFile = sibling(file, ".bak");
		this.clock = clock;
		this.idGenerator = idGenerator;
		this.maxRecords = maxRecords;
		this.maxAgeMillis = maxAgeMillis;
		this.records = new ArrayList<>(loadResult.records);
		this.acknowledgedKeys = new LinkedHashMap<>(loadResult.acknowledgedKeys);

		if (loadResult.recovered) {
			repairPrimary();
		}
		if (prune(clock.nowMillis())) {
			persist();
		}
	}

	public static RoadCrewObservationOutbox open(File file) throws IOException {
		return open(file, System::currentTimeMillis, () -> UUID.randomUUID().toString(),
				DEFAULT_MAX_RECORDS, DEFAULT_MAX_AGE_MILLIS);
	}

	public static RoadCrewObservationOutbox open(File file, Clock clock,
			IdGenerator idGenerator) throws IOException {
		return open(file, clock, idGenerator, DEFAULT_MAX_RECORDS, DEFAULT_MAX_AGE_MILLIS);
	}

	static RoadCrewObservationOutbox open(File file, Clock clock, IdGenerator idGenerator,
			int maxRecords, long maxAgeMillis) throws IOException {
		if (file == null || clock == null || idGenerator == null || maxRecords <= 0 || maxAgeMillis <= 0) {
			throw new IllegalArgumentException("Invalid RoadCrew observation outbox configuration");
		}
		if (file.isDirectory()) {
			throw new IOException("RoadCrew observation outbox path is a directory: " + file);
		}
		LoadResult loadResult = load(file, sibling(file, ".bak"), sibling(file, ".tmp"));
		return new RoadCrewObservationOutbox(file, clock, idGenerator, maxRecords, maxAgeMillis, loadResult);
	}

	public synchronized EnqueueResult enqueue(RoadCrewPassageDetector.PassageEvidence evidence,
			long observedAtMillis) throws IOException {
		validateEvidence(evidence);
		if (observedAtMillis <= 0) {
			throw new IllegalArgumentException("Observation wall time must be positive");
		}
		boolean pruned = prune(clock.nowMillis());
		long bucket = observedAtMillis - observedAtMillis % OBSERVATION_BUCKET_MILLIS;
		RoadCrewSegmentIdentity.SegmentKey key = evidence.getSegmentKey();
		for (int i = 0; i < records.size(); i++) {
			Record existing = records.get(i);
			if (existing.isSameObservation(key, bucket)) {
				Record merged = existing.merge(evidence);
				if (merged != existing) {
					records.set(i, merged);
					persist();
				} else if (pruned) {
					persist();
				}
				return new EnqueueResult(EnqueueStatus.DEDUPLICATED, merged);
			}
		}
		if (acknowledgedKeys.containsKey(observationKey(key, bucket))) {
			if (pruned) {
				persist();
			}
			return new EnqueueResult(EnqueueStatus.ALREADY_UPLOADED, null);
		}

		String id = idGenerator.nextId();
		validateId(id);
		Record record = Record.fromEvidence(id, bucket, evidence);
		records.add(record);
		prune(clock.nowMillis());
		persist();
		return new EnqueueResult(EnqueueStatus.ADDED, record);
	}

	public synchronized List<Record> getEligibleBatch(long nowMillis, int limit) {
		if (nowMillis < 0 || limit <= 0) {
			return Collections.emptyList();
		}
		List<Record> eligible = new ArrayList<>();
		for (Record record : records) {
			if (record.nextAttemptAtMillis <= nowMillis) {
				eligible.add(record);
			}
		}
		eligible.sort(RECORD_ORDER);
		if (eligible.size() > limit) {
			eligible = new ArrayList<>(eligible.subList(0, limit));
		}
		return Collections.unmodifiableList(eligible);
	}

	public synchronized int resetRetrySchedule() throws IOException {
		int changed = 0;
		for (int i = 0; i < records.size(); i++) {
			Record record = records.get(i);
			if (record.attemptCount > 0 || record.nextAttemptAtMillis > 0) {
				records.set(i, record.retryNow());
				changed++;
			}
		}
		if (changed > 0) {
			persist();
		}
		return changed;
	}

	public synchronized int makeRetryRecordsEligibleNow() throws IOException {
		int changed = 0;
		for (int i = 0; i < records.size(); i++) {
			Record record = records.get(i);
			if (record.attemptCount > 0 && record.nextAttemptAtMillis > 0) {
				records.set(i, record.retryEligibleNow());
				changed++;
			}
		}
		if (changed > 0) {
			persist();
		}
		return changed;
	}

	public synchronized int markFailed(Iterable<String> ids, long nowMillis) throws IOException {
		if (nowMillis < 0) {
			throw new IllegalArgumentException("Retry wall time must not be negative");
		}
		Set<String> selected = normalizedIds(ids);
		if (selected.isEmpty()) {
			return 0;
		}
		int changed = 0;
		for (int i = 0; i < records.size(); i++) {
			Record record = records.get(i);
			if (selected.contains(record.id)) {
				records.set(i, record.failed(nowMillis));
				changed++;
			}
		}
		if (changed > 0) {
			persist();
		}
		return changed;
	}

	public synchronized int markUploaded(Iterable<String> ids) throws IOException {
		return removeAcknowledged(ids);
	}

	/**
	 * Removes observations that the server has permanently rejected while retaining
	 * their segment/bucket keys so the same evidence is not enqueued again.
	 */
	public synchronized int markRejected(Iterable<String> ids) throws IOException {
		return removeAcknowledged(ids);
	}

	private int removeAcknowledged(Iterable<String> ids) throws IOException {
		Set<String> selected = normalizedIds(ids);
		if (selected.isEmpty()) {
			return 0;
		}
		int removed = 0;
		for (Iterator<Record> iterator = records.iterator(); iterator.hasNext(); ) {
			Record record = iterator.next();
			if (selected.contains(record.id)) {
				acknowledgedKeys.put(observationKey(record.segmentKey,
						record.observedAtBucketMillis), record.observedAtBucketMillis);
				iterator.remove();
				removed++;
			}
		}
		if (removed > 0) {
			pruneAcknowledged(clock.nowMillis());
			persist();
		}
		return removed;
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(records);
	}

	private boolean prune(long nowMillis) {
		boolean changed = false;
		long oldestAllowed = Math.max(0, nowMillis - maxAgeMillis);
		for (Iterator<Record> iterator = records.iterator(); iterator.hasNext(); ) {
			if (iterator.next().observedAtBucketMillis < oldestAllowed) {
				iterator.remove();
				changed = true;
			}
		}
		if (records.size() > maxRecords) {
			records.sort(RECORD_ORDER);
			int removeCount = records.size() - maxRecords;
			records.subList(0, removeCount).clear();
			changed = true;
		}
		changed |= pruneAcknowledged(nowMillis);
		return changed;
	}

	private boolean pruneAcknowledged(long nowMillis) {
		boolean changed = false;
		long oldestAllowed = Math.max(0, nowMillis - maxAgeMillis);
		for (Iterator<Map.Entry<String, Long>> iterator = acknowledgedKeys.entrySet().iterator();
				iterator.hasNext(); ) {
			if (iterator.next().getValue() < oldestAllowed) {
				iterator.remove();
				changed = true;
			}
		}
		while (acknowledgedKeys.size() > DEFAULT_MAX_ACKNOWLEDGED_KEYS) {
			Iterator<String> iterator = acknowledgedKeys.keySet().iterator();
			iterator.next();
			iterator.remove();
			changed = true;
		}
		return changed;
	}

	private void persist() throws IOException {
		ensureParentDirectory();
		writeSnapshot(temporaryFile, records, acknowledgedKeys);
		boolean rotatedPrimary = false;
		if (file.exists()) {
			if (backupFile.exists() && !backupFile.delete()) {
				throw new IOException("Cannot replace RoadCrew observation backup: " + backupFile);
			}
			if (!file.renameTo(backupFile)) {
				throw new IOException("Cannot rotate RoadCrew observation outbox: " + file);
			}
			rotatedPrimary = true;
		}
		if (!temporaryFile.renameTo(file)) {
			if (rotatedPrimary && !backupFile.renameTo(file)) {
				throw new IOException("Cannot publish or restore RoadCrew observation outbox: " + file);
			}
			throw new IOException("Cannot publish RoadCrew observation outbox: " + file);
		}
	}

	private void repairPrimary() throws IOException {
		ensureParentDirectory();
		writeSnapshot(temporaryFile, records, acknowledgedKeys);
		if (file.exists() && !file.delete()) {
			throw new IOException("Cannot replace corrupt RoadCrew observation outbox: " + file);
		}
		if (!temporaryFile.renameTo(file)) {
			throw new IOException("Cannot restore RoadCrew observation outbox: " + file);
		}
	}

	private void ensureParentDirectory() throws IOException {
		File parent = file.getAbsoluteFile().getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
			throw new IOException("Cannot create RoadCrew observation directory: " + parent);
		}
	}

	private static LoadResult load(File primary, File backup, File temporary) throws IOException {
		List<File> candidates = new ArrayList<>();
		if (primary.exists()) {
			candidates.add(primary);
		}
		if (backup.exists()) {
			candidates.add(backup);
		}
		if (temporary.exists()) {
			candidates.add(temporary);
		}
		if (candidates.isEmpty()) {
			return new LoadResult(Collections.emptyList(), Collections.emptyMap(), false);
		}

		IOException failure = null;
		for (File candidate : candidates) {
			try {
				SnapshotJson snapshot = readSnapshot(candidate);
				return new LoadResult(snapshot.records, snapshot.acknowledgedKeys,
						!candidate.equals(primary));
			} catch (IOException e) {
				if (failure == null) {
					failure = new IOException("No valid RoadCrew observation outbox snapshot");
				}
				failure.addSuppressed(e);
			}
		}
		throw failure;
	}

	private static SnapshotJson readSnapshot(File source) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(source), StandardCharsets.UTF_8))) {
			RootJson root = GSON.fromJson(reader, RootJson.class);
			if (root == null || (root.schemaVersion != 1 && root.schemaVersion != SCHEMA_VERSION)
					|| root.records == null) {
				throw new IOException("Unsupported RoadCrew observation outbox schema: " + source);
			}
			List<Record> loaded = new ArrayList<>();
			Set<String> ids = new HashSet<>();
			for (RecordJson json : root.records) {
				Record record = Record.fromJson(json);
				if (!ids.add(record.id)) {
					throw new IOException("Duplicate RoadCrew observation ID: " + record.id);
				}
				loaded.add(record);
			}
			LinkedHashMap<String, Long> acknowledged = new LinkedHashMap<>();
			if (root.schemaVersion >= 2 && root.acknowledgedKeys != null) {
				for (AcknowledgedKeyJson json : root.acknowledgedKeys) {
					if (json == null || json.key == null || json.key.isEmpty()
							|| json.observedAtBucketMillis <= 0
							|| json.observedAtBucketMillis % OBSERVATION_BUCKET_MILLIS != 0) {
						throw new IOException("Invalid acknowledged RoadCrew observation key");
					}
					acknowledged.put(json.key, json.observedAtBucketMillis);
				}
			}
			return new SnapshotJson(loaded, acknowledged);
		} catch (JsonParseException | IllegalArgumentException e) {
			throw new IOException("Invalid RoadCrew observation outbox: " + source, e);
		}
	}

	private static void writeSnapshot(File destination, List<Record> records,
			Map<String, Long> acknowledgedKeys) throws IOException {
		RootJson root = new RootJson();
		root.schemaVersion = SCHEMA_VERSION;
		root.records = new ArrayList<>();
		for (Record record : records) {
			root.records.add(record.toJson());
		}
		root.acknowledgedKeys = new ArrayList<>();
		for (Map.Entry<String, Long> entry : acknowledgedKeys.entrySet()) {
			AcknowledgedKeyJson acknowledged = new AcknowledgedKeyJson();
			acknowledged.key = entry.getKey();
			acknowledged.observedAtBucketMillis = entry.getValue();
			root.acknowledgedKeys.add(acknowledged);
		}
		FileOutputStream stream = new FileOutputStream(destination, false);
		try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
			writer.write(GSON.toJson(root));
			writer.flush();
			FileDescriptor descriptor = stream.getFD();
			descriptor.sync();
		}
	}

	private static File sibling(File file, String suffix) {
		return new File(file.getPath() + suffix);
	}

	private static Set<String> normalizedIds(Iterable<String> ids) {
		if (ids == null) {
			return Collections.emptySet();
		}
		Set<String> result = new HashSet<>();
		for (String id : ids) {
			if (id != null && !id.trim().isEmpty()) {
				result.add(id.trim());
			}
		}
		return result;
	}

	private static void validateEvidence(RoadCrewPassageDetector.PassageEvidence evidence) {
		if (evidence == null || evidence.getSegmentKey() == null
				|| evidence.getFixCount() < RoadCrewPassageDetector.MIN_MATCHED_FIX_COUNT
				|| evidence.getDurationMillis() < RoadCrewPassageDetector.MIN_PASSAGE_DURATION_MILLIS
				|| !isNonNegativeFinite(evidence.getForwardMovementMeters())
				|| !isNonNegativeFinite(evidence.getMaximumDistanceMeters())
				|| !isNonNegativeFinite(evidence.getMaximumHeadingDifferenceDegrees())
				|| evidence.getMaximumHeadingDifferenceDegrees() > 180) {
			throw new IllegalArgumentException("Invalid RoadCrew passage evidence");
		}
	}

	private static void validateId(String id) {
		if (id == null || id.trim().isEmpty() || id.length() > MAX_ID_LENGTH
				|| !id.matches("[A-Za-z0-9._:-]+")) {
			throw new IllegalArgumentException("Invalid RoadCrew observation ID");
		}
	}

	private static boolean isNonNegativeFinite(double value) {
		return Double.isFinite(value) && value >= 0;
	}

	private static long retryDelay(int attemptCount) {
		long delay = RETRY_BASE_DELAY_MILLIS;
		for (int i = 1; i < attemptCount && delay < RETRY_MAX_DELAY_MILLIS; i++) {
			delay = Math.min(RETRY_MAX_DELAY_MILLIS, delay * 2);
		}
		return delay;
	}

	private static String observationKey(RoadCrewSegmentIdentity.SegmentKey key, long bucket) {
		return key.getCanonicalId() + ":" + key.getGeometryFingerprint() + ":" + bucket;
	}

	public interface Clock {
		long nowMillis();
	}

	public interface IdGenerator {
		String nextId();
	}

	public enum EnqueueStatus {
		ADDED,
		DEDUPLICATED,
		ALREADY_UPLOADED
	}

	public static final class EnqueueResult {
		private final EnqueueStatus status;
		private final Record record;

		private EnqueueResult(EnqueueStatus status, Record record) {
			this.status = status;
			this.record = record;
		}

		public EnqueueStatus getStatus() {
			return status;
		}

		public Record getRecord() {
			return record;
		}
	}

	public static final class Snapshot {
		private final List<Record> records;

		private Snapshot(List<Record> records) {
			List<Record> copy = new ArrayList<>(records);
			copy.sort(RECORD_ORDER);
			this.records = Collections.unmodifiableList(copy);
		}

		public List<Record> getRecords() {
			return records;
		}

		public int size() {
			return records.size();
		}

		public boolean isEmpty() {
			return records.isEmpty();
		}
	}

	public static final class Record {
		private final String id;
		private final RoadCrewSegmentIdentity.SegmentKey segmentKey;
		private final long observedAtBucketMillis;
		private final int fixCount;
		private final long durationMillis;
		private final double forwardMovementMeters;
		private final double maximumDistanceMeters;
		private final double maximumHeadingDifferenceDegrees;
		private final int attemptCount;
		private final long nextAttemptAtMillis;

		private Record(String id, RoadCrewSegmentIdentity.SegmentKey segmentKey,
				long observedAtBucketMillis, int fixCount, long durationMillis,
				double forwardMovementMeters, double maximumDistanceMeters,
				double maximumHeadingDifferenceDegrees, int attemptCount,
				long nextAttemptAtMillis) {
			this.id = id;
			this.segmentKey = segmentKey;
			this.observedAtBucketMillis = observedAtBucketMillis;
			this.fixCount = fixCount;
			this.durationMillis = durationMillis;
			this.forwardMovementMeters = forwardMovementMeters;
			this.maximumDistanceMeters = maximumDistanceMeters;
			this.maximumHeadingDifferenceDegrees = maximumHeadingDifferenceDegrees;
			this.attemptCount = attemptCount;
			this.nextAttemptAtMillis = nextAttemptAtMillis;
		}

		private static Record fromEvidence(String id, long bucket,
				RoadCrewPassageDetector.PassageEvidence evidence) {
			return new Record(id, evidence.getSegmentKey(), bucket, evidence.getFixCount(),
					evidence.getDurationMillis(), evidence.getForwardMovementMeters(),
					evidence.getMaximumDistanceMeters(),
					evidence.getMaximumHeadingDifferenceDegrees(), 0, 0);
		}

		private static Record fromJson(RecordJson json) throws IOException {
			if (json == null || json.segmentKey == null) {
				throw new IOException("Missing RoadCrew observation record");
			}
			validateId(json.id);
			if (json.observedAtBucketMillis <= 0
					|| json.observedAtBucketMillis % OBSERVATION_BUCKET_MILLIS != 0
					|| json.fixCount < RoadCrewPassageDetector.MIN_MATCHED_FIX_COUNT
					|| json.durationMillis < RoadCrewPassageDetector.MIN_PASSAGE_DURATION_MILLIS
					|| !isNonNegativeFinite(json.forwardMovementMeters)
					|| !isNonNegativeFinite(json.maximumDistanceMeters)
					|| !isNonNegativeFinite(json.maximumHeadingDifferenceDegrees)
					|| json.maximumHeadingDifferenceDegrees > 180
					|| json.attemptCount < 0 || json.attemptCount > MAX_ATTEMPT_COUNT
					|| json.nextAttemptAtMillis < 0) {
				throw new IOException("Invalid RoadCrew observation record: " + json.id);
			}
			long osmWayId;
			try {
				osmWayId = Long.parseLong(json.segmentKey.osmWayId);
			} catch (NumberFormatException e) {
				throw new IOException("Invalid RoadCrew observation OSM way ID", e);
			}
			RoadCrewSegmentIdentity.SegmentKey key = RoadCrewSegmentIdentity.key(
					json.segmentKey.version, osmWayId, json.segmentKey.region,
					json.segmentKey.fromLatitude, json.segmentKey.fromLongitude,
					json.segmentKey.toLatitude, json.segmentKey.toLongitude,
					json.segmentKey.geometryFingerprint, json.segmentKey.lengthMeters);
			return new Record(json.id, key, json.observedAtBucketMillis, json.fixCount,
					json.durationMillis, json.forwardMovementMeters, json.maximumDistanceMeters,
					json.maximumHeadingDifferenceDegrees, json.attemptCount,
					json.nextAttemptAtMillis);
		}

		private boolean isSameObservation(RoadCrewSegmentIdentity.SegmentKey key, long bucket) {
			return observedAtBucketMillis == bucket
					&& segmentKey.getCanonicalId().equals(key.getCanonicalId())
					&& segmentKey.getGeometryFingerprint().equals(key.getGeometryFingerprint());
		}

		private Record merge(RoadCrewPassageDetector.PassageEvidence evidence) {
			int mergedFixCount = Math.max(fixCount, evidence.getFixCount());
			long mergedDuration = Math.max(durationMillis, evidence.getDurationMillis());
			double mergedMovement = Math.max(forwardMovementMeters, evidence.getForwardMovementMeters());
			double mergedDistance = Math.max(maximumDistanceMeters, evidence.getMaximumDistanceMeters());
			double mergedHeading = Math.max(maximumHeadingDifferenceDegrees,
					evidence.getMaximumHeadingDifferenceDegrees());
			if (mergedFixCount == fixCount && mergedDuration == durationMillis
					&& mergedMovement == forwardMovementMeters && mergedDistance == maximumDistanceMeters
					&& mergedHeading == maximumHeadingDifferenceDegrees) {
				return this;
			}
			return new Record(id, segmentKey, observedAtBucketMillis, mergedFixCount,
					mergedDuration, mergedMovement, mergedDistance, mergedHeading,
					attemptCount, nextAttemptAtMillis);
		}

		private Record failed(long nowMillis) {
			int nextAttemptCount = Math.min(MAX_ATTEMPT_COUNT, attemptCount + 1);
			long delay = retryDelay(nextAttemptCount);
			long nextAttempt = nowMillis > Long.MAX_VALUE - delay ? Long.MAX_VALUE : nowMillis + delay;
			return new Record(id, segmentKey, observedAtBucketMillis, fixCount, durationMillis,
					forwardMovementMeters, maximumDistanceMeters, maximumHeadingDifferenceDegrees,
					nextAttemptCount, nextAttempt);
		}

		private Record retryNow() {
			return new Record(id, segmentKey, observedAtBucketMillis, fixCount, durationMillis,
					forwardMovementMeters, maximumDistanceMeters, maximumHeadingDifferenceDegrees,
					0, 0);
		}

		private Record retryEligibleNow() {
			return new Record(id, segmentKey, observedAtBucketMillis, fixCount, durationMillis,
					forwardMovementMeters, maximumDistanceMeters, maximumHeadingDifferenceDegrees,
					attemptCount, 0);
		}

		private RecordJson toJson() {
			RecordJson json = new RecordJson();
			json.id = id;
			json.segmentKey = SegmentKeyJson.from(segmentKey);
			json.observedAtBucketMillis = observedAtBucketMillis;
			json.fixCount = fixCount;
			json.durationMillis = durationMillis;
			json.forwardMovementMeters = forwardMovementMeters;
			json.maximumDistanceMeters = maximumDistanceMeters;
			json.maximumHeadingDifferenceDegrees = maximumHeadingDifferenceDegrees;
			json.attemptCount = attemptCount;
			json.nextAttemptAtMillis = nextAttemptAtMillis;
			return json;
		}

		public String getId() {
			return id;
		}

		public RoadCrewSegmentIdentity.SegmentKey getSegmentKey() {
			return segmentKey;
		}

		public long getObservedAtBucketMillis() {
			return observedAtBucketMillis;
		}

		public int getFixCount() {
			return fixCount;
		}

		public long getDurationMillis() {
			return durationMillis;
		}

		public double getForwardMovementMeters() {
			return forwardMovementMeters;
		}

		public double getMaximumDistanceMeters() {
			return maximumDistanceMeters;
		}

		public double getMaximumHeadingDifferenceDegrees() {
			return maximumHeadingDifferenceDegrees;
		}

		public int getAttemptCount() {
			return attemptCount;
		}

		public long getNextAttemptAtMillis() {
			return nextAttemptAtMillis;
		}
	}

	private static final class LoadResult {
		private final List<Record> records;
		private final Map<String, Long> acknowledgedKeys;
		private final boolean recovered;

		private LoadResult(List<Record> records, Map<String, Long> acknowledgedKeys,
				boolean recovered) {
			this.records = records;
			this.acknowledgedKeys = acknowledgedKeys;
			this.recovered = recovered;
		}
	}

	private static final class SnapshotJson {
		private final List<Record> records;
		private final Map<String, Long> acknowledgedKeys;

		private SnapshotJson(List<Record> records, Map<String, Long> acknowledgedKeys) {
			this.records = records;
			this.acknowledgedKeys = acknowledgedKeys;
		}
	}

	private static final class RootJson {
		int schemaVersion;
		List<RecordJson> records;
		List<AcknowledgedKeyJson> acknowledgedKeys;
	}

	private static final class AcknowledgedKeyJson {
		String key;
		long observedAtBucketMillis;
	}

	private static final class RecordJson {
		String id;
		SegmentKeyJson segmentKey;
		long observedAtBucketMillis;
		int fixCount;
		long durationMillis;
		double forwardMovementMeters;
		double maximumDistanceMeters;
		double maximumHeadingDifferenceDegrees;
		int attemptCount;
		long nextAttemptAtMillis;
	}

	private static final class SegmentKeyJson {
		int version;
		String osmWayId;
		String region;
		double fromLatitude;
		double fromLongitude;
		double toLatitude;
		double toLongitude;
		String geometryFingerprint;
		double lengthMeters;

		private static SegmentKeyJson from(RoadCrewSegmentIdentity.SegmentKey key) {
			SegmentKeyJson json = new SegmentKeyJson();
			json.version = key.getVersion();
			json.osmWayId = Long.toString(key.getOsmWayId());
			json.region = key.getRegion();
			json.fromLatitude = key.getFromLatitude();
			json.fromLongitude = key.getFromLongitude();
			json.toLatitude = key.getToLatitude();
			json.toLongitude = key.getToLongitude();
			json.geometryFingerprint = key.getGeometryFingerprint();
			json.lengthMeters = key.getLengthMeters();
			return json;
		}
	}
}
