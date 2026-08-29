package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RoadCrewObservationOutboxTest {

	private static final long BUCKET = RoadCrewObservationOutbox.OBSERVATION_BUCKET_MILLIS;
	private static final long BASE_TIME = 30L * 24 * 60 * 60 * 1_000;

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void localCaptureDoesNotQueueUntilConfirmedAndReplaysIdempotently() throws Exception {
		File file = observationFile("confirmed-import.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		RoadCrewObservationOutbox.Record local = RoadCrewObservationOutbox.Record.capture(
				evidence(3100, 43.0), BASE_TIME + 42_000);
		RoadCrewObservationOutbox.Record restored = RoadCrewObservationOutbox.Record.decode(local.encode());
		Assert.assertEquals(local.getId(), restored.getId());
		Assert.assertEquals(BASE_TIME, restored.getObservedAtBucketMillis());
		Assert.assertTrue(outbox.snapshot().isEmpty());
		Assert.assertEquals(1, outbox.importConfirmed(Collections.singletonList(restored)));
		Assert.assertEquals(1, outbox.importConfirmed(Collections.singletonList(restored)));
		Assert.assertEquals(1, outbox.snapshot().size());
		outbox.markUploaded(Collections.singleton(restored.getId()));
		RoadCrewObservationOutbox reopened = open(file, clock, new CounterIds());
		Assert.assertEquals(1, reopened.importConfirmed(Collections.singletonList(restored)));
		Assert.assertTrue(reopened.snapshot().isEmpty());
	}

	@Test
	public void confirmedBatchStopsAtCapacityWithoutEvictionAndPreservesRetryState() throws Exception {
		File file = observationFile("confirmed-capacity.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = RoadCrewObservationOutbox.open(file, clock, new CounterIds(), 2, 86400_000);
		RoadCrewObservationOutbox.Record first = RoadCrewObservationOutbox.Record.capture(evidence(3101, 43.0), BASE_TIME);
		RoadCrewObservationOutbox.Record second = RoadCrewObservationOutbox.Record.capture(evidence(3102, 43.0), BASE_TIME);
		RoadCrewObservationOutbox.Record third = RoadCrewObservationOutbox.Record.capture(evidence(3103, 43.0), BASE_TIME);
		Assert.assertEquals(2, outbox.importConfirmed(Arrays.asList(first, second, third)));
		outbox.markFailed(Collections.singleton(first.getId()), BASE_TIME);
		Assert.assertEquals(1, outbox.importConfirmed(Collections.singletonList(first)));
		Assert.assertEquals(1, outbox.snapshot().getRecords().stream()
				.filter(record -> first.getId().equals(record.getId()))
				.findFirst().orElseThrow(AssertionError::new).getAttemptCount());
		Assert.assertEquals(0, outbox.importConfirmed(Collections.singletonList(third)));
		Assert.assertTrue(outbox.snapshot().getRecords().stream()
				.anyMatch(record -> first.getId().equals(record.getId())));
		outbox.markUploaded(Collections.singleton(first.getId()));
		Assert.assertEquals(1, outbox.importConfirmed(Collections.singletonList(third)));
		Assert.assertEquals(2, open(file, clock, new CounterIds()).snapshot().size());
	}

	@Test
	public void confirmedBatchRejectsNullAtomicallyAndDeduplicatesDifferentIds() throws Exception {
		RoadCrewObservationOutbox outbox = open(observationFile("confirmed-validation.json"), new MutableClock(BASE_TIME), new CounterIds());
		RoadCrewObservationOutbox.Record first = RoadCrewObservationOutbox.Record.capture(evidence(3104, 43.0), BASE_TIME);
		RoadCrewObservationOutbox.Record duplicate = RoadCrewObservationOutbox.Record.capture(evidence(3104, 43.0), BASE_TIME + 1000);
		try {
			outbox.importConfirmed(Arrays.asList(first, null));
			Assert.fail("Null batch entry must fail closed");
		} catch (IllegalArgumentException expected) {
			Assert.assertTrue(outbox.snapshot().isEmpty());
		}
		Assert.assertEquals(2, outbox.importConfirmed(Arrays.asList(first, duplicate)));
		Assert.assertEquals(1, outbox.snapshot().size());
	}

	@Test
	public void persistsMinimalEvidenceAcrossRestart() throws Exception {
		File file = temporaryFolder.newFile("observations.json");
		Assert.assertTrue(file.delete());
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());

		RoadCrewObservationOutbox.EnqueueResult result = outbox.enqueue(evidence(3001, 43.0), BASE_TIME + 42_000);

		Assert.assertEquals(RoadCrewObservationOutbox.EnqueueStatus.ADDED, result.getStatus());
		Assert.assertEquals(1, outbox.snapshot().size());
		RoadCrewObservationOutbox reopened = open(file, clock, new CounterIds());
		RoadCrewObservationOutbox.Record record = reopened.snapshot().getRecords().get(0);
		Assert.assertEquals("observation-1", record.getId());
		Assert.assertEquals(BASE_TIME, record.getObservedAtBucketMillis());
		Assert.assertEquals(3, record.getFixCount());
		Assert.assertEquals(2_000, record.getDurationMillis());
		Assert.assertEquals(result.getRecord().getSegmentKey().getCanonicalId(),
				record.getSegmentKey().getCanonicalId());
		Assert.assertEquals(result.getRecord().getSegmentKey().getGeometryFingerprint(),
				record.getSegmentKey().getGeometryFingerprint());

		String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		Assert.assertFalse(json.contains("driverId"));
		Assert.assertFalse(json.contains("vehicleId"));
		Assert.assertFalse(json.contains("gpsFix"));
		Assert.assertFalse(json.contains("rawPoints"));
	}

	@Test
	public void deduplicatesSameSegmentAndTimeBucket() throws Exception {
		File file = observationFile("dedupe.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		RoadCrewPassageDetector.PassageEvidence evidence = evidence(3002, 43.0);

		RoadCrewObservationOutbox.EnqueueResult first = outbox.enqueue(evidence, BASE_TIME + 1_000);
		RoadCrewObservationOutbox.EnqueueResult duplicate = outbox.enqueue(evidence, BASE_TIME + 50_000);
		RoadCrewObservationOutbox.EnqueueResult nextBucket = outbox.enqueue(evidence, BASE_TIME + BUCKET + 1_000);

		Assert.assertEquals(RoadCrewObservationOutbox.EnqueueStatus.ADDED, first.getStatus());
		Assert.assertEquals(RoadCrewObservationOutbox.EnqueueStatus.DEDUPLICATED, duplicate.getStatus());
		Assert.assertEquals(first.getRecord().getId(), duplicate.getRecord().getId());
		Assert.assertEquals(RoadCrewObservationOutbox.EnqueueStatus.ADDED, nextBucket.getStatus());
		Assert.assertEquals(2, outbox.snapshot().size());
	}

	@Test
	public void retryStateAndBackoffSurviveRestart() throws Exception {
		File file = observationFile("retry.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String id = outbox.enqueue(evidence(3003, 43.0), BASE_TIME).getRecord().getId();

		Assert.assertEquals(1, outbox.getEligibleBatch(BASE_TIME, 10).size());
		Assert.assertEquals(1, outbox.markFailed(Collections.singleton(id), BASE_TIME));
		long firstRetry = BASE_TIME + RoadCrewObservationOutbox.RETRY_BASE_DELAY_MILLIS;
		Assert.assertTrue(outbox.getEligibleBatch(firstRetry - 1, 10).isEmpty());

		RoadCrewObservationOutbox reopened = open(file, clock, new CounterIds());
		Assert.assertEquals(1, reopened.getEligibleBatch(firstRetry, 10).size());
		Assert.assertEquals(1, reopened.markFailed(Collections.singleton(id), firstRetry));
		RoadCrewObservationOutbox.Record failedTwice = reopened.snapshot().getRecords().get(0);
		Assert.assertEquals(2, failedTwice.getAttemptCount());
		Assert.assertEquals(firstRetry + 2 * RoadCrewObservationOutbox.RETRY_BASE_DELAY_MILLIS,
				failedTwice.getNextAttemptAtMillis());
	}

	@Test
	public void resetRetryScheduleMakesBlockedRecordsImmediatelyEligible() throws Exception {
		File file = observationFile("retry-reset.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String id = outbox.enqueue(evidence(3009, 43.0), BASE_TIME).getRecord().getId();
		outbox.markFailed(Collections.singleton(id), BASE_TIME);
		Assert.assertTrue(outbox.getEligibleBatch(BASE_TIME, 10).isEmpty());

		Assert.assertEquals(1, outbox.resetRetrySchedule());
		RoadCrewObservationOutbox reopened = open(file, clock, new CounterIds());
		Assert.assertEquals(1, reopened.getEligibleBatch(BASE_TIME, 10).size());
		Assert.assertEquals(0, reopened.snapshot().getRecords().get(0).getAttemptCount());
	}

	@Test
	public void immediateConnectivityRetryPreservesBackoffHistory() throws Exception {
		File file = observationFile("retry-connectivity.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String id = outbox.enqueue(evidence(3010, 43.0), BASE_TIME).getRecord().getId();
		outbox.markFailed(Collections.singleton(id), BASE_TIME);

		Assert.assertEquals(1, outbox.makeRetryRecordsEligibleNow());
		RoadCrewObservationOutbox.Record record = open(file, clock, new CounterIds())
				.snapshot().getRecords().get(0);
		Assert.assertEquals(1, record.getAttemptCount());
		Assert.assertEquals(0, record.getNextAttemptAtMillis());
	}

	@Test
	public void retryBackoffIsCappedAtFifteenMinutes() throws Exception {
		File file = observationFile("retry-cap.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String id = outbox.enqueue(evidence(3011, 43.0), BASE_TIME).getRecord().getId();
		long failedAt = BASE_TIME;

		for (int attempt = 1; attempt <= 12; attempt++) {
			outbox.markFailed(Collections.singleton(id), failedAt);
			RoadCrewObservationOutbox.Record record = outbox.snapshot().getRecords().get(0);
			long delay = record.getNextAttemptAtMillis() - failedAt;
			Assert.assertTrue(delay <= RoadCrewObservationOutbox.RETRY_MAX_DELAY_MILLIS);
			if (attempt >= 6) {
				Assert.assertEquals(RoadCrewObservationOutbox.RETRY_MAX_DELAY_MILLIS, delay);
			}
			failedAt = record.getNextAttemptAtMillis();
		}
	}

	@Test
	public void uploadedRecordsAreRemovedDurably() throws Exception {
		File file = observationFile("uploaded.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String first = outbox.enqueue(evidence(3004, 43.0), BASE_TIME).getRecord().getId();
		outbox.enqueue(evidence(3005, 43.001), BASE_TIME);

		Assert.assertEquals(1, outbox.markUploaded(Collections.singleton(first)));
		Assert.assertEquals(1, outbox.snapshot().size());
		Assert.assertEquals(1, open(file, clock, new CounterIds()).snapshot().size());
	}

	@Test
	public void rejectedRecordDoesNotBlockOrRequeueSameEvidence() throws Exception {
		File file = observationFile("rejected.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewPassageDetector.PassageEvidence rejectedEvidence = evidence(3007, 43.0);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String rejectedId = outbox.enqueue(rejectedEvidence, BASE_TIME + 1_000)
				.getRecord().getId();
		outbox.enqueue(evidence(3008, 43.001), BASE_TIME + 1_000);

		Assert.assertEquals(1, outbox.markRejected(Collections.singleton(rejectedId)));
		Assert.assertEquals(1, outbox.snapshot().size());
		RoadCrewObservationOutbox reopened = open(file, clock, new CounterIds());
		RoadCrewObservationOutbox.EnqueueResult duplicate = reopened.enqueue(
				rejectedEvidence, BASE_TIME + 50_000);

		Assert.assertEquals(RoadCrewObservationOutbox.EnqueueStatus.ALREADY_UPLOADED,
				duplicate.getStatus());
		Assert.assertEquals(1, reopened.snapshot().size());
	}

	@Test
	public void acknowledgedSegmentBucketIsNotQueuedAgainAfterRestart() throws Exception {
		File file = observationFile("acknowledged.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewPassageDetector.PassageEvidence evidence = evidence(3006, 43.0);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		String id = outbox.enqueue(evidence, BASE_TIME + 1_000).getRecord().getId();

		Assert.assertEquals(1, outbox.markUploaded(Collections.singleton(id)));
		RoadCrewObservationOutbox reopened = open(file, clock, new CounterIds());
		RoadCrewObservationOutbox.EnqueueResult duplicate = reopened.enqueue(
				evidence, BASE_TIME + 50_000);

		Assert.assertEquals(RoadCrewObservationOutbox.EnqueueStatus.ALREADY_UPLOADED,
				duplicate.getStatus());
		Assert.assertTrue(reopened.snapshot().isEmpty());
	}

	@Test
	public void enforcesCountAndAgeRetention() throws Exception {
		File file = observationFile("retention.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		CounterIds ids = new CounterIds();
		RoadCrewObservationOutbox outbox = RoadCrewObservationOutbox.open(file, clock, ids, 3, 2 * 24 * 60 * 60 * 1_000L);

		outbox.enqueue(evidence(3010, 43.000), BASE_TIME - 3 * BUCKET);
		outbox.enqueue(evidence(3011, 43.001), BASE_TIME - 2 * BUCKET);
		outbox.enqueue(evidence(3012, 43.002), BASE_TIME - BUCKET);
		outbox.enqueue(evidence(3013, 43.003), BASE_TIME);

		List<RoadCrewObservationOutbox.Record> retained = outbox.snapshot().getRecords();
		Assert.assertEquals(3, retained.size());
		Assert.assertEquals("observation-2", retained.get(0).getId());
		clock.now = BASE_TIME + 3 * 24 * 60 * 60 * 1_000L;
		RoadCrewObservationOutbox expired = RoadCrewObservationOutbox.open(file, clock,
				new CounterIds(), 3, 2 * 24 * 60 * 60 * 1_000L);
		Assert.assertTrue(expired.snapshot().isEmpty());
		Assert.assertTrue(RoadCrewObservationOutbox.open(file, clock, new CounterIds(), 3,
				2 * 24 * 60 * 60 * 1_000L).snapshot().isEmpty());
	}

	@Test
	public void recoversFromPreviousValidSnapshotWhenPrimaryIsCorrupt() throws Exception {
		File file = observationFile("recover.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		outbox.enqueue(evidence(3020, 43.0), BASE_TIME);
		outbox.enqueue(evidence(3021, 43.001), BASE_TIME + BUCKET);
		writeCorrupt(file);

		RoadCrewObservationOutbox recovered = open(file, clock, new CounterIds());

		Assert.assertEquals(1, recovered.snapshot().size());
		Assert.assertEquals("observation-1", recovered.snapshot().getRecords().get(0).getId());
		Assert.assertEquals(1, open(file, clock, new CounterIds()).snapshot().size());
	}

	@Test
	public void failsClosedWhenEverySnapshotIsCorrupt() throws Exception {
		File file = observationFile("all-corrupt.json");
		MutableClock clock = new MutableClock(BASE_TIME);
		RoadCrewObservationOutbox outbox = open(file, clock, new CounterIds());
		outbox.enqueue(evidence(3030, 43.0), BASE_TIME);
		outbox.enqueue(evidence(3031, 43.001), BASE_TIME + BUCKET);
		writeCorrupt(file);
		writeCorrupt(new File(file.getPath() + ".bak"));

		try {
			open(file, clock, new CounterIds());
			Assert.fail("Expected corrupt outbox to fail closed");
		} catch (IOException expected) {
			Assert.assertTrue(expected.getMessage().contains("No valid RoadCrew observation"));
		}
	}

	private File observationFile(String name) throws IOException {
		File file = temporaryFolder.newFile(name);
		Assert.assertTrue(file.delete());
		return file;
	}

	private static RoadCrewObservationOutbox open(File file, MutableClock clock,
			CounterIds ids) throws IOException {
		return RoadCrewObservationOutbox.open(file, clock, ids);
	}

	private static void writeCorrupt(File file) throws IOException {
		try (FileOutputStream stream = new FileOutputStream(file, false)) {
			stream.write("{broken".getBytes(StandardCharsets.UTF_8));
			stream.getFD().sync();
		}
	}

	private static RoadCrewPassageDetector.PassageEvidence evidence(long osmWayId, double latitude) {
		RoadCrewSegmentMatcher.PreparedSegments prepared = RoadCrewSegmentMatcher.prepare(
				Collections.singletonList(road(osmWayId, latitude)));
		RoadCrewPassageDetector detector = new RoadCrewPassageDetector();
		detector.accept(match(prepared, latitude, 27.0020), 1_000);
		detector.accept(match(prepared, latitude, 27.0022), 2_000);
		RoadCrewPassageDetector.DetectionResult result = detector.accept(
				match(prepared, latitude, 27.0024), 3_000);
		Assert.assertTrue(result.isConfirmed());
		return result.getEvidence();
	}

	private static RoadCrewSegmentMatcher.MatchResult match(
			RoadCrewSegmentMatcher.PreparedSegments prepared, double latitude, double longitude) {
		RoadCrewSegmentMatcher.MatchResult result = prepared.match(
				new RoadCrewSegmentMatcher.GpsFix(latitude, longitude, 3, 15, 90));
		Assert.assertTrue(result.isMatched());
		return result;
	}

	private static RouteDataObject road(long osmWayId, double latitude) {
		RouteRegion region = new RouteRegion();
		region.setName("Bulgaria");
		RouteDataObject road = new RouteDataObject(region);
		road.id = osmWayId << 6;
		road.types = new int[0];
		road.pointsX = new int[]{MapUtils.get31TileNumberX(27.0000), MapUtils.get31TileNumberX(27.0100)};
		road.pointsY = new int[]{MapUtils.get31TileNumberY(latitude), MapUtils.get31TileNumberY(latitude)};
		return road;
	}

	private static final class MutableClock implements RoadCrewObservationOutbox.Clock {
		private long now;

		private MutableClock(long now) {
			this.now = now;
		}

		@Override
		public long nowMillis() {
			return now;
		}
	}

	private static final class CounterIds implements RoadCrewObservationOutbox.IdGenerator {
		private int next = 1;

		@Override
		public String nextId() {
			return "observation-" + next++;
		}
	}
}
