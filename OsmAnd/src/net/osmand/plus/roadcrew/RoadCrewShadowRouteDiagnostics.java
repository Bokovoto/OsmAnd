package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.ValueHolder;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RoadCrewShadowIndex;
import net.osmand.router.RoadCrewShadowRouteEvaluator;
import net.osmand.router.RouteSegmentResult;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs aggregate, read-only Shadow diagnostics after production routing. */
final class RoadCrewShadowRouteDiagnostics implements IRouteInformationListener {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewShadowRouteDiagnostics.class);
	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final String PREFS_NAME = "roadcrew_shadow_route_diagnostics";
	private static final String KEY_EVALUATED_AT = "evaluated_at";
	private static final String KEY_SNAPSHOT_GENERATED_AT = "snapshot_generated_at";
	private static final String KEY_ROUTE_DISTANCE_METERS = "route_distance_meters";
	private static final String KEY_EVALUATED_DISTANCE_METERS = "evaluated_distance_meters";
	private static final String KEY_ROUTE_SEGMENT_COUNT = "route_segment_count";
	private static final String KEY_EVALUATED_SEGMENT_COUNT = "evaluated_segment_count";
	private static final String KEY_EXACT_MATCH_COUNT = "exact_match_count";
	private static final String KEY_EXACT_COVERAGE = "exact_coverage";
	private static final String KEY_MATURE_COVERAGE = "mature_coverage";
	private static final String KEY_CONFIDENCE_COVERAGE = "confidence_coverage";
	private static final Object INSTANCE_LOCK = new Object();

	@Nullable
	private static RoadCrewShadowRouteDiagnostics instance;

	private final OsmandApplication app;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final AtomicReference<List<RouteSegmentResult>> pendingRoute = new AtomicReference<>();
	private final AtomicBoolean drainScheduled = new AtomicBoolean();

	private RoadCrewShadowRouteDiagnostics(@NonNull OsmandApplication app) {
		this.app = app;
	}

	static void ensureStarted(@NonNull OsmandApplication app) {
		if (!ROADCREW_PACKAGE.equals(app.getPackageName())) {
			return;
		}
		synchronized (INSTANCE_LOCK) {
			if (instance == null) {
				instance = new RoadCrewShadowRouteDiagnostics(app);
				app.getRoutingHelper().addListener(instance);
			}
		}
	}

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		if (!isEligible()) {
			return;
		}
		RouteCalculationResult calculated = app.getRoutingHelper().getRoute();
		List<RouteSegmentResult> originalRoute = calculated.getOriginalRoute();
		if (!calculated.isCalculated() || originalRoute == null || originalRoute.isEmpty()) {
			return;
		}
		pendingRoute.set(new ArrayList<>(originalRoute));
		if (drainScheduled.compareAndSet(false, true)) {
			executor.execute(this::drain);
		}
	}

	@Override
	public void routeWasCancelled() {
		pendingRoute.set(null);
	}

	@Override
	public void routeWasFinished() {
		// Keep the last aggregate result available for inspection in the profile.
	}

	private void drain() {
		try {
			while (isEligible()) {
				List<RouteSegmentResult> route = pendingRoute.getAndSet(null);
				if (route == null) {
					break;
				}
				evaluate(route);
			}
		} finally {
			drainScheduled.set(false);
			if (isEligible() && pendingRoute.get() != null
					&& drainScheduled.compareAndSet(false, true)) {
				executor.execute(this::drain);
			}
		}
	}

	private void evaluate(@NonNull List<RouteSegmentResult> route) {
		try {
			RoadCrewShadowIndex index = RoadCrewShadowSnapshotDownloader.getCachedIndex(app);
			if (index == null || !isEligible()) {
				return;
			}
			RoadCrewShadowRouteEvaluator.Result result =
					RoadCrewShadowRouteEvaluator.evaluate(route, index);
			persist(app, index.getGeneratedAtMillis(), result);
			LOG.info("RoadCrew Shadow diagnostic: exact=" + result.getExactMatchCount()
					+ "/" + result.getEvaluatedSegmentCount()
					+ ", confidence=" + Math.round(result.getConfidenceCoverage() * 100) + "%");
		} catch (RuntimeException e) {
			LOG.error("RoadCrew Shadow route diagnostic failed closed", e);
		}
	}

	private boolean isEligible() {
		ApplicationMode mode = app.getRoutingHelper().getAppMode();
		return RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)
				&& mode != null && mode.isDerivedRoutingFrom(ApplicationMode.TRUCK);
	}

	private static synchronized void persist(@NonNull Context context, long snapshotGeneratedAt,
			@NonNull RoadCrewShadowRouteEvaluator.Result result) {
		if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(context)) {
			return;
		}
		preferences(context).edit()
				.putLong(KEY_EVALUATED_AT, System.currentTimeMillis())
				.putLong(KEY_SNAPSHOT_GENERATED_AT, snapshotGeneratedAt)
				.putLong(KEY_ROUTE_DISTANCE_METERS, Math.round(result.getRouteDistanceMeters()))
				.putLong(KEY_EVALUATED_DISTANCE_METERS, Math.round(result.getEvaluatedDistanceMeters()))
				.putInt(KEY_ROUTE_SEGMENT_COUNT, result.getRouteSegmentCount())
				.putInt(KEY_EVALUATED_SEGMENT_COUNT, result.getEvaluatedSegmentCount())
				.putInt(KEY_EXACT_MATCH_COUNT, result.getExactMatchCount())
				.putFloat(KEY_EXACT_COVERAGE, (float) result.getExactCoverage())
				.putFloat(KEY_MATURE_COVERAGE, (float) result.getMatureCoverage())
				.putFloat(KEY_CONFIDENCE_COVERAGE, (float) result.getConfidenceCoverage())
				.apply();
	}

	static synchronized void clear(@NonNull Context context) {
		preferences(context).edit().clear().apply();
	}

	@NonNull
	static Summary getLastSummary(@NonNull Context context) {
		SharedPreferences preferences = preferences(context);
		long evaluatedAt = preferences.getLong(KEY_EVALUATED_AT, 0);
		if (evaluatedAt <= 0) {
			return Summary.empty();
		}
		return new Summary(true, evaluatedAt,
				preferences.getLong(KEY_SNAPSHOT_GENERATED_AT, 0),
				preferences.getLong(KEY_ROUTE_DISTANCE_METERS, 0),
				preferences.getLong(KEY_EVALUATED_DISTANCE_METERS, 0),
				preferences.getInt(KEY_ROUTE_SEGMENT_COUNT, 0),
				preferences.getInt(KEY_EVALUATED_SEGMENT_COUNT, 0),
				preferences.getInt(KEY_EXACT_MATCH_COUNT, 0),
				preferences.getFloat(KEY_EXACT_COVERAGE, 0),
				preferences.getFloat(KEY_MATURE_COVERAGE, 0),
				preferences.getFloat(KEY_CONFIDENCE_COVERAGE, 0));
	}

	@NonNull
	private static SharedPreferences preferences(@NonNull Context context) {
		return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	static final class Summary {
		final boolean available;
		final long evaluatedAtMillis;
		final long snapshotGeneratedAtMillis;
		final long routeDistanceMeters;
		final long evaluatedDistanceMeters;
		final int routeSegmentCount;
		final int evaluatedSegmentCount;
		final int exactMatchCount;
		final float exactCoverage;
		final float matureCoverage;
		final float confidenceCoverage;

		private Summary(boolean available, long evaluatedAtMillis,
				long snapshotGeneratedAtMillis, long routeDistanceMeters,
				long evaluatedDistanceMeters, int routeSegmentCount,
				int evaluatedSegmentCount, int exactMatchCount, float exactCoverage,
				float matureCoverage, float confidenceCoverage) {
			this.available = available;
			this.evaluatedAtMillis = evaluatedAtMillis;
			this.snapshotGeneratedAtMillis = snapshotGeneratedAtMillis;
			this.routeDistanceMeters = routeDistanceMeters;
			this.evaluatedDistanceMeters = evaluatedDistanceMeters;
			this.routeSegmentCount = routeSegmentCount;
			this.evaluatedSegmentCount = evaluatedSegmentCount;
			this.exactMatchCount = exactMatchCount;
			this.exactCoverage = exactCoverage;
			this.matureCoverage = matureCoverage;
			this.confidenceCoverage = confidenceCoverage;
		}

		private static Summary empty() {
			return new Summary(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		}
	}
}
