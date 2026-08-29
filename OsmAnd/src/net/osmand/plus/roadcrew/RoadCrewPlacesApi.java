package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RoadCrewPlacesApi {

	private static final String TAG = "RoadCrewPlacesApi";
	private static final String API_BASE_URL = "https://roadcrew-api.galin-b-vasilev1.workers.dev";
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

	private RoadCrewPlacesApi() {
	}

	static void listPlaces(@NonNull OsmandApplication app, @NonNull LatLon center, double radiusKm,
			@NonNull Callback<List<RoadCrewPlace>> callback) {
		run(app, callback, () -> {
			String path = "/v1/places?lat=" + center.getLatitude() + "&lon=" + center.getLongitude()
					+ "&radiusKm=" + Math.min(100, Math.max(1, radiusKm)) + "&limit=150";
			JSONArray array = get(path, deviceId(app)).optJSONArray("places");
			List<RoadCrewPlace> places = new ArrayList<>();
			if (array != null) {
				for (int i = 0; i < array.length(); i++) {
					places.add(readPlace(array.getJSONObject(i)));
				}
			}
			return places;
		});
	}

	static void createPlace(@NonNull OsmandApplication app, @NonNull String kind, @NonNull String name,
			@NonNull LatLon location, @NonNull String sourceType, @NonNull String sourceId,
			@NonNull Callback<String> callback) {
		run(app, callback, () -> {
			JSONObject body = new JSONObject();
			body.put("kind", kind);
			body.put("name", name);
			body.put("lat", location.getLatitude());
			body.put("lon", location.getLongitude());
			body.put("sourceType", sourceType);
			if (!sourceId.isEmpty()) {
				body.put("sourceId", sourceId);
			}
			return post("/v1/places", deviceId(app), body).optString("id");
		});
	}

	static void getPlace(@NonNull OsmandApplication app, @NonNull String placeId,
			@NonNull Callback<RoadCrewPlace.Details> callback) {
		run(app, callback, () -> {
			JSONObject response = get("/v1/places/" + encode(placeId), deviceId(app));
			RoadCrewPlace place = readPlace(response.getJSONObject("place"));
			List<RoadCrewPlace.Message> messages = new ArrayList<>();
			JSONArray messageArray = response.optJSONArray("messages");
			if (messageArray != null) {
				for (int i = 0; i < messageArray.length(); i++) {
					JSONObject object = messageArray.getJSONObject(i);
					messages.add(new RoadCrewPlace.Message(object.optString("id"), object.optString("displayName"),
							object.optString("category"), object.optString("body"), object.optLong("createdAt"),
							object.optLong("expiresAt"), object.optBoolean("verifiedVisit"),
							object.optInt("stillValidCount"), object.optInt("outdatedCount"), object.optString("localVote")));
				}
			}
			List<RoadCrewPlace.Review> reviews = new ArrayList<>();
			JSONArray reviewArray = response.optJSONArray("reviews");
			if (reviewArray != null) {
				for (int i = 0; i < reviewArray.length(); i++) {
					JSONObject object = reviewArray.getJSONObject(i);
					reviews.add(new RoadCrewPlace.Review(object.optString("displayName"),
							object.optInt("securityScore"), object.optInt("quietScore"),
							object.optInt("accessScore"), object.optInt("facilitiesScore"),
							object.optBoolean("theftReported"), object.optString("body"),
							object.optBoolean("verifiedVisit"), object.optLong("updatedAt")));
				}
			}
			JSONObject rating = response.optJSONObject("rating");
			if (rating == null) {
				rating = new JSONObject();
			}
			return new RoadCrewPlace.Details(place, messages, reviews, rating.optInt("count"),
					rating.optDouble("average"), rating.optDouble("security"), rating.optDouble("quiet"),
					rating.optDouble("access"), rating.optDouble("facilities"), rating.optInt("theftReports"));
		});
	}

	static void createMessage(@NonNull OsmandApplication app, @NonNull String placeId,
			@NonNull String category, @NonNull String message, @NonNull Callback<Boolean> callback) {
		run(app, callback, () -> {
			JSONObject body = new JSONObject();
			body.put("category", category);
			body.put("body", message);
			post("/v1/places/" + encode(placeId) + "/messages", deviceId(app), body);
			return true;
		});
	}

	static void saveReview(@NonNull OsmandApplication app, @NonNull String placeId,
			int security, int quiet, int access, int facilities, boolean theftReported,
			@NonNull String bodyText, @NonNull Callback<Boolean> callback) {
		run(app, callback, () -> {
			JSONObject body = new JSONObject();
			body.put("securityScore", security);
			body.put("quietScore", quiet);
			body.put("accessScore", access);
			body.put("facilitiesScore", facilities);
			body.put("theftReported", theftReported);
			body.put("body", bodyText);
			post("/v1/places/" + encode(placeId) + "/reviews", deviceId(app), body);
			return true;
		});
	}

	static void voteMessage(@NonNull OsmandApplication app, @NonNull String messageId,
			@NonNull String vote, @NonNull Callback<Boolean> callback) {
		run(app, callback, () -> {
			JSONObject body = new JSONObject();
			body.put("vote", vote);
			post("/v1/place-messages/" + encode(messageId) + "/votes", deviceId(app), body);
			return true;
		});
	}

	@NonNull
	private static RoadCrewPlace readPlace(@NonNull JSONObject object) {
		return new RoadCrewPlace(object.optString("id"), object.optString("kind", "OTHER"),
				object.optString("name"), new LatLon(object.optDouble("lat"), object.optDouble("lon")),
				object.optString("sourceType"), object.optString("sourceId"), object.optString("latestCategory"),
				object.optString("latestBody"), object.optLong("latestMessageAt"), object.optLong("latestExpiresAt"),
				object.optInt("activeMessageCount"), object.optInt("reviewCount"),
				object.optDouble("averageRating"), object.optDouble("distanceKm"));
	}

	private static <T> void run(@NonNull OsmandApplication app, @NonNull Callback<T> callback,
			@NonNull Request<T> request) {
		EXECUTOR.execute(() -> {
			try {
				T result = request.execute();
				app.runInUIThread(() -> callback.onSuccess(result));
			} catch (Exception error) {
				Log.w(TAG, "Place channel request failed", error);
				app.runInUIThread(() -> callback.onError(error));
			}
		});
	}

	@NonNull
	private static JSONObject get(@NonNull String path, @NonNull String deviceId) throws IOException, JSONException {
		return request(path, deviceId, "GET", null);
	}

	@NonNull
	private static JSONObject post(@NonNull String path, @NonNull String deviceId, @NonNull JSONObject body)
			throws IOException, JSONException {
		return request(path, deviceId, "POST", body);
	}

	@NonNull
	private static JSONObject request(@NonNull String path, @NonNull String deviceId,
			@NonNull String method, JSONObject body) throws IOException, JSONException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE_URL + path).openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(10_000);
		connection.setReadTimeout(15_000);
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("X-RoadCrew-Device-Id", deviceId);
		if (body != null) {
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			try (OutputStream output = connection.getOutputStream()) {
				output.write(body.toString().getBytes(StandardCharsets.UTF_8));
			}
		}
		int status = connection.getResponseCode();
		String responseBody = readBody(connection, status);
		connection.disconnect();
		if (status < 200 || status >= 300) {
			String message = responseBody;
			try { message = new JSONObject(responseBody).optString("error", responseBody); } catch (JSONException ignored) { }
			throw new IOException(message.isEmpty() ? "HTTP " + status : message);
		}
		return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
	}

	@NonNull
	private static String readBody(@NonNull HttpURLConnection connection, int status) throws IOException {
		InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		if (input == null) return "";
		StringBuilder result = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) result.append(line);
		}
		return result.toString();
	}

	@NonNull
	private static String encode(@NonNull String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	@NonNull
	private static String deviceId(@NonNull OsmandApplication app) {
		return RoadCrewReportsRepository.getLocalDeviceId(app);
	}

	interface Callback<T> {
		void onSuccess(@NonNull T result);
		void onError(@NonNull Exception error);
	}

	private interface Request<T> {
		T execute() throws IOException, JSONException;
	}
}
