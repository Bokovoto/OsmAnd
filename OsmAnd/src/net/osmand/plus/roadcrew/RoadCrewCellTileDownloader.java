package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.data.QuadRect;
import net.osmand.plus.OsmandApplication;
import net.osmand.router.RouteSegmentResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the rcs2 cell tiles covering a route and keeps them in one file.
 *
 * The same shape as the older preference download: a bounded, blocking refresh
 * at route time, an atomic snapshot on disk, and routing that reads the file
 * rather than the network. A driver in a dead spot routes on what he has.
 *
 * Tiles are a quarter of a degree, the grid the server already serves, and a
 * route asks only for the tiles it crosses - at most {@link #MAX_TILES}, so a
 * Sofia to Hamburg line cannot turn one calculation into a hundred requests.
 */
public final class RoadCrewCellTileDownloader {

	private static final String TAG = "RoadCrewCellTiles";
	private static final String TILE_URL =
			RoadCrewEndpoints.API_BASE_URL + "/v2/truck-map/cell-tiles/";
	private static final String DEVICE_ID_HEADER = "X-RoadCrew-Device-Id";
	private static final double TILE_SIZE_DEGREES = 0.25;
	private static final double ROUTE_MARGIN_DEGREES = 0.15;
	private static final int MAX_TILES = 24;
	private static final long MIN_REFRESH_INTERVAL_MILLIS = 15 * 60_000L;
	private static final long FAILED_REFRESH_INTERVAL_MILLIS = 2 * 60_000L;
	private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
	private static final int READ_TIMEOUT_MILLIS = 8_000;
	private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

	private static long nextRefreshAtMillis;

	private RoadCrewCellTileDownloader() {
	}

	public static synchronized void refreshForRouteBlocking(@NonNull OsmandApplication app,
			@NonNull List<RouteSegmentResult> route) {
		if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextRefreshAtMillis) {
			return;
		}
		QuadRect bounds = boundsOf(route);
		if (bounds == null) {
			return;
		}
		try {
			JSONArray roads = new JSONArray();
			long generatedAt = 0;
			int fetched = 0;
			for (String tile : tilesFor(bounds)) {
				JSONObject document = fetchTile(app, tile);
				if (document == null) {
					continue;
				}
				fetched++;
				generatedAt = Math.max(generatedAt, document.optLong("generatedAt", 0));
				JSONArray tileRoads = document.optJSONArray("roads");
				for (int index = 0; tileRoads != null && index < tileRoads.length(); index++) {
					roads.put(tileRoads.get(index));
				}
			}
			if (fetched == 0) {
				nextRefreshAtMillis = now + FAILED_REFRESH_INTERVAL_MILLIS;
				return;
			}
			write(app, roads, generatedAt > 0 ? generatedAt : now);
			nextRefreshAtMillis = now + MIN_REFRESH_INTERVAL_MILLIS;
		} catch (Exception e) {
			// Routing must never wait on this. What is already on disk stays.
			Log.w(TAG, "Cannot refresh the RoadCrew cell tiles; routing on what is stored", e);
			nextRefreshAtMillis = now + FAILED_REFRESH_INTERVAL_MILLIS;
		}
	}

	@NonNull
	private static List<String> tilesFor(@NonNull QuadRect bounds) {
		int minX = (int) Math.floor((bounds.left - ROUTE_MARGIN_DEGREES + 180) / TILE_SIZE_DEGREES);
		int maxX = (int) Math.floor((bounds.right + ROUTE_MARGIN_DEGREES + 180) / TILE_SIZE_DEGREES);
		int minY = (int) Math.floor((bounds.bottom - ROUTE_MARGIN_DEGREES + 90) / TILE_SIZE_DEGREES);
		int maxY = (int) Math.floor((bounds.top + ROUTE_MARGIN_DEGREES + 90) / TILE_SIZE_DEGREES);
		List<String> tiles = new ArrayList<>();
		for (int y = minY; y <= maxY && tiles.size() < MAX_TILES; y++) {
			for (int x = minX; x <= maxX && tiles.size() < MAX_TILES; x++) {
				if (x >= 0 && y >= 0) {
					tiles.add(x + "_" + y);
				}
			}
		}
		return tiles;
	}

	private static JSONObject fetchTile(@NonNull OsmandApplication app, @NonNull String tile) {
		try {
			String body = requestTile(app, tile);
			return body == null ? null : new JSONObject(body);
		} catch (Exception e) {
			Log.w(TAG, "Cell tile " + tile + " unavailable", e);
			return null;
		}
	}

	/**
	 * Kept apart from the parsing on purpose: the two together, with a
	 * try-with-resources inside a try/catch/finally, produced bytecode the
	 * bundled lint could not read at all (ASM NegativeArraySizeException,
	 * 21.09). Two plain methods analyse cleanly and read no worse.
	 */
	private static String requestTile(@NonNull OsmandApplication app, @NonNull String tile)
			throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(TILE_URL + tile).openConnection();
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			connection.setReadTimeout(READ_TIMEOUT_MILLIS);
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty(DEVICE_ID_HEADER,
					RoadCrewReportsRepository.getLocalDeviceId(app));
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) {
				return null;
			}
			return readBody(connection);
		} finally {
			connection.disconnect();
		}
	}

	private static String readBody(@NonNull HttpURLConnection connection) throws IOException {
		StringBuilder body = new StringBuilder();
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				connection.getInputStream(), StandardCharsets.UTF_8));
		try {
			char[] buffer = new char[8192];
			int read;
			while ((read = reader.read(buffer)) > 0 && body.length() < MAX_RESPONSE_BYTES) {
				body.append(buffer, 0, read);
			}
		} finally {
			reader.close();
		}
		return body.toString();
	}

	/**
	 * One document for every tile fetched, written whole and then moved into
	 * place - a half-written file must never become the map a lorry follows.
	 */
	private static void write(@NonNull OsmandApplication app, @NonNull JSONArray roads,
			long generatedAt) throws IOException {
		JSONObject document = new JSONObject();
		try {
			document.put("ok", true);
			document.put("schemaVersion", 2);
			document.put("evidenceModel", "RCS2_CELLS");
			document.put("routingEffect", "PREFERENCE_ONLY");
			document.put("routingPreferencePolicy", "MATURE_VALIDATED_SOFT_V1");
			document.put("generatedAt", generatedAt);
			document.put("roads", roads);
		} catch (Exception e) {
			throw new IOException("Cannot build the cell preference snapshot", e);
		}
		File target = RoadCrewMapObservationConsent.getCellPreferencesFile(app);
		File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
		try (FileOutputStream output = new FileOutputStream(temporary)) {
			output.write(document.toString().getBytes(StandardCharsets.UTF_8));
			output.getFD().sync();
		}
		if (!temporary.renameTo(target)) {
			// A failed rename leaves the previous snapshot in place, which is
			// the right outcome: old evidence beats half of the new.
			temporary.delete();
			throw new IOException("Cannot replace the cell preference snapshot");
		}
	}

	private static QuadRect boundsOf(@NonNull List<RouteSegmentResult> route) {
		double minLat = Double.MAX_VALUE;
		double maxLat = -Double.MAX_VALUE;
		double minLon = Double.MAX_VALUE;
		double maxLon = -Double.MAX_VALUE;
		for (RouteSegmentResult segment : route) {
			if (segment == null || segment.getObject() == null) {
				continue;
			}
			for (int index = 0; index < segment.getObject().getPointsLength(); index++) {
				double latitude = net.osmand.util.MapUtils.get31LatitudeY(
						segment.getObject().getPoint31YTile(index));
				double longitude = net.osmand.util.MapUtils.get31LongitudeX(
						segment.getObject().getPoint31XTile(index));
				minLat = Math.min(minLat, latitude);
				maxLat = Math.max(maxLat, latitude);
				minLon = Math.min(minLon, longitude);
				maxLon = Math.max(maxLon, longitude);
			}
		}
		if (minLat > maxLat || minLon > maxLon) {
			return null;
		}
		return new QuadRect(minLon, maxLat, maxLon, minLat);
	}
}
