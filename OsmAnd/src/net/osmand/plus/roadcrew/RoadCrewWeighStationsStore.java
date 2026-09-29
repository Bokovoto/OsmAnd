package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The stationary weigh stations, as the server last sent them
 * (GET /v1/weigh-stations). A station added on the server reaches the phone
 * within REFRESH_INTERVAL_MILLIS, without a new version. The last list is kept
 * for driving without internet; a failed refresh never removes it.
 */
final class RoadCrewWeighStationsStore {

	private static final Log LOG = PlatformUtil.getLog(RoadCrewWeighStationsStore.class);
	private static final String PATH = "/v1/weigh-stations";
	private static final String PREFS = "roadcrew_weigh_stations";
	private static final String KEY_JSON = "stations_json";
	private static final String KEY_FETCHED_AT = "fetched_at";
	private static final long REFRESH_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L;
	/** After a failure, and between looks at the clock from the map's frames. */
	private static final long RETRY_INTERVAL_MILLIS = 10 * 60 * 1000L;
	private static final int CONNECT_TIMEOUT_MILLIS = 10 * 1000;
	private static final int READ_TIMEOUT_MILLIS = 15 * 1000;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	@Nullable
	private static volatile List<RoadCrewWeighStations.Station> stations;
	private static long lastLookMillis;

	private RoadCrewWeighStationsStore() {
	}

	/** The last list the server sent; empty until the first one arrives. */
	@NonNull
	static List<RoadCrewWeighStations.Station> get(@NonNull OsmandApplication app) {
		List<RoadCrewWeighStations.Station> current = stations;
		if (current == null) {
			current = parse(prefs(app).getString(KEY_JSON, null));
			if (current == null) {
				current = Collections.emptyList();
			}
			stations = current;
		}
		return current;
	}

	static void refreshPeriodically(@NonNull OsmandApplication app) {
		long now = System.currentTimeMillis();
		synchronized (RoadCrewWeighStationsStore.class) {
			if (now - lastLookMillis < RETRY_INTERVAL_MILLIS) {
				return;
			}
			lastLookMillis = now;
		}
		if (now - prefs(app).getLong(KEY_FETCHED_AT, 0) < REFRESH_INTERVAL_MILLIS) {
			return;
		}
		EXECUTOR.execute(() -> fetch(app));
	}

	private static void fetch(@NonNull OsmandApplication app) {
		try {
			HttpURLConnection connection = (HttpURLConnection) new URL(RoadCrewEndpoints.API_BASE_URL + PATH).openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			connection.setReadTimeout(READ_TIMEOUT_MILLIS);
			connection.setRequestProperty("Accept", "application/json");
			try {
				int responseCode = connection.getResponseCode();
				if (responseCode != HttpURLConnection.HTTP_OK) {
					LOG.warn("RoadCrew weigh stations: HTTP " + responseCode);
					return;
				}
				String body = read(connection);
				List<RoadCrewWeighStations.Station> fresh = parse(body);
				if (fresh == null) {
					LOG.warn("RoadCrew weigh stations: unreadable answer, the last list stays");
					return;
				}
				prefs(app).edit()
						.putString(KEY_JSON, body)
						.putLong(KEY_FETCHED_AT, System.currentTimeMillis())
						.apply();
				stations = fresh;
			} finally {
				connection.disconnect();
			}
		} catch (IOException e) {
			LOG.warn("RoadCrew weigh stations: " + e.getMessage());
		}
	}

	/** Null when the answer is not a list at all; a bad station is only skipped. */
	@Nullable
	private static List<RoadCrewWeighStations.Station> parse(@Nullable String json) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		try {
			JSONArray array = new JSONObject(json).optJSONArray("stations");
			if (array == null) {
				return null;
			}
			List<RoadCrewWeighStations.Station> result = new ArrayList<>();
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.optJSONObject(i);
				if (object == null) {
					continue;
				}
				String id = object.optString("id");
				double lat = object.optDouble("lat", Double.NaN);
				double lon = object.optDouble("lon", Double.NaN);
				if (id.isEmpty() || !(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) {
					continue;
				}
				result.add(new RoadCrewWeighStations.Station(id, object.optString("name"), lat, lon));
			}
			return Collections.unmodifiableList(result);
		} catch (JSONException e) {
			return null;
		}
	}

	@NonNull
	private static String read(@NonNull HttpURLConnection connection) throws IOException {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder builder = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line);
			}
			return builder.toString();
		}
	}

	@NonNull
	private static SharedPreferences prefs(@NonNull OsmandApplication app) {
		return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
