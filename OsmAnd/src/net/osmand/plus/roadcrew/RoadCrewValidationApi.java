package net.osmand.plus.roadcrew;

import net.osmand.plus.OsmandApplication;
import net.osmand.router.RoadCrewSegmentIdentity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RoadCrewValidationApi {

	private static final String API = "https://roadcrew-api.galin-b-vasilev1.workers.dev/v2/truck-map/";

	static String existingToken(OsmandApplication app) {
		return RoadCrewMapObservationUploader.getExistingInstallationToken(app);
	}

	static JSONObject request(String token, JSONObject answer) throws IOException, JSONException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API
				+ (answer == null ? "validation-question" : "validation-responses")).openConnection();
		try {
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(20_000);
			connection.setInstanceFollowRedirects(false);
			connection.setRequestProperty("Authorization", "Bearer " + token);
			connection.setRequestProperty("Accept", "application/json");
			if (answer != null) {
				connection.setRequestMethod("POST");
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", "application/json");
				byte[] bytes = answer.toString().getBytes(StandardCharsets.UTF_8);
				connection.setFixedLengthStreamingMode(bytes.length);
				try (OutputStream stream = connection.getOutputStream()) {
					stream.write(bytes);
				}
			}
			int status = connection.getResponseCode();
			if (status == 403 || status == 409 || status == 400) {
				throw new RejectedAnswerException();
			}
			if (status < 200 || status >= 300) {
				throw new IOException("Validation HTTP " + status);
			}
			try (InputStream input = connection.getInputStream();
					ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[2048];
				int count;
				while ((count = input.read(buffer)) != -1) {
					if (output.size() + count > 32_768) {
						throw new IOException("Validation response too large");
					}
					output.write(buffer, 0, count);
				}
				JSONObject result = new JSONObject(output.toString("UTF-8"));
				if (!result.optBoolean("ok")) {
					throw new IOException("Validation response not acknowledged");
				}
				return result;
			}
		} finally {
			connection.disconnect();
		}
	}

	static final class Question {
		final JSONObject json;
		final String id;
		final String segmentId;
		final long passedAt;
		final RoadCrewSegmentIdentity.SegmentKey key;

		Question(JSONObject json) throws JSONException {
			this.json = json;
			id = json.getString("questionId");
			segmentId = json.getString("segmentId");
			passedAt = json.getLong("passedAtBucketMillis");
			if (!"TRUCK_SUITABILITY".equals(json.getString("kind"))
					|| !id.equals("truck-suitability:" + segmentId)) {
				throw new JSONException("Invalid validation question");
			}
			key = RoadCrewSegmentIdentity.key(json.getInt("keyVersion"), json.getLong("osmWayId"),
					json.getString("region"), json.getDouble("fromLatitude"), json.getDouble("fromLongitude"),
					json.getDouble("toLatitude"), json.getDouble("toLongitude"),
					json.getString("geometryFingerprint"), json.getDouble("lengthMeters"));
		}

		JSONObject answer(String decision) throws JSONException {
			return new JSONObject().put("questionId", id).put("segmentId", segmentId).put("decision", decision);
		}
	}

	static final class RejectedAnswerException extends IOException { }
}
