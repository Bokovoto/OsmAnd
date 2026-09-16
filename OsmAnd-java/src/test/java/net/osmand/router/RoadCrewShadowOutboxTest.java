package net.osmand.router;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/**
 * The comparison queue must survive the phone being killed, must never grow
 * without bound, and must not let one branch hide the other's failures.
 */
public class RoadCrewShadowOutboxTest {

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private long now = 1_757_000_000_000L;

	@Test
	public void whatWasQueuedSurvivesBeingReopened() throws Exception {
		File file = queueFile("survives.json");
		RoadCrewShadowOutbox outbox = open(file);
		outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"one\"}");
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"two\"}");

		RoadCrewShadowOutbox reopened = open(file);

		Assert.assertEquals(2, reopened.pendingCount());
		Assert.assertEquals(1, reopened.pendingCount(RoadCrewShadowOutbox.PIPELINE_LEGACY));
		Assert.assertEquals(1, reopened.pendingCount(RoadCrewShadowOutbox.PIPELINE_DIRECT));
		Assert.assertEquals("{\"id\":\"one\"}", reopened.snapshot().get(0).getPayload());
	}

	@Test
	public void aBatchNeverMixesTheTwoPipelines() throws Exception {
		RoadCrewShadowOutbox outbox = open(queueFile("split.json"));
		outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"l1\"}");
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d1\"}");
		outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"l2\"}");

		RoadCrewShadowOutbox.Batch batch = outbox.nextBatch(now, 100);

		Assert.assertEquals(RoadCrewShadowOutbox.PIPELINE_LEGACY, batch.getPipeline());
		Assert.assertEquals(2, batch.getRecords().size());
		for (RoadCrewShadowOutbox.Record record : batch.getRecords()) {
			Assert.assertEquals(RoadCrewShadowOutbox.PIPELINE_LEGACY, record.getPipeline());
		}

		outbox.markUploaded(batch.getIds());
		RoadCrewShadowOutbox.Batch second = outbox.nextBatch(now, 100);
		Assert.assertEquals(RoadCrewShadowOutbox.PIPELINE_DIRECT, second.getPipeline());
	}

	@Test
	public void theSameRecordsAlwaysProduceTheSameChunkId() throws Exception {
		RoadCrewShadowOutbox outbox = open(queueFile("idempotent.json"));
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d1\"}");
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d2\"}");

		String first = outbox.nextBatch(now, 100).getBatchId();
		// The send failed and is tried again later: the server must see the same
		// chunk, overwriting what it has, never counting the drive twice.
		outbox.markFailed(outbox.nextBatch(now, 100).getIds(), now);
		String retry = outbox.nextBatch(now + 60 * 60_000L, 100).getBatchId();

		Assert.assertEquals(first, retry);
		Assert.assertEquals(64, first.length());
		Assert.assertTrue(first.matches("[0-9a-f]{64}"));
	}

	@Test
	public void aFullQueueDropsTheOldestAndCountsIt() throws Exception {
		RoadCrewShadowOutbox outbox = RoadCrewShadowOutbox.open(queueFile("bounded.json"),
				() -> now, ids(), 3, RoadCrewShadowOutbox.DEFAULT_MAX_AGE_MILLIS);
		for (int index = 1; index <= 5; index++) {
			outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"n\":" + index + "}");
		}

		Assert.assertEquals(3, outbox.pendingCount());
		Assert.assertEquals("{\"n\":3}", outbox.snapshot().get(0).getPayload());
		Assert.assertEquals("the newest observations are the ones worth keeping",
				"{\"n\":5}", outbox.snapshot().get(2).getPayload());
		Assert.assertEquals(2, outbox.droppedCount(RoadCrewShadowOutbox.PIPELINE_DIRECT));
		Assert.assertEquals(0, outbox.droppedCount(RoadCrewShadowOutbox.PIPELINE_LEGACY));
	}

	@Test
	public void eachBranchCountsItsOwnFailures() throws Exception {
		RoadCrewShadowOutbox outbox = open(queueFile("failures.json"));
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d1\"}");
		outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"l1\"}");

		String directId = outbox.snapshot().get(0).getId();
		String legacyId = outbox.snapshot().get(1).getId();
		outbox.markFailed(Collections.singletonList(directId), now);
		outbox.markFailed(Collections.singletonList(directId), now);
		outbox.markUploaded(Collections.singletonList(legacyId));

		Assert.assertEquals(2, outbox.consecutiveFailures(RoadCrewShadowOutbox.PIPELINE_DIRECT));
		Assert.assertEquals("a working branch must not clear the broken one",
				0, outbox.consecutiveFailures(RoadCrewShadowOutbox.PIPELINE_LEGACY));
	}

	@Test
	public void aFailedRecordWaitsAndTheOtherBranchGoesOnWithoutIt() throws Exception {
		RoadCrewShadowOutbox outbox = open(queueFile("backoff.json"));
		outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"l1\"}");
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d1\"}");

		outbox.markFailed(outbox.nextBatch(now, 100).getIds(), now);

		RoadCrewShadowOutbox.Batch next = outbox.nextBatch(now + 1_000, 100);
		Assert.assertEquals(RoadCrewShadowOutbox.PIPELINE_DIRECT, next.getPipeline());
		Assert.assertEquals(1, next.getRecords().size());
	}

	@Test
	public void theFlushRuleIsTwentyObservationsOrTwoMinutes() throws Exception {
		RoadCrewShadowOutbox outbox = open(queueFile("flush.json"));
		Assert.assertFalse("nothing waiting is not a reason to send", outbox.shouldFlush(now));

		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d1\"}");
		Assert.assertFalse(outbox.shouldFlush(now));
		Assert.assertTrue("the oldest has waited long enough",
				outbox.shouldFlush(now + RoadCrewShadowOutbox.FLUSH_INTERVAL_MILLIS));

		for (int index = 1; index < RoadCrewShadowOutbox.FLUSH_OBSERVATION_COUNT; index++) {
			outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"n\":" + index + "}");
		}
		Assert.assertTrue("twenty waiting is a reason on its own", outbox.shouldFlush(now));
	}

	@Test
	public void aTornOrNonsensicalFileCostsNothingButTheSample() throws Exception {
		File file = queueFile("torn.json");
		RoadCrewShadowOutbox outbox = open(file);
		outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"d1\"}");
		java.nio.file.Files.write(file.toPath(), "not json at all".getBytes("UTF-8"));

		// The backup still holds the state before the last write, so reopening
		// recovers rather than starting empty; either way it must not throw.
		RoadCrewShadowOutbox reopened = open(file);
		Assert.assertTrue(reopened.pendingCount() >= 0);
		reopened.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-b", "{\"id\":\"d2\"}");
		Assert.assertTrue(reopened.pendingCount() >= 1);
	}

	@Test
	public void anUnknownPipelineIsRefusedOutright() throws Exception {
		RoadCrewShadowOutbox outbox = open(queueFile("unknown.json"));
		for (String pipeline : Arrays.asList("RCS3", "", "rcs2")) {
			try {
				outbox.add(pipeline, "group-a", "{\"id\":\"x\"}");
				Assert.fail("accepted " + pipeline);
			} catch (IllegalArgumentException expected) {
				// The two branches are the experiment; a third would be untracked.
			}
		}
		Assert.assertEquals(0, outbox.pendingCount());
	}

	/**
	 * Measured on the server, 16.09: a phone driving queues roughly fifty
	 * observations a minute. Rewriting the whole queue for each of them is what
	 * the phone pays for holding data longer before sending - so queueing must
	 * not touch the records already written.
	 */
	@Test
	public void queueingOneObservationDoesNotRewriteTheOnesBeforeIt() throws Exception {
		File file = queueFile("append.json");
		RoadCrewShadowOutbox outbox = open(file);
		outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"first\"}");
		// A queue that has never been sent has no written-whole file yet: the
		// observations are only in the log. Either way, what is already written
		// must not be touched again by the observations that follow.
		byte[] afterFirst = file.isFile() ? Files.readAllBytes(file.toPath()) : new byte[0];

		for (int index = 0; index < 50; index++) {
			outbox.add(RoadCrewShadowOutbox.PIPELINE_DIRECT, "group-a", "{\"id\":\"n" + index + "\"}");
		}

		Assert.assertArrayEquals("the records already written are left alone",
				afterFirst, file.isFile() ? Files.readAllBytes(file.toPath()) : new byte[0]);
		Assert.assertEquals(51, open(file).pendingCount());
	}

	@Test
	public void aQueueCutOffMidWriteKeepsEverythingBeforeIt() throws Exception {
		File file = queueFile("torn.json");
		RoadCrewShadowOutbox outbox = open(file);
		for (int index = 0; index < 5; index++) {
			outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"n" + index + "\"}");
		}
		File log = new File(file.getParentFile(), file.getName() + ".log");
		Assert.assertTrue("the additions are appended, not rewritten", log.isFile());
		byte[] bytes = Files.readAllBytes(log.toPath());
		// The phone died halfway through writing the last observation.
		Files.write(log.toPath(), Arrays.copyOf(bytes, bytes.length - 12));

		Assert.assertEquals("the torn one is dropped, the rest survive", 4, open(file).pendingCount());
	}

	@Test
	public void sendingCompactsTheQueueBackIntoOneFile() throws Exception {
		File file = queueFile("compact.json");
		RoadCrewShadowOutbox outbox = open(file);
		for (int index = 0; index < 4; index++) {
			outbox.add(RoadCrewShadowOutbox.PIPELINE_LEGACY, "group-a", "{\"id\":\"n" + index + "\"}");
		}
		RoadCrewShadowOutbox.Batch batch = outbox.nextBatch(now, 2);
		outbox.markUploaded(batch.getIds());

		File log = new File(file.getParentFile(), file.getName() + ".log");
		Assert.assertTrue("what was sent is not left in the log", !log.isFile() || log.length() == 0);
		Assert.assertEquals(2, open(file).pendingCount());
		Assert.assertTrue("the queue file holds what is left",
				new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("n2"));
	}

	private File queueFile(String name) throws Exception {
		File file = new File(temporaryFolder.getRoot(), name);
		Assert.assertTrue(!file.exists() || file.delete());
		return file;
	}

	private RoadCrewShadowOutbox open(File file) throws Exception {
		return RoadCrewShadowOutbox.open(file, () -> now, ids());
	}

	private static RoadCrewShadowOutbox.IdGenerator ids() {
		return new RoadCrewShadowOutbox.IdGenerator() {
			private int next = 1;

			@Override
			public String nextId() {
				return "shadow-" + next++;
			}
		};
	}
}
