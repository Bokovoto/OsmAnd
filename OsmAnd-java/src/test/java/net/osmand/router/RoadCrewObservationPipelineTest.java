package net.osmand.router;

import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.RouteDataObject;
import net.osmand.util.MapUtils;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;

public class RoadCrewObservationPipelineTest {

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void queuesOnlyAfterConfirmedPassage() throws Exception {
		RoadCrewObservationOutbox outbox = outbox("confirmed.json");
		RoadCrewObservationPipeline pipeline = new RoadCrewObservationPipeline(outbox);
		Assert.assertEquals(2, pipeline.replaceRoads(Collections.singletonList(road(4001, 43.0))));

		RoadCrewObservationPipeline.ProcessingResult first = pipeline.accept(fix(43.0, 27.0020), 1_000, 9_000_000);
		RoadCrewObservationPipeline.ProcessingResult second = pipeline.accept(fix(43.0, 27.0022), 2_000, 9_001_000);
		RoadCrewObservationPipeline.ProcessingResult third = pipeline.accept(fix(43.0, 27.0024), 3_000, 9_002_000);

		Assert.assertFalse(first.wasQueued());
		Assert.assertFalse(second.wasQueued());
		Assert.assertTrue(third.wasQueued());
		Assert.assertEquals(RoadCrewPassageDetector.Status.CONFIRMED,
				third.getDetection().getStatus());
		Assert.assertEquals(1, outbox.snapshot().size());
	}

	@Test
	public void noCandidatesAndResetCannotCreateEvidence() throws Exception {
		RoadCrewObservationOutbox outbox = outbox("reset.json");
		RoadCrewObservationPipeline pipeline = new RoadCrewObservationPipeline(outbox);

		RoadCrewObservationPipeline.ProcessingResult empty = pipeline.accept(
				fix(43.0, 27.0020), 1_000, 9_000_000);
		Assert.assertEquals(RoadCrewSegmentMatcher.Status.NO_SEGMENTS, empty.getMatch().getStatus());

		pipeline.replaceRoads(Collections.singletonList(road(4002, 43.0)));
		pipeline.accept(fix(43.0, 27.0020), 2_000, 9_001_000);
		pipeline.accept(fix(43.0, 27.0022), 3_000, 9_002_000);
		pipeline.reset();
		RoadCrewObservationPipeline.ProcessingResult afterReset = pipeline.accept(
				fix(43.0, 27.0024), 4_000, 9_003_000);

		Assert.assertEquals(RoadCrewSegmentMatcher.Status.NO_SEGMENTS,
				afterReset.getMatch().getStatus());
		Assert.assertTrue(outbox.snapshot().isEmpty());
	}

	private RoadCrewObservationOutbox outbox(String name) throws Exception {
		File file = temporaryFolder.newFile(name);
		Assert.assertTrue(file.delete());
		return RoadCrewObservationOutbox.open(file, () -> 9_000_000,
				new RoadCrewObservationOutbox.IdGenerator() {
					private int next = 1;

					@Override
					public String nextId() {
						return "pipeline-" + next++;
					}
				});
	}

	private static RoadCrewSegmentMatcher.GpsFix fix(double latitude, double longitude) {
		return new RoadCrewSegmentMatcher.GpsFix(latitude, longitude, 3, 15, 90);
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
}
