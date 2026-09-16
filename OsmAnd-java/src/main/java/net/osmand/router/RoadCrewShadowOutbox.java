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
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The durable queue behind the A/B comparison of ROADMAP section 168.
 *
 * It is deliberately a separate file from the production outbox. Nothing here
 * can become evidence, nothing here waits for the driver's trip review, and if
 * it fills up or is thrown away entirely the ordinary recording is untouched -
 * that is the whole point of keeping the experiment on its own.
 *
 * What it holds is one already-serialised observation per record, plus only
 * enough to route it: which of the two pipelines produced it, and which
 * recording session it came from. The queue itself never inspects the payload,
 * so the two key shapes need no common type.
 */
public final class RoadCrewShadowOutbox {

	public static final int SCHEMA_VERSION = 1;
	/**
	 * About half an hour of driving on both branches at the rate measured on the
	 * server (roughly fifty observations a minute), not a day - the older ones are
	 * dropped past this and counted in droppedCount (Codex 16.09: the promise that
	 * everything eventually goes out was not true, and is not made here).
	 */
	public static final int DEFAULT_MAX_RECORDS = 1_500;
	/** A comparison sample older than this is of no use; the server expires it too. */
	public static final long DEFAULT_MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1_000;
	/** Send when this many are waiting... */
	public static final int FLUSH_OBSERVATION_COUNT = 500;
	/**
	 * ...or when the oldest has waited this long, whichever comes first.
	 *
	 * Fifteen minutes, not two (Galin, 16.09). A phone driving made about fifty
	 * files an hour, and 96% of everything the server stores is this comparison;
	 * at this cadence it makes a handful. Nothing is observed less: the queue is
	 * on the disk, survives the phone being killed, and goes out whole at the end
	 * of a drive. Appending instead of rewriting is what makes the wait cheap.
	 */
	public static final long FLUSH_INTERVAL_MILLIS = 15 * 60 * 1_000L;
	/** The server takes a thousand observations in one file (4d98b7f). */
	public static final int MAX_BATCH_RECORDS = 1_000;
	public static final long RETRY_BASE_DELAY_MILLIS = 60_000;
	public static final long RETRY_MAX_DELAY_MILLIS = 30 * 60 * 1_000L;

	public static final String PIPELINE_LEGACY = "RCS1";
	public static final String PIPELINE_DIRECT = "RCS2";

	private static final Gson GSON = new Gson();
	private static final int MAX_PAYLOAD_LENGTH = 8_192;

	private final File file;
	private final File temporaryFile;
	private final File backupFile;
	/**
	 * New observations are appended here, one line each, instead of rewriting the
	 * whole queue for every one of them. A phone driving queues about fifty a
	 * minute (measured on the server, 16.09): rewriting cost roughly the square of
	 * how long the queue is held, which is what made sending less often expensive.
	 * The queue is written whole again only when something is sent, dropped or
	 * cleared, and the log is then gone.
	 */
	private final File logFile;
	/** The number written at the head of the log this queue is appending to. */
	private int logGeneration;
	private final Clock clock;
	private final IdGenerator idGenerator;
	private final int maxRecords;
	private final long maxAgeMillis;
	private final List<Record> records;
	/** How many sends in a row have failed, counted per branch. */
	private int legacyFailures;
	private int directFailures;
	/** Records dropped because the queue was full, counted per branch. */
	private int legacyDropped;
	private int directDropped;

	public interface Clock {
		long nowMillis();
	}

	public interface IdGenerator {
		String nextId();
	}

	/** One serialised observation waiting to be sent. */
	public static final class Record {
		private String id;
		private String pipeline;
		private String comparisonGroupId;
		private String payload;
		private long createdAtMillis;
		private int attemptCount;
		private long nextAttemptAtMillis;

		Record() {
		}

		public String getId() {
			return id;
		}

		public String getPipeline() {
			return pipeline;
		}

		public String getComparisonGroupId() {
			return comparisonGroupId;
		}

		/** The observation exactly as it goes into the chunk body. */
		public String getPayload() {
			return payload;
		}

		public long getCreatedAtMillis() {
			return createdAtMillis;
		}

		public int getAttemptCount() {
			return attemptCount;
		}

		public long getNextAttemptAtMillis() {
			return nextAttemptAtMillis;
		}

		private boolean valid() {
			return id != null && !id.isEmpty()
					&& (PIPELINE_LEGACY.equals(pipeline) || PIPELINE_DIRECT.equals(pipeline))
					&& payload != null && !payload.isEmpty() && payload.length() <= MAX_PAYLOAD_LENGTH
					&& createdAtMillis > 0 && attemptCount >= 0;
		}
	}

	/**
	 * A set of records to send as one chunk. The id is derived from the record
	 * ids, so a retry of the same records produces the same chunk id and the
	 * server simply overwrites what it already has rather than counting it twice.
	 */
	public static final class Batch {
		private final String pipeline;
		private final String batchId;
		private final List<Record> records;

		private Batch(String pipeline, String batchId, List<Record> records) {
			this.pipeline = pipeline;
			this.batchId = batchId;
			this.records = records;
		}

		public String getPipeline() {
			return pipeline;
		}

		public String getBatchId() {
			return batchId;
		}

		public List<Record> getRecords() {
			return records;
		}

		public List<String> getIds() {
			List<String> ids = new ArrayList<>(records.size());
			for (Record record : records) {
				ids.add(record.id);
			}
			return ids;
		}

		public boolean isEmpty() {
			return records.isEmpty();
		}
	}

	private static final class State {
		int schemaVersion;
		/**
		 * Which log belongs to this state. persist() writes the state for the NEXT
		 * log and only then drops the current one, so a log left behind by a crash
		 * in between carries the previous number, is ignored, and cannot bring back
		 * records that were sent or cleared (Codex 16.09).
		 */
		int logGeneration;
		List<Record> records;
		int legacyFailures;
		int directFailures;
		int legacyDropped;
		int directDropped;
	}

	private RoadCrewShadowOutbox(File file, Clock clock, IdGenerator idGenerator,
			int maxRecords, long maxAgeMillis, State state) throws IOException {
		this.file = file;
		this.temporaryFile = new File(file.getParentFile(), file.getName() + ".tmp");
		this.backupFile = new File(file.getParentFile(), file.getName() + ".bak");
		this.logFile = logFileFor(file);
		this.logGeneration = Math.max(0, state.logGeneration);
		this.clock = clock;
		this.idGenerator = idGenerator;
		this.maxRecords = maxRecords;
		this.maxAgeMillis = maxAgeMillis;
		this.records = new ArrayList<>(state.records);
		this.legacyFailures = Math.max(0, state.legacyFailures);
		this.directFailures = Math.max(0, state.directFailures);
		this.legacyDropped = Math.max(0, state.legacyDropped);
		this.directDropped = Math.max(0, state.directDropped);
		if (prune(clock.nowMillis())) {
			persist();
		}
	}

	public static RoadCrewShadowOutbox open(File file) throws IOException {
		return open(file, System::currentTimeMillis, () -> UUID.randomUUID().toString(),
				DEFAULT_MAX_RECORDS, DEFAULT_MAX_AGE_MILLIS);
	}

	public static RoadCrewShadowOutbox open(File file, Clock clock, IdGenerator idGenerator)
			throws IOException {
		return open(file, clock, idGenerator, DEFAULT_MAX_RECORDS, DEFAULT_MAX_AGE_MILLIS);
	}

	static RoadCrewShadowOutbox open(File file, Clock clock, IdGenerator idGenerator,
			int maxRecords, long maxAgeMillis) throws IOException {
		if (file == null || clock == null || idGenerator == null
				|| maxRecords <= 0 || maxAgeMillis <= 0) {
			throw new IllegalArgumentException("Invalid shadow outbox configuration");
		}
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Cannot create the RoadCrew shadow outbox directory");
		}
		return new RoadCrewShadowOutbox(file, clock, idGenerator, maxRecords, maxAgeMillis,
				load(file));
	}

	/**
	 * Queues one serialised observation. A full queue drops its oldest record -
	 * never the newest, because the comparison is more useful when it follows
	 * the drive that is happening now.
	 */
	public synchronized void add(String pipeline, String comparisonGroupId, String payload)
			throws IOException {
		if (!PIPELINE_LEGACY.equals(pipeline) && !PIPELINE_DIRECT.equals(pipeline)) {
			throw new IllegalArgumentException("Unknown shadow pipeline: " + pipeline);
		}
		if (payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD_LENGTH) {
			throw new IllegalArgumentException("Invalid shadow observation payload");
		}
		long now = clock.nowMillis();
		Record record = new Record();
		record.id = idGenerator.nextId();
		record.pipeline = pipeline;
		record.comparisonGroupId = comparisonGroupId;
		record.payload = payload;
		record.createdAtMillis = now;
		record.attemptCount = 0;
		record.nextAttemptAtMillis = 0;
		records.add(record);
		if (prune(now)) {
			persist();
		} else {
			append(record);
		}
	}

	/** Whether the flush rule of section 168 says it is time to send. */
	public synchronized boolean shouldFlush(long nowMillis) {
		int waiting = 0;
		long oldest = Long.MAX_VALUE;
		for (Record record : records) {
			if (record.nextAttemptAtMillis <= nowMillis) {
				waiting++;
				oldest = Math.min(oldest, record.createdAtMillis);
			}
		}
		return waiting >= FLUSH_OBSERVATION_COUNT
				|| (waiting > 0 && nowMillis - oldest >= FLUSH_INTERVAL_MILLIS);
	}

	/**
	 * The next chunk to send. One batch never mixes the two pipelines: which
	 * one produced a chunk travels in the query string, not in the body.
	 */
	public synchronized Batch nextBatch(long nowMillis, int limit) throws IOException {
		String pipeline = null;
		List<Record> batch = new ArrayList<>();
		for (Record record : records) {
			if (record.nextAttemptAtMillis > nowMillis) {
				continue;
			}
			if (pipeline == null) {
				pipeline = record.pipeline;
			} else if (!pipeline.equals(record.pipeline)) {
				continue;
			}
			batch.add(record);
			if (batch.size() >= Math.max(1, Math.min(limit, MAX_BATCH_RECORDS))) {
				break;
			}
		}
		if (batch.isEmpty()) {
			return new Batch(PIPELINE_DIRECT, "", Collections.<Record>emptyList());
		}
		return new Batch(pipeline, batchId(batch), Collections.unmodifiableList(batch));
	}

	public synchronized void markUploaded(Collection<String> ids) throws IOException {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		Set<String> remove = new HashSet<>(ids);
		String pipeline = null;
		for (Iterator<Record> iterator = records.iterator(); iterator.hasNext(); ) {
			Record record = iterator.next();
			if (remove.contains(record.id)) {
				pipeline = record.pipeline;
				iterator.remove();
			}
		}
		if (PIPELINE_LEGACY.equals(pipeline)) {
			legacyFailures = 0;
		} else if (PIPELINE_DIRECT.equals(pipeline)) {
			directFailures = 0;
		}
		persist();
	}

	/**
	 * Leaves the records queued and backs off. The two branches count their own
	 * failures: a fault in the new pipeline must be visible as its own, not
	 * hidden in a total that the working branch keeps resetting.
	 */
	public synchronized void markFailed(Collection<String> ids, long nowMillis) throws IOException {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		Set<String> failed = new HashSet<>(ids);
		String pipeline = null;
		for (Record record : records) {
			if (!failed.contains(record.id)) {
				continue;
			}
			pipeline = record.pipeline;
			record.attemptCount = Math.min(record.attemptCount + 1, 1_000);
			record.nextAttemptAtMillis = nowMillis + backoffMillis(record.attemptCount);
		}
		if (PIPELINE_LEGACY.equals(pipeline)) {
			legacyFailures = Math.min(legacyFailures + 1, 1_000_000);
		} else if (PIPELINE_DIRECT.equals(pipeline)) {
			directFailures = Math.min(directFailures + 1, 1_000_000);
		}
		persist();
	}

	/** Throws the whole experiment away without touching anything else. */
	public synchronized void clear() throws IOException {
		records.clear();
		persist();
	}

	public synchronized List<Record> snapshot() {
		return Collections.unmodifiableList(new ArrayList<>(records));
	}

	public synchronized int pendingCount() {
		return records.size();
	}

	public synchronized int pendingCount(String pipeline) {
		int count = 0;
		for (Record record : records) {
			if (record.pipeline.equals(pipeline)) {
				count++;
			}
		}
		return count;
	}

	public synchronized int consecutiveFailures(String pipeline) {
		return PIPELINE_LEGACY.equals(pipeline) ? legacyFailures : directFailures;
	}

	public synchronized int droppedCount(String pipeline) {
		return PIPELINE_LEGACY.equals(pipeline) ? legacyDropped : directDropped;
	}

	private static long backoffMillis(int attemptCount) {
		long delay = RETRY_BASE_DELAY_MILLIS;
		for (int step = 1; step < attemptCount && delay < RETRY_MAX_DELAY_MILLIS; step++) {
			delay *= 2;
		}
		return Math.min(delay, RETRY_MAX_DELAY_MILLIS);
	}

	/** @return whether anything was removed */
	private boolean prune(long nowMillis) {
		boolean changed = false;
		for (Iterator<Record> iterator = records.iterator(); iterator.hasNext(); ) {
			Record record = iterator.next();
			if (nowMillis - record.createdAtMillis > maxAgeMillis) {
				countDrop(record);
				iterator.remove();
				changed = true;
			}
		}
		while (records.size() > maxRecords) {
			countDrop(records.remove(0));
			changed = true;
		}
		return changed;
	}

	private void countDrop(Record record) {
		if (PIPELINE_LEGACY.equals(record.pipeline)) {
			legacyDropped = Math.min(legacyDropped + 1, 1_000_000);
		} else {
			directDropped = Math.min(directDropped + 1, 1_000_000);
		}
	}

	/**
	 * The chunk id, from the record ids alone. Two attempts at the same records
	 * therefore land on the same object, which is what makes a retry safe.
	 */
	public static String batchId(List<Record> batch) throws IOException {
		List<String> ids = new ArrayList<>(batch.size());
		for (Record record : batch) {
			ids.add(record.id);
		}
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

	private static State load(File file) {
		State state = readState(file);
		if (state == null) {
			state = readState(new File(file.getParentFile(), file.getName() + ".bak"));
		}
		if (state == null) {
			state = new State();
		}
		if (state.records == null) {
			state.records = new ArrayList<>();
		}
		readLog(logFileFor(file), state.records, state.logGeneration);
		for (Iterator<Record> iterator = state.records.iterator(); iterator.hasNext(); ) {
			if (!iterator.next().valid()) {
				iterator.remove();
			}
		}
		return state;
	}

	/**
	 * The observations appended since the queue was last written whole.
	 *
	 * A log belongs to one state: its first line names the generation, and a log
	 * from an earlier one is what a crash between writing the state and dropping
	 * the log leaves behind. Reading it would bring back records that were sent or
	 * cleared, so it is ignored and removed (Codex 16.09, reproduced).
	 *
	 * The last line can be half-written - the phone died in the middle of it. It
	 * is dropped AND cut from the file: otherwise the next observation is appended
	 * behind the damage, and every later reading stops at the same place, losing
	 * everything written after it (Codex 16.09, reproduced).
	 */
	private static void readLog(File log, List<Record> records, int generation) {
		if (!log.isFile()) {
			return;
		}
		Set<String> known = new HashSet<>();
		for (Record record : records) {
			if (record != null && record.id != null) {
				known.add(record.id);
			}
		}
		byte[] bytes;
		try {
			bytes = readAll(log);
		} catch (IOException unreadable) {
			// The state file is the record of what was there; keep going.
			return;
		}
		String text = new String(bytes, StandardCharsets.UTF_8);
		int consumed = 0;
		boolean header = false;
		int start = 0;
		while (start < text.length()) {
			int end = text.indexOf('\n', start);
			if (end < 0) {
				// No newline: the phone stopped in the middle of this line.
				break;
			}
			String line = text.substring(start, end);
			int next = end + 1;
			if (!header) {
				header = true;
				if (!line.startsWith(GENERATION_PREFIX) || !matches(line, generation)) {
					// Not this state's log. Nothing in it may be believed.
					delete(log);
					return;
				}
				consumed = next;
				start = next;
				continue;
			}
			if (!line.isEmpty()) {
				try {
					Record record = GSON.fromJson(line, Record.class);
					if (record == null || !record.valid()) {
						break;
					}
					if (known.add(record.id)) {
						records.add(record);
					}
				} catch (JsonParseException torn) {
					break;
				}
			}
			consumed = next;
			start = next;
		}
		if (consumed < bytes.length) {
			truncate(log, consumed);
		}
	}

	private static boolean matches(String header, int generation) {
		try {
			return Integer.parseInt(header.substring(GENERATION_PREFIX.length()).trim()) == generation;
		} catch (NumberFormatException unreadable) {
			return false;
		}
	}

	private static byte[] readAll(File file) throws IOException {
		long length = file.length();
		if (length <= 0 || length > Integer.MAX_VALUE) {
			return new byte[0];
		}
		byte[] bytes = new byte[(int) length];
		try (FileInputStream input = new FileInputStream(file)) {
			int read = 0;
			while (read < bytes.length) {
				int chunk = input.read(bytes, read, bytes.length - read);
				if (chunk < 0) {
					byte[] shorter = new byte[read];
					System.arraycopy(bytes, 0, shorter, 0, read);
					return shorter;
				}
				read += chunk;
			}
		}
		return bytes;
	}

	/** Cuts the damage away, so the next observation is appended to sound bytes. */
	private static void truncate(File log, long length) {
		try (RandomAccessFile handle = new RandomAccessFile(log, "rw")) {
			handle.setLength(length);
			handle.getFD().sync();
		} catch (IOException stillDamaged) {
			// Better to keep reading what is sound than to lose the queue.
		}
	}

	private static void delete(File log) {
		if (log.isFile() && !log.delete()) {
			truncate(log, 0);
		}
	}

	private static State readState(File file) {
		if (file == null || !file.isFile()) {
			return null;
		}
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			State state = GSON.fromJson(reader, State.class);
			return state == null || state.schemaVersion != SCHEMA_VERSION ? null : state;
		} catch (IOException | JsonParseException e) {
			// A shadow queue that cannot be read is simply started again. It
			// holds nothing anyone is owed.
			return null;
		}
	}

	/** The head of a log names the state it belongs to. */
	private static final String GENERATION_PREFIX = "#generation ";

	public static File logFileFor(File file) {
		return new File(file.getParentFile(), file.getName() + ".log");
	}

	/** One observation, one line, and on the disk before this returns. */
	private void append(Record record) throws IOException {
		boolean fresh = !logFile.isFile() || logFile.length() == 0;
		try (FileOutputStream output = new FileOutputStream(logFile, true)) {
			Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
			if (fresh) {
				writer.write(GENERATION_PREFIX);
				writer.write(Integer.toString(logGeneration));
				writer.write("\n");
			}
			writer.write(GSON.toJson(record));
			writer.write("\n");
			writer.flush();
			output.getFD().sync();
		}
	}

	private void persist() throws IOException {
		logGeneration++;
		writeState();
		// Everything in the log is now inside the state file. Dropping it keeps
		// the next load from adding those records a second time.
		if (logFile.exists() && !logFile.delete()) {
			try (FileOutputStream truncate = new FileOutputStream(logFile)) {
				truncate.getFD().sync();
			}
		}
	}

	private void writeState() throws IOException {
		State state = new State();
		state.schemaVersion = SCHEMA_VERSION;
		state.logGeneration = logGeneration;
		state.records = records;
		state.legacyFailures = legacyFailures;
		state.directFailures = directFailures;
		state.legacyDropped = legacyDropped;
		state.directDropped = directDropped;

		try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
			BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(output, StandardCharsets.UTF_8));
			GSON.toJson(state, writer);
			writer.flush();
			FileDescriptor descriptor = output.getFD();
			descriptor.sync();
		}
		if (file.isFile()) {
			if (backupFile.isFile() && !backupFile.delete()) {
				throw new IOException("Cannot replace the RoadCrew shadow outbox backup");
			}
			if (!file.renameTo(backupFile)) {
				throw new IOException("Cannot roll the RoadCrew shadow outbox");
			}
		}
		if (!temporaryFile.renameTo(file)) {
			throw new IOException("Cannot commit the RoadCrew shadow outbox");
		}
	}
}
