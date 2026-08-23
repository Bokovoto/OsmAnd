package net.osmand.plus.roadcrew;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.plus.NavigationService;
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
 * Bridge from accepted GPS fixes to the local, aggregate RoadCrew passage
 * outbox. Background collection is limited to active truck navigation. Raw
 * fixes never leave this coordinator.
 */
public final class RoadCrewMapObservationCoordinator implements OsmAndLocationListener {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewMapObservationCoordinator.class);
	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final Object INSTANCE_LOCK = new Object();
	private static final long ACTIVE_FIX_TIMEOUT_MILLIS = 20_000;
	private static final double LOAD_RADIUS_METERS = 900;
	private static final double RELOAD_DISTANCE_METERS = 350;
	private static final long RELOAD_INTERVAL_MILLIS = 60_000;
	private static final int MAX_ROUTE_OBJECTS = 8_000;
	@Nullable
	private static RoadCrewMapObservationCoordinator instance;

	private final OsmandApplication app;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final AtomicReference<LocationSample> latestSample = new AtomicReference<>();
	private final AtomicBoolean drainScheduled = new AtomicBoolean();
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final AtomicInteger stateGeneration = new AtomicInteger();

	@Nullable
	private RoadCrewObservationPipeline pipeline;
	@Nullable
	private RoadCrewObservationOutbox outbox;
	private volatile boolean enabled;
	private volatile boolean listening;
	private double loadedLatitude = Double.NaN;
	private double loadedLongitude = Double.NaN;
	private long loadedAtElapsedMillis;
	private volatile long lastEligibleFixAtMillis;

	RoadCrewMapObservationCoordinator(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public static void ensureStarted(@NonNull OsmandApplication app) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		getInstance(app).start();
	}

	static void setEnabledForApp(@NonNull OsmandApplication app, boolean enabled) {
		RoadCrewMapObservationConsent.setEnabled(app, enabled);
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		getInstance(app).setEnabled(enabled);
	}

	@NonNull
	static StatusSnapshot getStatus(@NonNull OsmandApplication app) {
		boolean consentEnabled = RoadCrewMapObservationConsent.isEnabled(app);
		RoadCrewMapObservationCoordinator coordinator;
		synchronized (INSTANCE_LOCK) {
			coordinator = instance;
		}
		boolean truckProfile = coordinator != null
				? coordinator.isTruckProfileActive()
				: app.getSettings().getApplicationMode()
						.isDerivedRoutingFrom(ApplicationMode.TRUCK);
		boolean collectionContext = coordinator != null && coordinator.isCollectionContextActive();
		boolean backgroundNavigation = coordinator != null && coordinator.isActiveTruckNavigation()
				&& !app.getSettings().MAP_ACTIVITY_ENABLED;
		CollectionStatus status;
		if (!consentEnabled) {
			status = CollectionStatus.OFF;
		} else if (!truckProfile) {
			status = CollectionStatus.TRUCK_PROFILE_REQUIRED;
		} else if (!collectionContext) {
			status = CollectionStatus.PAUSED;
		} else if (RoadCrewMapObservationConsent.hasUploadError(app)) {
			status = CollectionStatus.UPLOAD_ERROR;
		} else if (coordinator != null && System.currentTimeMillis()
				- coordinator.lastEligibleFixAtMillis <= ACTIVE_FIX_TIMEOUT_MILLIS) {
			status = CollectionStatus.ACTIVE;
		} else {
			status = CollectionStatus.WAITING_FOR_GPS;
		}
		return new StatusSnapshot(status, backgroundNavigation,
				RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app),
				RoadCrewMapObservationConsent.getLastUploadAt(app),
				RoadCrewMapObservationConsent.getUploadedObservationCount(app),
				RoadCrewMapObservationConsent.getPendingObservationCount(app));
	}

	@NonNull
	static RoadCrewMapObservationCoordinator getInstance(@NonNull OsmandApplication app) {
		synchronized (INSTANCE_LOCK) {
			if (instance == null) {
				instance = new RoadCrewMapObservationCoordinator(app);
			}
			return instance;
		}
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
			lastEligibleFixAtMillis = 0;
			cancelled.set(true);
			executor.execute(() -> {
				resetPipeline();
				if (!this.enabled && stateGeneration.get() == generation) {
					RoadCrewMapObservationConsent.deleteLocalObservations(app);
				}
			});
		}
	}

	@Override
	public void updateLocation(Location location) {
		if (!enabled || location == null || !location.hasAccuracy()
				|| !location.hasSpeed() || !location.hasBearing()
				|| app.getLocationProvider().getLocationSimulation().isRouteAnimating()
				|| !isCollectionContextActive() || !isTruckProfileActive()) {
			return;
		}
		lastEligibleFixAtMillis = System.currentTimeMillis();
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
			RoadCrewShadowSnapshotDownloader.schedule(app, sample.latitude, sample.longitude);
			RoadCrewObservationPipeline currentPipeline = ensurePipeline();
			if (needsRoadReload(sample)) {
				if (!reloadRoads(currentPipeline, sample)) {
					return;
				}
			}
			RoadCrewObservationPipeline.ProcessingResult result = currentPipeline.accept(
					new RoadCrewSegmentMatcher.GpsFix(sample.latitude, sample.longitude,
							sample.accuracyMeters, sample.speedMetersPerSecond, sample.bearingDegrees),
					sample.elapsedRealtimeMillis, sample.wallTimeMillis);
			if (result.wasQueued() && outbox != null) {
				RoadCrewMapObservationConsent.recordPendingCount(app, outbox.snapshot().size());
				RoadCrewMapObservationUploader.schedule(app, outbox);
			}
		} catch (IOException | RuntimeException e) {
			LOG.error("RoadCrew Live Truck Map observation failed closed", e);
			resetPipeline();
		}
	}

	@NonNull
	private RoadCrewObservationPipeline ensurePipeline() throws IOException {
		if (pipeline == null) {
			outbox = RoadCrewObservationOutbox.open(RoadCrewMapObservationConsent.getOutboxFile(app));
			RoadCrewMapObservationConsent.recordPendingCount(app, outbox.snapshot().size());
			pipeline = new RoadCrewObservationPipeline(outbox);
			RoadCrewMapObservationUploader.schedule(app, outbox);
		}
		return pipeline;
	}

	private boolean isCollectionContextActive() {
		return app.getSettings().MAP_ACTIVITY_ENABLED || isActiveTruckNavigation();
	}

	private boolean isTruckProfileActive() {
		ApplicationMode mode = isActiveTruckNavigation()
				? app.getRoutingHelper().getAppMode()
				: app.getSettings().getApplicationMode();
		return mode != null && mode.isDerivedRoutingFrom(ApplicationMode.TRUCK);
	}

	private boolean isActiveTruckNavigation() {
		NavigationService service = app.getNavigationService();
		ApplicationMode routeMode = app.getRoutingHelper().getAppMode();
		return service != null
				&& service.isUsedBy(NavigationService.USED_BY_NAVIGATION)
				&& app.getRoutingHelper().isFollowingMode()
				&& routeMode != null
				&& routeMode.isDerivedRoutingFrom(ApplicationMode.TRUCK);
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
		outbox = null;
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

	enum CollectionStatus {
		OFF,
		PAUSED,
		TRUCK_PROFILE_REQUIRED,
		WAITING_FOR_GPS,
		ACTIVE,
		UPLOAD_ERROR
	}

	static final class StatusSnapshot {
		final CollectionStatus status;
		final boolean backgroundNavigation;
		final boolean communityRoutingAccess;
		final long lastUploadAtMillis;
		final int uploadedObservationCount;
		final int pendingObservationCount;

		StatusSnapshot(@NonNull CollectionStatus status, boolean backgroundNavigation,
				boolean communityRoutingAccess, long lastUploadAtMillis,
				int uploadedObservationCount, int pendingObservationCount) {
			this.status = status;
			this.backgroundNavigation = backgroundNavigation;
			this.communityRoutingAccess = communityRoutingAccess;
			this.lastUploadAtMillis = lastUploadAtMillis;
			this.uploadedObservationCount = uploadedObservationCount;
			this.pendingObservationCount = pendingObservationCount;
		}
	}
}
