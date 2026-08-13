package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.Location;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoadCrewReportsSync {

	private static final String TAG = "RoadCrewReportsSync";
	private static final String API_BASE_URL = "https://roadcrew-api.galin-b-vasilev1.workers.dev";
	private static final String DEVICE_ID_HEADER = "X-RoadCrew-Device-Id";
	private static final int CONNECT_TIMEOUT_MILLIS = 10 * 1000;
	private static final int READ_TIMEOUT_MILLIS = 15 * 1000;
	private static final long AUTO_SYNC_INTERVAL_MILLIS = 60 * 1000;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
	private static boolean syncRunning;
	private static long lastAutoSyncMillis;

	private RoadCrewReportsSync() {
	}

	public static void syncNow(@NonNull OsmandApplication app) {
		if (!RoadCrewReportsLayer.isEnabled(app)) {
			return;
		}
		synchronized (RoadCrewReportsSync.class) {
			if (syncRunning) {
				return;
			}
			syncRunning = true;
		}
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				RoadCrewPushNotifications.ensureRegistered(app);
				sendHeartbeat(app, deviceId);
				syncDriverProfile(app, deviceId);
				syncPendingReports(app, deviceId);
				fetchRemoteReports(app, deviceId);
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew sync failed", e);
			} finally {
				synchronized (RoadCrewReportsSync.class) {
					syncRunning = false;
				}
			}
		});
	}

	public static void syncPeriodically(@NonNull OsmandApplication app) {
		long now = System.currentTimeMillis();
		synchronized (RoadCrewReportsSync.class) {
			if (now - lastAutoSyncMillis < AUTO_SYNC_INTERVAL_MILLIS) {
				return;
			}
			lastAutoSyncMillis = now;
		}
		syncNow(app);
	}

	public static void fetchNotifications(@NonNull OsmandApplication app,
			@NonNull NotificationsCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject response = getJson("/v1/notifications", deviceId);
				JSONArray array = response.optJSONArray("notifications");
				List<RoadCrewNotification> notifications = new ArrayList<>();
				if (array != null) {
					for (int i = 0; i < array.length(); i++) {
						JSONObject object = array.getJSONObject(i);
						notifications.add(new RoadCrewNotification(
								object.optString("id"),
								object.optString("reportId"),
								object.optString("kind"),
								object.optString("title"),
								object.optString("body"),
								object.optLong("createdAt")
						));
					}
				}
				app.runInUIThread(() -> callback.onNotifications(notifications));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew notifications failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void joinHelpChat(@NonNull OsmandApplication app, @NonNull String reportId,
			@NonNull HelpChatCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject response = postJson("/v1/help-requests/" + reportId + "/join", deviceId, new JSONObject());
				String chatRoomId = response.optString("chatRoomId");
				app.runInUIThread(() -> callback.onSuccess(chatRoomId));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew join help chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void openPlateAlertChat(@NonNull OsmandApplication app, @NonNull String plateAlertId,
			@NonNull HelpChatCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject response = postJson("/v1/plate-alerts/" + plateAlertId + "/chat", deviceId, new JSONObject());
				app.runInUIThread(() -> callback.onSuccess(response.optString("chatRoomId")));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew open direct chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void syncHelpReportAndJoinChat(@NonNull OsmandApplication app, @NonNull RoadCrewReport report,
			@NonNull HelpReportChatCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				String reportId = report.getId();
				if (!isRemoteReport(report)) {
					reportId = RoadCrewReportsRepository.findSyncedReportIdMatching(app, report);
					if (reportId.isEmpty()) {
						reportId = createRemoteReport(app, deviceId, report);
					}
				}
				JSONObject response = postJson("/v1/help-requests/" + reportId + "/join", deviceId, new JSONObject());
				String chatRoomId = response.optString("chatRoomId");
				String syncedReportId = reportId;
				app.runInUIThread(() -> callback.onSuccess(syncedReportId, chatRoomId));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew sync Help report and join chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void fetchHelpChatMessages(@NonNull OsmandApplication app, @NonNull String reportId,
			@NonNull HelpChatMessagesCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject response = getJson("/v1/help-requests/" + reportId + "/messages", deviceId);
				JSONArray array = response.optJSONArray("messages");
				List<RoadCrewChatMessage> messages = new ArrayList<>();
				if (array != null) {
					for (int i = 0; i < array.length(); i++) {
						JSONObject object = array.getJSONObject(i);
						messages.add(new RoadCrewChatMessage(
								object.optString("id"),
								object.optString("deviceId"),
								object.optString("displayName"),
								object.optString("body"),
								object.optLong("createdAt")
						));
					}
				}
				app.runInUIThread(() -> callback.onMessages(messages));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew fetch help chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void fetchDirectChatMessages(@NonNull OsmandApplication app, @NonNull String chatRoomId,
			@NonNull HelpChatMessagesCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject response = getJson("/v1/direct-chats/" + chatRoomId + "/messages", deviceId);
				JSONArray array = response.optJSONArray("messages");
				List<RoadCrewChatMessage> messages = new ArrayList<>();
				if (array != null) {
					for (int i = 0; i < array.length(); i++) {
						JSONObject object = array.getJSONObject(i);
						messages.add(new RoadCrewChatMessage(
								object.optString("id"),
								object.optString("deviceId"),
								object.optString("displayName"),
								object.optString("body"),
								object.optLong("createdAt")
						));
					}
				}
				app.runInUIThread(() -> callback.onMessages(messages));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew fetch direct chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void sendHelpChatMessage(@NonNull OsmandApplication app, @NonNull String reportId,
			@NonNull String message, @NonNull HelpChatCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject body = new JSONObject();
				body.put("body", message);
				JSONObject response = postJson("/v1/help-requests/" + reportId + "/messages", deviceId, body);
				app.runInUIThread(() -> callback.onSuccess(response.optString("chatRoomId")));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew send help chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void sendDirectChatMessage(@NonNull OsmandApplication app, @NonNull String chatRoomId,
			@NonNull String message, @NonNull HelpChatCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject body = new JSONObject();
				body.put("body", message);
				JSONObject response = postJson("/v1/direct-chats/" + chatRoomId + "/messages", deviceId, body);
				app.runInUIThread(() -> callback.onSuccess(response.optString("chatRoomId")));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew send direct chat failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void resolveHelpReport(@NonNull OsmandApplication app, @NonNull RoadCrewReport report,
			@NonNull HelpResolveCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				String originalReportId = report.getId();
				String reportId = originalReportId;
				if (!isRemoteReport(report)) {
					reportId = RoadCrewReportsRepository.findSyncedReportIdMatching(app, report);
					if (reportId.isEmpty()) {
						RoadCrewReportsRepository.removeReport(app, originalReportId);
						app.runInUIThread(callback::onSuccess);
						return;
					}
				}
				postJson("/v1/help-requests/" + reportId + "/resolve", deviceId, new JSONObject());
				RoadCrewReportsRepository.removeReport(app, originalReportId);
				if (!reportId.equals(originalReportId)) {
					RoadCrewReportsRepository.removeReport(app, reportId);
				}
				app.runInUIThread(callback::onSuccess);
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew resolve Help report failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void sendPlateSafetyAlert(@NonNull OsmandApplication app, @NonNull String normalizedPlate,
			@NonNull String category, @NonNull String message, @NonNull HelpResolveCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String plateHash = RoadCrewDriverProfile.plateHash(normalizedPlate);
				if (plateHash.isEmpty()) {
					throw new IOException("Missing plate hash");
				}
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject body = new JSONObject();
				body.put("plateHash", plateHash);
				JSONArray plateHashes = new JSONArray();
				for (String hash : RoadCrewDriverProfile.plateHashesForLookup(normalizedPlate)) {
					plateHashes.put(hash);
				}
				body.put("plateHashes", plateHashes);
				body.put("category", category);
				if (!message.trim().isEmpty()) {
					body.put("body", message);
				}
				JSONObject response = postJson("/v1/plate-alerts", deviceId, body);
				int matchedCount = response.optInt("matchedCount", 0);
				boolean selfMatch = response.optBoolean("selfMatch", false);
				app.runInUIThread(() -> {
					if (matchedCount > 0) {
						callback.onSuccess();
					} else if (selfMatch) {
						callback.onError(new IOException("This number is registered on this phone."));
					} else {
						callback.onError(new IOException("No opted-in driver found for this truck or trailer number."));
					}
				});
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew plate safety alert failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public static void registerPushToken(@NonNull OsmandApplication app, @NonNull String token,
			@NonNull HelpResolveCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				JSONObject body = new JSONObject();
				body.put("provider", "FCM");
				body.put("token", token);
				postJson("/v1/devices/push-token", deviceId, body);
				app.runInUIThread(callback::onSuccess);
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew push token registration failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	private static void sendHeartbeat(@NonNull OsmandApplication app, @NonNull String deviceId)
			throws IOException, JSONException {
		Location location = app.getLocationProvider().getLastKnownLocation();
		if (location == null) {
			return;
		}
		JSONObject body = new JSONObject();
		body.put("lat", location.getLatitude());
		body.put("lon", location.getLongitude());
		String displayName = RoadCrewDriverProfile.load(app).getDisplayName();
		if (!displayName.isEmpty()) {
			body.put("displayName", displayName);
		}
		if (location.hasBearing()) {
			body.put("heading", location.getBearing());
		}
		if (location.hasSpeed()) {
			body.put("speed", location.getSpeed());
		}
		postJson("/v1/devices/heartbeat", deviceId, body);
	}

	private static void syncDriverProfile(@NonNull OsmandApplication app, @NonNull String deviceId)
			throws IOException, JSONException {
		RoadCrewDriverProfile profile = RoadCrewDriverProfile.load(app);
		JSONObject body = new JSONObject();
		body.put("displayName", profile.getDisplayName());
		body.put("plateAlertsEnabled", profile.isPlateAlertsEnabled());
		JSONArray plates = new JSONArray();
		if (profile.isPlateAlertsEnabled()) {
			for (String truckHash : profile.getTruckPlateHashes()) {
				JSONObject truck = new JSONObject();
				truck.put("kind", "TRUCK");
				truck.put("hash", truckHash);
				plates.put(truck);
			}
			for (String trailerHash : profile.getTrailerPlateHashes()) {
				JSONObject trailer = new JSONObject();
				trailer.put("kind", "TRAILER");
				trailer.put("hash", trailerHash);
				plates.put(trailer);
			}
		}
		body.put("plates", plates);
		postJson("/v1/devices/profile", deviceId, body);
	}

	private static void syncPendingReports(@NonNull OsmandApplication app, @NonNull String deviceId)
			throws IOException, JSONException {
		List<RoadCrewReport> reports = RoadCrewReportsRepository.getReports(app);
		for (RoadCrewReport report : reports) {
			if (report.getSyncState() == RoadCrewReportSyncState.PENDING_CREATE) {
				createRemoteReport(app, deviceId, report);
			} else if (report.getSyncState() == RoadCrewReportSyncState.PENDING_UPDATE
					&& report.hasLocalVote()
					&& isRemoteReport(report)) {
				syncRemoteVote(deviceId, report, report.getId());
				RoadCrewReportsRepository.markReportSynced(app, report.getId(), report.getId(),
						report.getExpiresAtMillis());
			}
		}
	}

	private static void fetchRemoteReports(@NonNull OsmandApplication app, @NonNull String deviceId)
			throws IOException, JSONException {
		Location location = app.getLocationProvider().getLastKnownLocation();
		if (location == null) {
			return;
		}
		String path = "/v1/reports?lat=" + location.getLatitude()
				+ "&lon=" + location.getLongitude()
				+ "&radiusKm=50";
		JSONObject response = getJson(path, deviceId);
		JSONArray array = response.optJSONArray("reports");
		if (array == null) {
			return;
		}
		List<RoadCrewReport> remoteReports = new ArrayList<>();
		for (int i = 0; i < array.length(); i++) {
			RoadCrewReport report = readRemoteReport(array.getJSONObject(i));
			if (report != null) {
				remoteReports.add(report);
			}
		}
		if (RoadCrewReportsRepository.mergeRemoteReports(app, remoteReports)) {
			app.runInUIThread(() -> app.getOsmandMap().refreshMap());
		}
	}

	private static RoadCrewReport readRemoteReport(@NonNull JSONObject object) {
		try {
			RoadCrewReportType type = RoadCrewReportType.valueOf(object.getString("type"));
			return new RoadCrewReport(
					object.getString("id"),
					type,
					new LatLon(object.getDouble("lat"), object.getDouble("lon")),
					object.getLong("createdAt"),
					object.getLong("expiresAt"),
					object.optString("createdBy", ""),
					object.optString("details", ""),
					RoadCrewReportSyncState.SYNCED,
					object.optInt("confirmedCount", 0),
					object.optInt("deniedCount", 0),
					RoadCrewReportLocalVote.NONE,
					object.optLong("probablyResolvedAt", 0)
			);
		} catch (IllegalArgumentException | JSONException e) {
			return null;
		}
	}

	@NonNull
	private static String createRemoteReport(@NonNull OsmandApplication app, @NonNull String deviceId,
			@NonNull RoadCrewReport report) throws IOException, JSONException {
		JSONObject body = new JSONObject();
		LatLon location = report.getLocation();
		body.put("type", report.getType().name());
		body.put("lat", location.getLatitude());
		body.put("lon", location.getLongitude());
		body.put("details", report.getDetails());

		JSONObject response = postJson("/v1/reports", deviceId, body);
		String remoteReportId = response.getString("reportId");
		long remoteExpiresAt = response.optLong("expiresAt", report.getExpiresAtMillis());
		if (report.hasLocalVote()) {
			syncRemoteVote(deviceId, report, remoteReportId);
		}
		RoadCrewReportsRepository.markReportSynced(app, report.getId(), remoteReportId, remoteExpiresAt);
		return remoteReportId;
	}

	private static void syncRemoteVote(@NonNull String deviceId, @NonNull RoadCrewReport report,
			@NonNull String remoteReportId) throws IOException, JSONException {
		JSONObject body = new JSONObject();
		body.put("vote", report.getLocalVote() == RoadCrewReportLocalVote.CONFIRMED ? "CONFIRMED" : "DENIED");
		postJson("/v1/reports/" + remoteReportId + "/votes", deviceId, body, true);
	}

	private static boolean isRemoteReport(@NonNull RoadCrewReport report) {
		return !report.getId().startsWith("local-") && !report.getId().startsWith("seed-");
	}

	@NonNull
	private static JSONObject postJson(@NonNull String path, @NonNull String deviceId, @NonNull JSONObject body)
			throws IOException, JSONException {
		return postJson(path, deviceId, body, false);
	}

	@NonNull
	private static JSONObject postJson(@NonNull String path, @NonNull String deviceId, @NonNull JSONObject body,
			boolean allowDuplicateVote) throws IOException, JSONException {
		HttpURLConnection connection = openConnection(path, deviceId);
		byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
		connection.setFixedLengthStreamingMode(bytes.length);
		try (OutputStream outputStream = connection.getOutputStream()) {
			outputStream.write(bytes);
		}
		int responseCode = connection.getResponseCode();
		String responseBody = readResponseBody(connection, responseCode);
		connection.disconnect();
		if (allowDuplicateVote && responseCode == HttpURLConnection.HTTP_CONFLICT) {
			return new JSONObject();
		}
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("RoadCrew API " + path + " failed with HTTP " + responseCode + ": " + responseBody);
		}
		return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
	}

	@NonNull
	private static JSONObject getJson(@NonNull String path, @NonNull String deviceId)
			throws IOException, JSONException {
		HttpURLConnection connection = openConnection(path, deviceId, "GET", false);
		int responseCode = connection.getResponseCode();
		String responseBody = readResponseBody(connection, responseCode);
		connection.disconnect();
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("RoadCrew API " + path + " failed with HTTP " + responseCode + ": " + responseBody);
		}
		return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
	}

	@NonNull
	private static HttpURLConnection openConnection(@NonNull String path, @NonNull String deviceId) throws IOException {
		return openConnection(path, deviceId, "POST", true);
	}

	@NonNull
	private static HttpURLConnection openConnection(@NonNull String path, @NonNull String deviceId,
			@NonNull String method, boolean doOutput) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE_URL + path).openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty(DEVICE_ID_HEADER, deviceId);
		connection.setDoOutput(doOutput);
		return connection;
	}

	@NonNull
	private static String readResponseBody(@NonNull HttpURLConnection connection, int responseCode) throws IOException {
		InputStream stream = responseCode >= 200 && responseCode < 300
				? connection.getInputStream()
				: connection.getErrorStream();
		if (stream == null) {
			return "";
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder builder = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line);
			}
			return builder.toString();
		}
	}

	public interface NotificationsCallback {
		void onNotifications(@NonNull List<RoadCrewNotification> notifications);

		void onError(@NonNull Exception error);
	}

	public interface HelpChatCallback {
		void onSuccess(@NonNull String chatRoomId);

		void onError(@NonNull Exception error);
	}

	public interface HelpReportChatCallback {
		void onSuccess(@NonNull String reportId, @NonNull String chatRoomId);

		void onError(@NonNull Exception error);
	}

	public interface HelpChatMessagesCallback {
		void onMessages(@NonNull List<RoadCrewChatMessage> messages);

		void onError(@NonNull Exception error);
	}

	public interface HelpResolveCallback {
		void onSuccess();

		void onError(@NonNull Exception error);
	}

	public static final class RoadCrewNotification {
		@NonNull
		private final String id;
		@NonNull
		private final String reportId;
		@NonNull
		private final String kind;
		@NonNull
		private final String title;
		@NonNull
		private final String body;
		private final long createdAtMillis;

		private RoadCrewNotification(@NonNull String id, @NonNull String reportId, @NonNull String kind,
				@NonNull String title, @NonNull String body, long createdAtMillis) {
			this.id = id;
			this.reportId = reportId;
			this.kind = kind;
			this.title = title;
			this.body = body;
			this.createdAtMillis = createdAtMillis;
		}

		@NonNull
		public String getId() {
			return id;
		}

		@NonNull
		public String getReportId() {
			return reportId;
		}

		@NonNull
		public String getKind() {
			return kind;
		}

		@NonNull
		public String getTitle() {
			return title;
		}

		@NonNull
		public String getBody() {
			return body;
		}

		public long getCreatedAtMillis() {
			return createdAtMillis;
		}
	}

	public static final class RoadCrewChatMessage {
		@NonNull
		private final String id;
		@NonNull
		private final String deviceId;
		@NonNull
		private final String displayName;
		@NonNull
		private final String body;
		private final long createdAtMillis;

		private RoadCrewChatMessage(@NonNull String id, @NonNull String deviceId, @NonNull String displayName,
				@NonNull String body, long createdAtMillis) {
			this.id = id;
			this.deviceId = deviceId;
			this.displayName = displayName;
			this.body = body;
			this.createdAtMillis = createdAtMillis;
		}

		@NonNull
		public String getId() {
			return id;
		}

		@NonNull
		public String getDeviceId() {
			return deviceId;
		}

		@NonNull
		public String getDisplayName() {
			return displayName;
		}

		@NonNull
		public String getBody() {
			return body;
		}

		public long getCreatedAtMillis() {
			return createdAtMillis;
		}
	}
}
