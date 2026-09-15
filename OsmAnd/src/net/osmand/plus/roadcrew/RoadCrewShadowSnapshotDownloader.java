package net.osmand.plus.roadcrew;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewShadowIndex;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads bounded evidence; routing separately consumes only validated, exact preferences.
 *
 * Since Test 106 the evidence comes as fixed tiles (RoadCrewShadowTiles): the 2x2
 * block around the phone from /v1/truck-map/shadow-tiles/, each tile fetched again
 * only when it is older than one 15-minute epoch. The tiles are merged and saved in
 * the snapshot format the rest of the app already reads.
 */
final class RoadCrewShadowSnapshotDownloader {

	private static final String TAG = "RoadCrewShadow";
	private static final String TILES_URL =
			RoadCrewEndpoints.API_BASE_URL + "/v1/truck-map/shadow-tiles/";
	private static final String DEVICE_ID_HEADER = "X-RoadCrew-Device-Id";
	/** Up to four tiles of up to 500 segments each. */
	private static final int MAX_SNAPSHOT_SEGMENTS = 4 * RoadCrewShadowTiles.MAX_SEGMENTS_PER_TILE;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int READ_TIMEOUT_MILLIS = 20_000;
	private static final int MAX_RESPONSE_CHARS = 2_000_000;
	private static final int MAX_SNAPSHOT_CHARS = 8_000_000;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	private static boolean running;
	/** Tiles fetched in this process, by key; only the current coverage is kept. */
	private static final Map<String, FetchedTile> TILES = new HashMap<>();
	private static List<String> publishedCoverage = new ArrayList<>();

	private static final class FetchedTile {
		final RoadCrewShadowTiles.Tile tile;
		final long fetchedAtMillis;

		FetchedTile(RoadCrewShadowTiles.Tile tile, long fetchedAtMillis) {
			this.tile = tile;
			this.fetchedAtMillis = fetchedAtMillis;
		}
	}

	private RoadCrewShadowSnapshotDownloader() {
	}

	static synchronized void schedule(@NonNull OsmandApplication app,
			double latitude, double longitude) {
		if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)
				|| !isCoordinate(latitude, longitude) || running) {
			return;
		}
		long now = System.currentTimeMillis();
		List<String> coverage = RoadCrewShadowTiles.coverage(latitude, longitude);
		List<String> stale = new ArrayList<>();
		for (String key : coverage) {
			FetchedTile fetched = TILES.get(key);
			if (fetched == null || now - fetched.fetchedAtMillis >= RoadCrewShadowTiles.TILE_EPOCH_MILLIS) {
				stale.add(key);
			}
		}
		if (stale.isEmpty() && coverage.equals(publishedCoverage)) {
			return;
		}
		running = true;
		EXECUTOR.execute(() -> download(app, coverage, stale));
	}

	private static void download(@NonNull OsmandApplication app,
			@NonNull List<String> coverage, @NonNull List<String> stale) {
		try {
			if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
				return;
			}
			for (String key : stale) {
				RoadCrewShadowTiles.Tile tile = parseTile(requestTile(app, key), key);
				synchronized (RoadCrewShadowSnapshotDownloader.class) {
					TILES.put(key, new FetchedTile(tile, System.currentTimeMillis()));
				}
			}
			List<RoadCrewShadowTiles.Tile> parts = new ArrayList<>();
			synchronized (RoadCrewShadowSnapshotDownloader.class) {
				TILES.keySet().retainAll(coverage);
				for (String key : coverage) {
					FetchedTile fetched = TILES.get(key);
					parts.add(fetched == null ? null : fetched.tile);
				}
			}
			String snapshot = snapshotJson(coverage, RoadCrewShadowTiles.merge(coverage, parts));
			RoadCrewShadowIndex index = parseSnapshot(snapshot);
			if (!RoadCrewMapObservationConsent.hasCommunityRoutingAccess(app)) {
				return;
			}
			persist(app, snapshot);
			synchronized (RoadCrewShadowSnapshotDownloader.class) {
				publishedCoverage = new ArrayList<>(coverage);
			}
			Log.i(TAG, "Cached read-only Shadow snapshot with " + index.size() + " segments from "
					+ coverage.size() + " tiles (" + stale.size() + " fetched)");
		} catch (IOException | JSONException | IllegalArgumentException e) {
			// The previous snapshot stays; the tiles still stale are asked for next time.
			Log.w(TAG, "Read-only Shadow tile refresh failed", e);
		} finally {
			synchronized (RoadCrewShadowSnapshotDownloader.class) {
				running = false;
			}
		}
	}

	@NonNull
	private static String requestTile(@NonNull OsmandApplication app, @NonNull String key) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(TILES_URL + key).openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty(DEVICE_ID_HEADER,
				RoadCrewReportsRepository.getLocalDeviceId(app));
		int responseCode = connection.getResponseCode();
		String response = readResponse(connection, responseCode, MAX_RESPONSE_CHARS);
		connection.disconnect();
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("RoadCrew Shadow tile " + key + " failed with HTTP " + responseCode);
		}
		return response;
	}

	/** A tile as the server sends it (truck-map-tiles.ts, shadowTileCache in index.ts). */
	@NonNull
	static RoadCrewShadowTiles.Tile parseTile(@NonNull String body, @NonNull String expectedKey) throws JSONException {
		JSONObject root = new JSONObject(body);
		if (root.getInt("schemaVersion") != RoadCrewShadowIndex.SCHEMA_VERSION
				|| !RoadCrewShadowIndex.ROUTING_EFFECT_NONE.equals(root.getString("routingEffect"))
				|| !expectedKey.equals(root.getString("tile"))) {
			throw new JSONException("Unexpected RoadCrew Shadow tile metadata");
		}
		JSONArray segments = root.getJSONArray("segments");
		if (segments.length() > RoadCrewShadowTiles.MAX_SEGMENTS_PER_TILE) {
			throw new JSONException("Too many segments in RoadCrew Shadow tile");
		}
		List<RoadCrewShadowTiles.Segment> parsed = new ArrayList<>(segments.length());
		for (int index = 0; index < segments.length(); index++) {
			JSONObject segment = segments.getJSONObject(index);
			JSONObject preference = segment.optJSONObject("routingPreference");
			boolean eligible = preference != null && preference.optBoolean("eligible", false);
			double validUntil = preference != null && preference.has("validUntil")
					? preference.getDouble("validUntil") : Double.POSITIVE_INFINITY;
			parsed.add(new RoadCrewShadowTiles.Segment(segment.getString("segmentId"), eligible, validUntil,
					segment.toString()));
		}
		return new RoadCrewShadowTiles.Tile(expectedKey, root.getLong("generatedAt"),
				root.getBoolean("complete"), parsed);
	}

	/** The merged tiles in the snapshot format parseSnapshot and the cache already use. */
	@NonNull
	static String snapshotJson(@NonNull List<String> coverage, @NonNull RoadCrewShadowTiles.Merge merge)
			throws JSONException {
		if (merge.generatedAtMillis <= 0) {
			throw new JSONException("No RoadCrew Shadow tile to publish");
		}
		double minLatitude = 90, maxLatitude = -90, minLongitude = 180, maxLongitude = -180;
		for (String key : coverage) {
			double[] bounds = RoadCrewShadowTiles.bounds(key);
			minLatitude = Math.min(minLatitude, bounds[0]);
			maxLatitude = Math.max(maxLatitude, bounds[1]);
			minLongitude = Math.min(minLongitude, bounds[2]);
			maxLongitude = Math.max(maxLongitude, bounds[3]);
		}
		JSONArray segments = new JSONArray();
		for (RoadCrewShadowTiles.Segment segment : merge.segments) {
			segments.put(new JSONObject(segment.json));
		}
		JSONObject bounds = new JSONObject();
		bounds.put("minLatitude", minLatitude);
		bounds.put("maxLatitude", maxLatitude);
		bounds.put("minLongitude", minLongitude);
		bounds.put("maxLongitude", maxLongitude);
		JSONObject root = new JSONObject();
		root.put("schemaVersion", RoadCrewShadowIndex.SCHEMA_VERSION);
		root.put("generatedAt", merge.generatedAtMillis);
		root.put("routingEffect", RoadCrewShadowIndex.ROUTING_EFFECT_NONE);
		root.put("truncated", !merge.complete);
		root.put("bounds", bounds);
		root.put("tiles", new JSONArray(coverage));
		root.put("segments", segments);
		return root.toString();
	}

	@NonNull
	static RoadCrewShadowIndex parseSnapshot(@NonNull String body) throws JSONException {
		JSONObject root = new JSONObject(body);
		if (!root.has("truncated")) {
			throw new JSONException("Missing RoadCrew Shadow completeness flag");
		}
		JSONArray segments = root.optJSONArray("segments");
		if (segments == null || segments.length() > MAX_SNAPSHOT_SEGMENTS) {
			throw new JSONException("Invalid RoadCrew Shadow segment collection");
		}
		List<RoadCrewShadowIndex.Entry> entries = new ArrayList<>(segments.length());
		for (int index = 0; index < segments.length(); index++) {
			JSONObject segment = segments.getJSONObject(index);
			entries.add(new RoadCrewShadowIndex.Entry(
					segment.getString("segmentId"),
					segment.getString("canonicalId"),
					segment.getString("geometryFingerprint"),
					RoadCrewShadowIndex.Level.parse(segment.getString("shadowLevel")),
					segment.getDouble("confidence"),
					segment.getInt("passageCount"),
					segment.getInt("distinctObserverCount"),
					segment.getInt("activeDayCount")));
		}
		JSONObject bounds = root.getJSONObject("bounds");
		return RoadCrewShadowIndex.create(root.getInt("schemaVersion"),
				root.getLong("generatedAt"), root.getString("routingEffect"),
				new RoadCrewShadowIndex.Bounds(
						bounds.getDouble("minLatitude"), bounds.getDouble("maxLatitude"),
						bounds.getDouble("minLongitude"), bounds.getDouble("maxLongitude")),
				entries);
	}

	@NonNull
	static Summary getCachedSummary(@NonNull Context context) {
		RoadCrewShadowIndex index = getCachedIndex(context);
		if (index != null) {
			return new Summary(true, index.getGeneratedAtMillis(), index.size(),
					index.count(RoadCrewShadowIndex.Level.COLLECTING),
					index.count(RoadCrewShadowIndex.Level.CANDIDATE),
					index.count(RoadCrewShadowIndex.Level.MATURE_SHADOW));
		}
		return Summary.empty();
	}

	@Nullable
	static RoadCrewShadowIndex getCachedIndex(@NonNull Context context) {
		for (File candidate : cacheCandidates(context)) {
			if (!candidate.isFile()) {
				continue;
			}
			try {
				return parseSnapshot(readFile(candidate));
			} catch (IOException | JSONException | IllegalArgumentException e) {
				Log.w(TAG, "Ignoring invalid cached Shadow snapshot", e);
			}
		}
		return null;
	}

	private static void persist(@NonNull Context context, @NonNull String body) throws IOException {
		File primary = RoadCrewMapObservationConsent.getShadowSnapshotFile(context);
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
				throw new IOException("Cannot replace RoadCrew Shadow backup");
			}
			if (!primary.renameTo(backup)) {
				throw new IOException("Cannot rotate RoadCrew Shadow snapshot");
			}
			rotated = true;
		}
		if (!temporary.renameTo(primary)) {
			if (rotated && !backup.renameTo(primary)) {
				throw new IOException("Cannot publish or restore RoadCrew Shadow snapshot");
			}
			throw new IOException("Cannot publish RoadCrew Shadow snapshot");
		}
	}

	@NonNull
	private static List<File> cacheCandidates(@NonNull Context context) {
		File primary = RoadCrewMapObservationConsent.getShadowSnapshotFile(context);
		List<File> result = new ArrayList<>(2);
		result.add(primary);
		result.add(new File(primary.getPath() + ".bak"));
		return result;
	}

	@NonNull
	private static String readFile(@NonNull File file) throws IOException {
		try (FileInputStream stream = new FileInputStream(file)) {
			return readStream(stream, MAX_SNAPSHOT_CHARS);
		}
	}

	@NonNull
	private static String readResponse(@NonNull HttpURLConnection connection, int responseCode, int maxChars)
			throws IOException {
		InputStream stream = responseCode >= 200 && responseCode < 300
				? connection.getInputStream() : connection.getErrorStream();
		return stream == null ? "" : readStream(stream, maxChars);
	}

	@NonNull
	private static String readStream(@NonNull InputStream stream, int maxChars) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder result = new StringBuilder();
			char[] buffer = new char[8_192];
			int read;
			while ((read = reader.read(buffer)) >= 0) {
				if (result.length() + read > maxChars) {
					throw new IOException("RoadCrew Shadow response is too large");
				}
				result.append(buffer, 0, read);
			}
			return result.toString();
		}
	}

	private static boolean isCoordinate(double latitude, double longitude) {
		return Double.isFinite(latitude) && Double.isFinite(longitude)
				&& latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
	}

	static final class Summary {
		final boolean available;
		final long generatedAtMillis;
		final int totalCount;
		final int collectingCount;
		final int candidateCount;
		final int matureCount;

		private Summary(boolean available, long generatedAtMillis, int totalCount,
				int collectingCount, int candidateCount, int matureCount) {
			this.available = available;
			this.generatedAtMillis = generatedAtMillis;
			this.totalCount = totalCount;
			this.collectingCount = collectingCount;
			this.candidateCount = candidateCount;
			this.matureCount = matureCount;
		}

		private static Summary empty() {
			return new Summary(false, 0, 0, 0, 0, 0);
		}
	}
}
