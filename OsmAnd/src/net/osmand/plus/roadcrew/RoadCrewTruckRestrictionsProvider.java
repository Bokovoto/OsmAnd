package net.osmand.plus.roadcrew;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.ResultMatcher;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteRegion;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class RoadCrewTruckRestrictionsProvider {

	private static final int MIN_ZOOM = 12;
	private static final int MAX_RESTRICTIONS = 160;
	private static final long REFRESH_INTERVAL_MILLIS = 1500;

	private final OsmandApplication app;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final AtomicBoolean loading = new AtomicBoolean();
	private final Object lock = new Object();

	private volatile List<TruckRestriction> cachedRestrictions = new ArrayList<>();
	@Nullable
	private volatile String cachedRequestKey;
	private volatile long lastRefreshMillis;

	RoadCrewTruckRestrictionsProvider(@NonNull OsmandApplication app) {
		this.app = app;
	}

	@NonNull
	List<TruckRestriction> getRestrictions(@NonNull RotatedTileBox tileBox) {
		if (tileBox.getZoom() < MIN_ZOOM) {
			return new ArrayList<>();
		}
		String requestKey = createRequestKey(tileBox);
		long now = System.currentTimeMillis();
		if (!requestKey.equals(cachedRequestKey)
				&& now - lastRefreshMillis >= REFRESH_INTERVAL_MILLIS
				&& loading.compareAndSet(false, true)) {
			lastRefreshMillis = now;
			QuadRect bounds = tileBox.getLatLonBounds();
			int zoom = tileBox.getZoom();
			executor.execute(() -> loadRestrictions(bounds, zoom, requestKey));
		}
		synchronized (lock) {
			return new ArrayList<>(cachedRestrictions);
		}
	}

	void shutdown() {
		executor.shutdownNow();
	}

	private void loadRestrictions(@NonNull QuadRect bounds, int zoom, @NonNull String requestKey) {
		try {
			List<TruckRestriction> result = new ArrayList<>();
			Map<String, TruckRestriction> unique = new LinkedHashMap<>();
			BinaryMapIndexReader.SearchRequest<RouteDataObject> request = BinaryMapIndexReader.buildSearchRouteRequest(
					MapUtils.get31TileNumberX(bounds.left),
					MapUtils.get31TileNumberX(bounds.right),
					MapUtils.get31TileNumberY(bounds.top),
					MapUtils.get31TileNumberY(bounds.bottom),
					null);
			for (BinaryMapIndexReader reader : app.getResourceManager().getRoutingMapFiles()) {
				if (reader == null || !reader.containsRouteData()) {
					continue;
				}
				for (RouteRegion region : reader.getRoutingIndexes()) {
					List<RouteSubregion> parent = zoom < 15 ? region.getBaseSubregions() : region.getSubregions();
					if (parent.isEmpty()) {
						parent = region.getSubregions();
					}
					List<RouteSubregion> subregions = reader.searchRouteIndexTree(request, parent);
					if (subregions.isEmpty()) {
						continue;
					}
					reader.loadRouteIndexData(subregions, new ResultMatcher<RouteDataObject>() {
						@Override
						public boolean publish(RouteDataObject object) {
							collectObjectRestrictions(object, unique);
							return unique.size() < MAX_RESTRICTIONS;
						}

						@Override
						public boolean isCancelled() {
							return unique.size() >= MAX_RESTRICTIONS || loading.get() == false;
						}
					});
					if (unique.size() >= MAX_RESTRICTIONS) {
						break;
					}
				}
				if (unique.size() >= MAX_RESTRICTIONS) {
					break;
				}
			}
			result.addAll(unique.values());
			synchronized (lock) {
				cachedRestrictions = result;
				cachedRequestKey = requestKey;
			}
		} catch (IOException | RuntimeException e) {
			// Keep the last good cache. The map can be reloaded while the user pans.
		} finally {
			loading.set(false);
		}
	}

	private void collectObjectRestrictions(@NonNull RouteDataObject object,
			@NonNull Map<String, TruckRestriction> unique) {
		if (object.getPointsLength() == 0) {
			return;
		}
		int middlePoint = object.getPointsLength() / 2;
		collectRouteRestriction(unique, object, middlePoint, "maxweight:hgv", RestrictionKind.WEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxweight", RestrictionKind.WEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxweightrating:hgv", RestrictionKind.WEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxweightrating", RestrictionKind.WEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxheight", RestrictionKind.HEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxheight:forward", RestrictionKind.HEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxheight:backward", RestrictionKind.HEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxheight:physical", RestrictionKind.HEIGHT);
		collectRouteRestriction(unique, object, middlePoint, "maxwidth", RestrictionKind.WIDTH);
		collectRouteRestriction(unique, object, middlePoint, "maxwidth:physical", RestrictionKind.WIDTH);
		collectRouteRestriction(unique, object, middlePoint, "maxlength", RestrictionKind.LENGTH);
		collectAccessRestriction(unique, object, middlePoint, "hgv", "no", RestrictionKind.HGV_NO, "HGV");
		collectAccessRestriction(unique, object, middlePoint, "goods", "no", RestrictionKind.HGV_NO, "GOODS");
		collectAccessRestriction(unique, object, middlePoint, "hazmat", "no", RestrictionKind.HAZMAT_NO, "ADR");

		for (int point = 0; point < object.getPointsLength(); point++) {
			collectPointRestriction(unique, object, point, "maxweight", RestrictionKind.WEIGHT);
			collectPointRestriction(unique, object, point, "maxheight", RestrictionKind.HEIGHT);
			collectPointRestriction(unique, object, point, "maxheight:physical", RestrictionKind.HEIGHT);
			collectPointRestriction(unique, object, point, "maxwidth", RestrictionKind.WIDTH);
			collectPointRestriction(unique, object, point, "maxwidth:physical", RestrictionKind.WIDTH);
			collectPointRestriction(unique, object, point, "maxlength", RestrictionKind.LENGTH);
			collectPointAccessRestriction(unique, object, point, "hgv", "no", RestrictionKind.HGV_NO, "HGV");
			collectPointAccessRestriction(unique, object, point, "goods", "no", RestrictionKind.HGV_NO, "GOODS");
			collectPointAccessRestriction(unique, object, point, "hazmat", "no", RestrictionKind.HAZMAT_NO, "ADR");
		}
	}

	private void collectRouteRestriction(@NonNull Map<String, TruckRestriction> unique,
			@NonNull RouteDataObject object, int point, @NonNull String tag, @NonNull RestrictionKind kind) {
		String value = object.getValue(tag);
		addRestriction(unique, object, point, tag, kind, value);
	}

	private void collectPointRestriction(@NonNull Map<String, TruckRestriction> unique,
			@NonNull RouteDataObject object, int point, @NonNull String tag, @NonNull RestrictionKind kind) {
		String value = object.getValue(point, tag);
		addRestriction(unique, object, point, tag, kind, value);
	}

	private void collectAccessRestriction(@NonNull Map<String, TruckRestriction> unique,
			@NonNull RouteDataObject object, int point, @NonNull String tag, @NonNull String expectedValue,
			@NonNull RestrictionKind kind, @NonNull String label) {
		if (expectedValue.equalsIgnoreCase(object.getValue(tag))) {
			add(unique, object, point, tag, kind, label);
		}
	}

	private void collectPointAccessRestriction(@NonNull Map<String, TruckRestriction> unique,
			@NonNull RouteDataObject object, int point, @NonNull String tag, @NonNull String expectedValue,
			@NonNull RestrictionKind kind, @NonNull String label) {
		if (expectedValue.equalsIgnoreCase(object.getValue(point, tag))) {
			add(unique, object, point, tag, kind, label);
		}
	}

	private void addRestriction(@NonNull Map<String, TruckRestriction> unique, @NonNull RouteDataObject object,
			int point, @NonNull String tag, @NonNull RestrictionKind kind, @Nullable String value) {
		String label = formatLabel(kind, value);
		if (!Algorithms.isEmpty(label)) {
			add(unique, object, point, tag, kind, label);
		}
	}

	private void add(@NonNull Map<String, TruckRestriction> unique, @NonNull RouteDataObject object,
			int point, @NonNull String tag, @NonNull RestrictionKind kind, @NonNull String label) {
		if (unique.size() >= MAX_RESTRICTIONS || point >= object.getPointsLength()) {
			return;
		}
		double latitude = MapUtils.get31LatitudeY(object.getPoint31YTile(point));
		double longitude = MapUtils.get31LongitudeX(object.getPoint31XTile(point));
		String key = object.getId() + ":" + point + ":" + tag + ":" + label;
		unique.put(key, new TruckRestriction(latitude, longitude, kind, label));
	}

	@Nullable
	private String formatLabel(@NonNull RestrictionKind kind, @Nullable String rawValue) {
		if (Algorithms.isEmpty(rawValue)) {
			return null;
		}
		String normalized = rawValue.trim().toLowerCase(Locale.US)
				.replace("tons", "t")
				.replace("ton", "t")
				.replace("metres", "m")
				.replace("meters", "m")
				.replace("meter", "m")
				.replace(" ", "");
		if (normalized.contains("@") || "none".equals(normalized) || "no".equals(normalized) || "yes".equals(normalized)) {
			return null;
		}
		switch (kind) {
			case WEIGHT:
				return normalized.endsWith("t") ? normalized : normalized + "t";
			case HEIGHT:
			case WIDTH:
			case LENGTH:
				return normalized.endsWith("m") ? normalized : normalized + "m";
			default:
				return normalized.toUpperCase(Locale.US);
		}
	}

	@NonNull
	private String createRequestKey(@NonNull RotatedTileBox tileBox) {
		QuadRect bounds = tileBox.getLatLonBounds();
		return tileBox.getZoom() + ":"
				+ Math.round(bounds.left * 500) + ":"
				+ Math.round(bounds.right * 500) + ":"
				+ Math.round(bounds.top * 500) + ":"
				+ Math.round(bounds.bottom * 500);
	}

	enum RestrictionKind {
		WEIGHT,
		HEIGHT,
		WIDTH,
		LENGTH,
		HGV_NO,
		HAZMAT_NO
	}

	static final class TruckRestriction {
		final double latitude;
		final double longitude;
		final RestrictionKind kind;
		final String label;

		TruckRestriction(double latitude, double longitude, @NonNull RestrictionKind kind, @NonNull String label) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.kind = kind;
			this.label = label;
		}
	}
}
