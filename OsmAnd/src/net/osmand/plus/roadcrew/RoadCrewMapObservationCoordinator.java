package net.osmand.plus.roadcrew;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.plus.OsmAndLocationProvider.OsmAndLocationListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.resources.BinaryMapReaderResource;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResourceType;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RoadCrewObfSegmentLoader;
import net.osmand.router.RoadCrewObservationOutbox;
import net.osmand.router.RoadCrewObservationPipeline;
import net.osmand.router.RoadCrewSegmentMatcher;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground-only bridge from accepted GPS fixes to the local, aggregate
 * RoadCrew passage outbox. Raw fixes never leave this coordinator.
 */
final class RoadCrewMapObservationCoordinator implements OsmAndLocationListener {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewMapObservationCoordinator.class);
	private static final double LOAD_RADIUS_METERS = 900;
	private static final double RELOAD_DISTANCE_METERS = 350;
	private static final long RELOAD_INTERVAL_MILLIS = 60_000;
	private static final int MAX_ROUTE_OBJECTS = 8_000;

	private final OsmandApplication app;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final AtomicReference<LocationSample> latestSample = new AtomicReference<>();
	private final AtomicBoolean drainScheduled = new AtomicBoolean();
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final AtomicInteger stateGeneration = new AtomicInteger();

	@Nullable
	private RoadCrewObservationPipeline pipeline;
	private volatile boolean enabled;
	private volatile boolean listening;
	private double loadedLatitude = Double.NaN;
	private double loadedLongitude = Double.NaN;
	private long loadedAtElapsedMillis;

	RoadCrewMapObservationCoordinator(@NonNull OsmandApplication app) {
		this.app = app;
	}

	void start() {
		setEnabled(RoadCrewMapObservationConsent.isEnabled(app));
	}

	void setEnabled(boolean enabled) {
		int generation = stateGeneration.incrementAndGet();
		this.enabled = enabled;
		if (enabled) {
			cancelled.set(false);
			if (!listening) {
				listening = true;
				app.getLocationProvider().addLocationListener(this);
			}
		} else {
			stopListening();
			latestSample.set(null);
			cancelled.set(true);
			executor.execute(() -> {
				resetPipeline();
				if (!this.enabled && stateGeneration.get() == generation) {
					RoadCrewMapObservationConsent.deleteLocalObservations(app);
				}
			});
		}
	}

	void shutdown() {
		stateGeneration.incrementAndGet();
		enabled = false;
		cancelled.set(true);
		stopListening();
		latestSample.set(null);
		executor.shutdownNow();
	}

	@Override
	public void updateLocation(Location location) {
		if (!enabled || location == null || !location.hasAccuracy()
				|| !location.hasSpeed() || !location.hasBearing()
				|| app.getLocationProvider().getLocationSimulation().isRouteAnimating()
				|| !app.getSettings().getApplicationMode().isDerivedRoutingFrom(ApplicationMode.TRUCK)) {
			return;
		}
		LocationSample sample = new LocationSample(location.getLatitude(), location.getLongitude(),
				location.getAccuracy(), location.getSpeed(), location.getBearing(),
				SystemClock.elapsedRealtime(), System.currentTimeMillis());
		latestSample.set(sample);
		scheduleDrain();
	}

	private void scheduleDrain() {
		if (drainScheduled.compareAndSet(false, true)) {
			executor.execute(this::drainLatestSamples);
		}
	}

	private void drainLatestSamples() {
		try {
			while (enabled && !Thread.currentThread().isInterrupted()) {
				LocationSample sample = latestSample.getAndSet(null);
				if (sample == null) {
					break;
				}
				process(sample);
			}
		} finally {
			drainScheduled.set(false);
			if (enabled && latestSample.get() != null) {
				scheduleDrain();
			}
		}
	}

	private void process(@NonNull LocationSample sample) {
		try {
			RoadCrewObservationPipeline currentPipeline = ensurePipeline();
			if (needsRoadReload(sample)) {
				if (!reloadRoads(currentPipeline, sample)) {
					return;
				}
			}
			currentPipeline.accept(new RoadCrewSegmentMatcher.GpsFix(sample.latitude, sample.longitude,
					sample.accuracyMeters, sample.speedMetersPerSecond, sample.bearingDegrees),
					sample.elapsedRealtimeMillis, sample.wallTimeMillis);
		} catch (IOException | RuntimeException e) {
			LOG.error("RoadCrew Live Truck Map observation failed closed", e);
			resetPipeline();
		}
	}

	@NonNull
	private RoadCrewObservationPipeline ensurePipeline() throws IOException {
		if (pipeline == null) {
			pipeline = new RoadCrewObservationPipeline(RoadCrewObservationOutbox.open(
					RoadCrewMapObservationConsent.getOutboxFile(app)));
		}
		return pipeline;
	}

	private boolean needsRoadReload(@NonNull LocationSample sample) {
		return !Double.isFinite(loadedLatitude)
				|| sample.elapsedRealtimeMillis - loadedAtElapsedMillis >= RELOAD_INTERVAL_MILLIS
				|| MapUtils.getDistance(loadedLatitude, loadedLongitude,
					sample.latitude, sample.longitude) >= RELOAD_DISTANCE_METERS;
	}

	private boolean reloadRoads(@NonNull RoadCrewObservationPipeline currentPipeline,
			@NonNull LocationSample sample) throws IOException {
		RoadCrewObfSegmentLoader.LoadResult loaded = RoadCrewObfSegmentLoader.load(
				getRoutingReaders(), sample.latitude, sample.longitude, LOAD_RADIUS_METERS,
				MAX_ROUTE_OBJECTS, () -> cancelled.get() || !enabled || Thread.currentThread().isInterrupted());
		if (loaded.isCancelled() || loaded.isTruncated()) {
			currentPipeline.reset();
			loadedLatitude = Double.NaN;
			return false;
		}
		currentPipeline.replaceRoads(loaded.getRouteObjects());
		loadedLatitude = sample.latitude;
		loadedLongitude = sample.longitude;
		loadedAtElapsedMillis = sample.elapsedRealtimeMillis;
		return true;
	}

	@NonNull
	private BinaryMapIndexReader[] getRoutingReaders() {
		List<BinaryMapIndexReader> readers = new ArrayList<>();
		for (BinaryMapReaderResource resource : app.getResourceManager().getFileReaders()) {
			if (!resource.isUseForRouting()) {
				continue;
			}
			BinaryMapIndexReader reader = resource.getReader(
					BinaryMapReaderResourceType.ROADCREW_MAP_OBSERVATION, false);
			if (reader != null && reader.containsRouteData()) {
				readers.add(reader);
			}
		}
		return readers.toArray(new BinaryMapIndexReader[0]);
	}

	private void resetPipeline() {
		if (pipeline != null) {
			pipeline.reset();
			pipeline = null;
		}
		loadedLatitude = Double.NaN;
		loadedLongitude = Double.NaN;
		loadedAtElapsedMillis = 0;
	}

	private void stopListening() {
		if (listening) {
			listening = false;
			app.getLocationProvider().removeLocationListener(this);
		}
	}

	private static final class LocationSample {
		private final double latitude;
		private final double longitude;
		private final double accuracyMeters;
		private final double speedMetersPerSecond;
		private final double bearingDegrees;
		private final long elapsedRealtimeMillis;
		private final long wallTimeMillis;

		private LocationSample(double latitude, double longitude, double accuracyMeters,
				double speedMetersPerSecond, double bearingDegrees,
				long elapsedRealtimeMillis, long wallTimeMillis) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.accuracyMeters = accuracyMeters;
			this.speedMetersPerSecond = speedMetersPerSecond;
			this.bearingDegrees = bearingDegrees;
			this.elapsedRealtimeMillis = elapsedRealtimeMillis;
			this.wallTimeMillis = wallTimeMillis;
		}
	}
}
