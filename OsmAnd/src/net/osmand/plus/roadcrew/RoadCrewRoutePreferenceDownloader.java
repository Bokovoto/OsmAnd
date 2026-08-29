package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.binary.RouteDataObject;
import net.osmand.plus.OsmandApplication;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.MapUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fetches complete, paginated routing preferences for the calculated route bounds. */
public final class RoadCrewRoutePreferenceDownloader {

	private static final String TAG = "RoadCrewRoutePrefs";
	private static final String API_URL =
			"https://roadcrew-api.galin-b-vasilev1.workers.dev/v1/truck-map/routing-preferences";
	private static final String DEVICE_ID_HEADER = "X-RoadCrew-Device-Id";
	private static final double ROUTE_MARGIN_DEGREES = 0.15;
	private static final double MAX_TILE_SPAN_DEGREES = 1.8;
	private static final long MIN_REFRESH_INTERVAL_MILLIS = 15 * 60_000L;
	private static final long FAILED_REFRESH_INTERVAL_MILLIS = 2 * 60_000L;
	private static final int PAGE_LIMIT = 500;
	private static final int MAX_PAGES = 256;
	private static final int MAX_PREFERENCES = 20_000;
	private static final int MAX_TILE_COUNT = 64;
	private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
	private static final int READ_TIMEOUT_MILLIS = 10_000;
	private static final int MAX_PAGE_CHARS = 2_000_000;

	private static long lastSuccessfulRequestAtMillis;
	private static Bounds lastSuccessfulBounds;
	private static long lastRequestAtMillis;
	private static Bounds lastRequestBounds;

	private RoadCrewRoutePreferenceDownloader() {
	}

	public static synchronized void refreshForRouteBlocking(@NonNull OsmandApplication app,
			@NonNull List<RouteSegmentResult> route) {
		if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app) || route.isEmpty()) {
			return;
		}
		Bounds bounds = routeBounds(route);
		if (bounds == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastSuccessfulBounds != null && lastSuccessfulBounds.contains(bounds)
				&& now - lastSuccessfulRequestAtMillis < MIN_REFRESH_INTERVAL_MILLIS
				&& RoadCrewMapObservationConsent.getRoutingPreferencesFile(app).isFile()) {
			return;
		}
		if (lastRequestBounds != null && lastRequestBounds.contains(bounds)
				&& now - lastRequestAtMillis < FAILED_REFRESH_INTERVAL_MILLIS) {
			return;
		}
		lastRequestAtMillis = now;
		lastRequestBounds = bounds;
		try {
			JSONObject snapshot = requestCompleteSnapshot(app, bounds);
			if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
				return;
			}
			persist(app, snapshot.toString());
			lastSuccessfulRequestAtMillis = System.currentTimeMillis();
			lastSuccessfulBounds = bounds;
			Log.i(TAG, "Cached " + snapshot.getJSONArray("segments").length()
					+ " complete route preferences");
		} catch (IOException | JSONException | IllegalArgumentException e) {
			Log.w(TAG, "Keeping previous route preferences after refresh failure", e);
		}
	}

	@NonNull
	private static JSONObject requestCompleteSnapshot(@NonNull OsmandApplication app,
			@NonNull Bounds bounds) throws IOException, JSONException {
		int latitudeTiles = (int) Math.ceil((bounds.maxLatitude - bounds.minLatitude) / MAX_TILE_SPAN_DEGREES);
		int longitudeTiles = (int) Math.ceil((bounds.maxLongitude - bounds.minLongitude) / MAX_TILE_SPAN_DEGREES);
		latitudeTiles = Math.max(1, latitudeTiles);
		longitudeTiles = Math.max(1, longitudeTiles);
		if (latitudeTiles * longitudeTiles > MAX_TILE_COUNT) {
			throw new IOException("Route bounds require too many preference tiles");
		}

		Map<String, JSONObject> preferences = new LinkedHashMap<>();
		long generatedAt = 0;
		long validUntil = Long.MAX_VALUE;
		int pageCount = 0;
		for (int latIndex = 0; latIndex < latitudeTiles; latIndex++) {
			double minLat = interpolate(bounds.minLatitude, bounds.maxLatitude, latIndex, latitudeTiles);
			double maxLat = interpolate(bounds.minLatitude, bounds.maxLatitude, latIndex + 1, latitudeTiles);
			for (int lonIndex = 0; lonIndex < longitudeTiles; lonIndex++) {
				double minLon = interpolate(bounds.minLongitude, bounds.maxLongitude, lonIndex, longitudeTiles);
				double maxLon = interpolate(bounds.minLongitude, bounds.maxLongitude, lonIndex + 1, longitudeTiles);
				String cursor = null;
				do {
					if (++pageCount > MAX_PAGES) {
						throw new IOException("Routing preference pagination exceeded its safety bound");
					}
					JSONObject page = requestPage(app, minLat, maxLat, minLon, maxLon, cursor);
					if (!page.optBoolean("ok") || page.optInt("schemaVersion") != 1
							|| !"MATURE_VALIDATED_SOFT_V1".equals(page.optString("routingPreferencePolicy"))) {
						throw new JSONException("Unsupported routing preference page");
					}
					generatedAt = Math.max(generatedAt, page.getLong("generatedAt"));
					validUntil = Math.min(validUntil, page.getLong("routingPreferenceValidUntil"));
					JSONArray segments = page.getJSONArray("segments");
					for (int index = 0; index < segments.length(); index++) {
						JSONObject segment = segments.getJSONObject(index);
						preferences.put(segment.getString("segmentId"), segment);
						if (preferences.size() > MAX_PREFERENCES) {
							throw new IOException("Routing preference collection is too large");
						}
					}
					cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
					if ((cursor == null) != page.optBoolean("complete")) {
						throw new JSONException("Inconsistent routing preference pagination");
					}
				} while (cursor != null);
			}
		}
		if (generatedAt <= 0 || validUntil == Long.MAX_VALUE) {
			throw new JSONException("Incomplete routing preference metadata");
		}
		JSONObject snapshot = new JSONObject();
		snapshot.put("ok", true);
		snapshot.put("schemaVersion", 1);
		snapshot.put("generatedAt", generatedAt);
		snapshot.put("routingPreferencePolicy", "MATURE_VALIDATED_SOFT_V1");
		snapshot.put("routingPreferenceValidUntil", validUntil);
		snapshot.put("truncated", false);
		snapshot.put("segments", new JSONArray(preferences.values()));
		return snapshot;
	}

	@NonNull
	private static JSONObject requestPage(@NonNull OsmandApplication app,
			double minLat, double maxLat, double minLon, double maxLon, String cursor)
			throws IOException, JSONException {
		String query = String.format(Locale.US,
				"?minLat=%.6f&maxLat=%.6f&minLon=%.6f&maxLon=%.6f&limit=%d",
				minLat, maxLat, minLon, maxLon, PAGE_LIMIT);
		if (cursor != null) {
			query += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8.name());
		}
		HttpURLConnection connection = (HttpURLConnection) new URL(API_URL + query).openConnection();
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			connection.setReadTimeout(READ_TIMEOUT_MILLIS);
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty(DEVICE_ID_HEADER,
					RoadCrewReportsRepository.getLocalDeviceId(app));
			int responseCode = connection.getResponseCode();
			String response = readResponse(connection, responseCode);
			if (responseCode < 200 || responseCode >= 300) {
				throw new IOException("RoadCrew routing preference API failed with HTTP " + responseCode);
			}
			return new JSONObject(response);
		} finally {
			connection.disconnect();
		}
	}

	private static void persist(@NonNull OsmandApplication app, @NonNull String body) throws IOException {
		File primary = RoadCrewMapObservationConsent.getRoutingPreferencesFile(app);
		File temporary = new File(primary.getPath() + ".tmp");
		File backup = new File(primary.getPath() + ".bak");
		try (FileOutputStream stream = new FileOutputStream(temporary, false);
				BufferedWriter writer = new BufferedWriter(
						new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
			writer.write(body);
			writer.flush();
			stream.getFD().sync();
		}
		boolean rotated = false;
		if (primary.exists()) {
			if (backup.exists() && !backup.delete()) {
				throw new IOException("Cannot replace routing preference backup");
			}
			if (!primary.renameTo(backup)) {
				throw new IOException("Cannot rotate routing preference snapshot");
			}
			rotated = true;
		}
		if (!temporary.renameTo(primary)) {
			if (rotated && !backup.renameTo(primary)) {
				throw new IOException("Cannot publish or restore routing preferences");
			}
			throw new IOException("Cannot publish routing preferences");
		}
	}

	private static String readResponse(HttpURLConnection connection, int responseCode) throws IOException {
		InputStream stream = responseCode >= 200 && responseCode < 300
				? connection.getInputStream() : connection.getErrorStream();
		if (stream == null) {
			return "";
		}
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder body = new StringBuilder();
			char[] buffer = new char[8192];
			int count;
			while ((count = reader.read(buffer)) >= 0) {
				if (body.length() + count > MAX_PAGE_CHARS) {
					throw new IOException("Routing preference page is too large");
				}
				body.append(buffer, 0, count);
			}
			return body.toString();
		}
	}

	private static Bounds routeBounds(List<RouteSegmentResult> route) {
		double minLat = Double.POSITIVE_INFINITY;
		double maxLat = Double.NEGATIVE_INFINITY;
		double minLon = Double.POSITIVE_INFINITY;
		double maxLon = Double.NEGATIVE_INFINITY;
		for (RouteSegmentResult segment : route) {
			RouteDataObject road = segment.getObject();
			int step = segment.getStartPointIndex() <= segment.getEndPointIndex() ? 1 : -1;
			for (int index = segment.getStartPointIndex(); ; index += step) {
				double latitude = MapUtils.get31LatitudeY(road.getPoint31YTile(index));
				double longitude = MapUtils.get31LongitudeX(road.getPoint31XTile(index));
				minLat = Math.min(minLat, latitude);
				maxLat = Math.max(maxLat, latitude);
				minLon = Math.min(minLon, longitude);
				maxLon = Math.max(maxLon, longitude);
				if (index == segment.getEndPointIndex()) {
					break;
				}
			}
		}
		if (!Double.isFinite(minLat) || !Double.isFinite(minLon)) {
			return null;
		}
		return new Bounds(Math.max(-90, minLat - ROUTE_MARGIN_DEGREES),
				Math.min(90, maxLat + ROUTE_MARGIN_DEGREES),
				Math.max(-180, minLon - ROUTE_MARGIN_DEGREES),
				Math.min(180, maxLon + ROUTE_MARGIN_DEGREES));
	}

	private static double interpolate(double min, double max, int index, int count) {
		return min + (max - min) * index / count;
	}

	private static final class Bounds {
		final double minLatitude;
		final double maxLatitude;
		final double minLongitude;
		final double maxLongitude;

		Bounds(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
			this.minLatitude = minLatitude;
			this.maxLatitude = maxLatitude;
			this.minLongitude = minLongitude;
			this.maxLongitude = maxLongitude;
		}

		boolean contains(Bounds other) {
			return minLatitude <= other.minLatitude && maxLatitude >= other.maxLatitude
					&& minLongitude <= other.minLongitude && maxLongitude >= other.maxLongitude;
		}
	}
}
