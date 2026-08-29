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
import net.osmand.router.RoadCrewRecordingPolicy;
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
import java.util.function.Consumer;

/**
 * Bridge from accepted GPS fixes to the local vehicle-review journal. Only
 * user-confirmed truck sections enter the upload outbox. Background collection
 * uses a visible foreground service, with or without navigation; raw fixes never
 * leave this coordinator.
 */
public final class RoadCrewMapObservationCoordinator implements OsmAndLocationListener {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewMapObservationCoordinator.class);
	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final Object INSTANCE_LOCK = new Object();
	private static final long ACTIVE_FIX_TIMEOUT_MILLIS = 20_000;
	private static final double LOAD_RADIUS_METERS = 900;
	private static final double RELOAD_DISTANCE_METERS = 350;
	private static final long RELOAD_INTERVAL_MILLIS = 60_000;
	private static final long FOREGROUND_RETRY_COOLDOWN_MILLIS = 30_000;
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
	private volatile long lastForegroundRetryAtMillis;
	private final AtomicInteger collectionGeneration = new AtomicInteger();
	private int appliedCollectionGeneration;
	private boolean previousCollectionContext;
	private volatile boolean navigationSessionActive;
	private long lastTransferElapsed;

	RoadCrewMapObservationCoordinator(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public static void ensureStarted(@NonNull OsmandApplication app) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		getInstance(app).start();
	}

	public static void onMapActivityAvailable(@NonNull OsmandApplication app) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		getInstance(app).retryPendingOnForeground();
		RoadCrewRecordingService.refreshFromForeground(app);
	}

	static void recordingContextChanged(@NonNull OsmandApplication app) {
		getInstance(app).observeTripContext();
		getInstance(app).lastEligibleFixAtMillis = 0;
	}

	static void observeTripContext(OsmandApplication app) {
		getInstance(app).observeTripContext();
	}

	public static void onNavigationStarted(@NonNull OsmandApplication app) {
		if (ROADCREW_PACKAGE.equals(app.getPackageName())) { getInstance(app).beginNavigationSession(); }
	}

	public static void onNavigationFinished(@NonNull OsmandApplication app) {
		if (ROADCREW_PACKAGE.equals(app.getPackageName())) { getInstance(app).endNavigationSession(); }
	}

	private synchronized void beginNavigationSession() {
		if (!enabled || navigationSessionActive || !isTruckProfileActive()
				|| app.getLocationProvider().getLocationSimulation().isRouteAnimating()) { return; }
		navigationSessionActive = true;
		queueTripBoundary(() -> RoadCrewTripJournal.get(app).navigationStarted());
	}

	private synchronized void endNavigationSession() {
		if (!navigationSessionActive) { return; }
		navigationSessionActive = false;
		queueTripBoundary(() -> RoadCrewTripJournal.get(app).navigationFinished());
	}

	private void queueTripBoundary(Runnable boundary) {
		int generation = collectionGeneration.incrementAndGet();
		latestSample.set(null);
		executor.execute(() -> {
			try {
				boundary.run();
				appliedCollectionGeneration = generation;
			} catch (RuntimeException e) {
				// Do not attach new fixes to the previous course after a failed boundary write.
				appliedCollectionGeneration = -1;
				LOG.warn("Could not update trip boundary", e);
			} finally { resetPipeline(); }
		});
	}

	private synchronized void observeTripContext() {
		boolean context = enabled && isCollectionContextActive() && isTruckProfileActive();
		// Also handles consent enabled during an already active navigation session.
		if (app.getRoutingHelper().isFollowingMode()) { beginNavigationSession(); }
		if (previousCollectionContext && !context) {
			queueTripBoundary(() -> RoadCrewTripJournal.get(app).collectionPaused());
		}
		previousCollectionContext = context;
		long elapsed = SystemClock.elapsedRealtime();
		if (enabled && elapsed - lastTransferElapsed >= 60_000) {
			lastTransferElapsed = elapsed;
			transferConfirmed();
		}
	}

	RoadCrewTripJournal.Trip prepareTripReview(boolean manual) throws Exception {
		return executor.submit(() -> {
			if (!enabled) { return null; }
			RoadCrewTripJournal journal = RoadCrewTripJournal.get(app);
			if (manual && !navigationSessionActive) { journal.collectionPaused(); resetPipeline(); }
			return journal.review(manual);
		}).get();
	}

	boolean hasNavigationSession() { return navigationSessionActive; }

	void markTripReviewShown(String tripId) {
		executor.execute(() -> {
			try { if (enabled) { RoadCrewTripJournal.get(app).markPresented(tripId); } }
			catch (RuntimeException e) { LOG.warn("Could not mark trip review shown", e); }
		});
	}

	void saveTripReview(String tripId, long[] included, long[] questions, boolean confirm, boolean discard,
			Consumer<Boolean> completed) {
		// This executor outlives MapActivity, so rotation cannot lose the user's selection.
		executor.execute(() -> {
			boolean saved = false;
			try {
				if (enabled) {
					RoadCrewTripJournal journal = RoadCrewTripJournal.get(app);
					if (confirm) { journal.confirm(tripId, included, questions, discard); }
					else { journal.saveDraft(tripId, included, questions); journal.snooze(tripId); }
					saved = true;
					if (confirm) { transferConfirmed(); }
				}
			} catch (RuntimeException e) { LOG.warn("Could not save trip review", e); }
			boolean result = saved;
			app.runInUIThread(() -> completed.accept(result));
		});
	}

	void transferConfirmed() {
		executor.execute(() -> {
			if (!enabled) { return; }
			try {
				ensurePipeline();
				RoadCrewTripJournal.get(app).transferConfirmed(outbox);
				RoadCrewMapObservationConsent.recordPendingCount(app, outbox.snapshot().size());
				RoadCrewMapObservationUploader.schedule(app, outbox);
			} catch (IOException | RuntimeException e) {
				LOG.warn("Confirmed trip transfer deferred", e);
			}
		});
	}

	/**
	 * Receives the foreground navigation service fix while MapActivity is in the
	 * background. OsmAnd keeps routing current through this service path, but it
	 * does not notify general location listeners unless an Android Auto session
	 * is active.
	 */
	public static void updateLocationFromNavigationService(@NonNull OsmandApplication app,
			@NonNull Location location) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		getInstance(app).updateLocation(location);
	}

	static void setEnabledForApp(@NonNull OsmandApplication app, boolean enabled) {
		RoadCrewMapObservationConsent.setEnabled(app, enabled);
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		getInstance(app).setEnabled(enabled);
		RoadCrewRecordingService.refreshFromForeground(app);
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
		boolean backgroundNavigation = coordinator != null
				&& (RoadCrewRecordingService.isRunning() || coordinator.isActiveTruckNavigation())
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
		} else if (RoadCrewMapObservationConsent.hasUploadWarning(app)) {
			status = CollectionStatus.UPLOAD_WARNING;
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
				RoadCrewMapObservationConsent.getPendingObservationCount(app),
				RoadCrewMapObservationConsent.getRejectedObservationCount(app));
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

	private void retryPendingOnForeground() {
		long now = System.currentTimeMillis();
		if (!enabled || now - lastForegroundRetryAtMillis < FOREGROUND_RETRY_COOLDOWN_MILLIS) {
			return;
		}
		lastForegroundRetryAtMillis = now;
		executor.execute(() -> {
			if (!enabled) {
				return;
			}
			try {
				ensurePipeline();
				if (outbox != null) {
					RoadCrewMapObservationUploader.retryNow(app, outbox);
				}
			} catch (IOException | RuntimeException e) {
				LOG.warn("Cannot retry queued RoadCrew observations on foreground", e);
			}
		});
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
			navigationSessionActive = false;
			int collection = collectionGeneration.incrementAndGet();
			stopListening();
			latestSample.set(null);
			lastEligibleFixAtMillis = 0;
			cancelled.set(true);
			executor.execute(() -> {
				resetPipeline();
				appliedCollectionGeneration = collection;
				if (!this.enabled && stateGeneration.get() == generation) {
					RoadCrewTripJournal.get(app).clear();
					RoadCrewMapObservationConsent.deleteLocalObservations(app);
					outbox = null;
				}
			});
		}
	}

	@Override
	public void updateLocation(Location location) {
		observeTripContext();
		if (!enabled || location == null || !location.hasAccuracy()
				|| !location.hasSpeed() || !location.hasBearing()
				|| app.getLocationProvider().getLocationSimulation().isRouteAnimating()
				|| !isCollectionContextActive() || !isTruckProfileActive()
				|| System.currentTimeMillis() - location.getTime() < 0
				|| System.currentTimeMillis() - location.getTime() > 10_000) {
			return;
		}
		lastEligibleFixAtMillis = System.currentTimeMillis();
		LocationSample sample = new LocationSample(location.getLatitude(), location.getLongitude(),
				location.getAccuracy(), location.getSpeed(), location.getBearing(),
				SystemClock.elapsedRealtime(), location.getTime(), collectionGeneration.get());
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
			if (sample.generation != collectionGeneration.get() || sample.generation != appliedCollectionGeneration || !enabled
					|| !isCollectionContextActive() || !isTruckProfileActive()) { return; }
			RoadCrewShadowSnapshotDownloader.schedule(app, sample.latitude, sample.longitude);
			RoadCrewObservationPipeline currentPipeline = ensurePipeline();
			if (needsRoadReload(sample)) {
				if (!reloadRoads(currentPipeline, sample)) {
					return;
				}
			}
			if (sample.generation != collectionGeneration.get() || sample.generation != appliedCollectionGeneration || !enabled
					|| !isCollectionContextActive() || !isTruckProfileActive()) { return; }
			currentPipeline.accept(
					new RoadCrewSegmentMatcher.GpsFix(sample.latitude, sample.longitude,
							sample.accuracyMeters, sample.speedMetersPerSecond, sample.bearingDegrees),
					sample.elapsedRealtimeMillis, sample.wallTimeMillis);
		} catch (IOException | RuntimeException e) {
			LOG.error("RoadCrew Live Truck Map observation failed closed", e);
			resetPipeline();
		}
	}

	@NonNull
	private RoadCrewObservationPipeline ensurePipeline() throws IOException {
		if (outbox == null) {
			outbox = RoadCrewObservationOutbox.open(RoadCrewMapObservationConsent.getOutboxFile(app));
			RoadCrewMapObservationConsent.recordPendingCount(app, outbox.snapshot().size());
			RoadCrewMapObservationUploader.schedule(app, outbox);
		}
		if (pipeline == null) {
			pipeline = new RoadCrewObservationPipeline((evidence, observedAt, road, binding) -> {
				if (enabled && isCollectionContextActive() && isTruckProfileActive()) {
					RoadCrewTripJournal.get(app).capture(evidence, observedAt, road, binding);
				}
			});
		}
		return pipeline;
	}

	private boolean isCollectionContextActive() {
		return RoadCrewRecordingPolicy.canCollect(enabled, isTruckProfileActive(),
				app.getLocationProvider().getLocationSimulation().isRouteAnimating(),
				app.getSettings().MAP_ACTIVITY_ENABLED,
				RoadCrewRecordingService.isRunning() || isActiveTruckNavigation());
	}

	boolean isTruckProfileActive() {
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
		private final int generation;

		private LocationSample(double latitude, double longitude, double accuracyMeters,
				double speedMetersPerSecond, double bearingDegrees,
				long elapsedRealtimeMillis, long wallTimeMillis, int generation) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.accuracyMeters = accuracyMeters;
			this.speedMetersPerSecond = speedMetersPerSecond;
			this.bearingDegrees = bearingDegrees;
			this.elapsedRealtimeMillis = elapsedRealtimeMillis;
			this.wallTimeMillis = wallTimeMillis;
			this.generation = generation;
		}
	}

	enum CollectionStatus {
		OFF,
		PAUSED,
		TRUCK_PROFILE_REQUIRED,
		WAITING_FOR_GPS,
		ACTIVE,
		UPLOAD_WARNING,
		UPLOAD_ERROR
	}

	static final class StatusSnapshot {
		final CollectionStatus status;
		final boolean backgroundNavigation;
		final boolean communityRoutingAccess;
		final long lastUploadAtMillis;
		final int uploadedObservationCount;
		final int pendingObservationCount;
		final int rejectedObservationCount;

		StatusSnapshot(@NonNull CollectionStatus status, boolean backgroundNavigation,
				boolean communityRoutingAccess, long lastUploadAtMillis,
				int uploadedObservationCount, int pendingObservationCount,
				int rejectedObservationCount) {
			this.status = status;
			this.backgroundNavigation = backgroundNavigation;
			this.communityRoutingAccess = communityRoutingAccess;
			this.lastUploadAtMillis = lastUploadAtMillis;
			this.uploadedObservationCount = uploadedObservationCount;
			this.pendingObservationCount = pendingObservationCount;
			this.rejectedObservationCount = rejectedObservationCount;
		}
	}
}
