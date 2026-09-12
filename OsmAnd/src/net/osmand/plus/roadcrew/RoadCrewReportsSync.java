package net.osmand.plus.roadcrew;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
	private static final RoadCrewProfileSyncState profileSyncState = new RoadCrewProfileSyncState();
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
				RoadCrewHelpResolveTarget target = RoadCrewReportsRepository.findHelpResolveTarget(app, report);
				if (target.kind == RoadCrewHelpResolveTarget.Kind.UNKNOWN) {
					// Never "closed" without the server: it would stay open for every driver.
					IOException error = new IOException("No server id for help " + originalReportId);
					Log.w(TAG, "RoadCrew resolve help failed", error);
					app.runInUIThread(() -> callback.onError(error));
					return;
				}
				if (target.kind == RoadCrewHelpResolveTarget.Kind.LOCAL_ONLY) {
					RoadCrewReportsRepository.removeReport(app, originalReportId);
					app.runInUIThread(callback::onSuccess);
					return;
				}
				String reportId = target.serverId;
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
		String payload = body.toString();
		long now = SystemClock.elapsedRealtime();
		if (!profileSyncState.beginSync(deviceId, payload, now)) {
			return;
		}
		JSONObject response = postJson("/v1/devices/profile", deviceId, body);
		if (!Boolean.TRUE.equals(response.opt("ok"))) {
			throw new IOException("Profile update was not acknowledged");
		}
		profileSyncState.acknowledge(deviceId, payload, now);
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
				try {
					syncRemoteVote(deviceId, report, report.getId());
				} catch (ReportNoLongerActiveException e) {
					// Closed on the server: the vote has nowhere to go. Drop the local copy
					// and go on - one closed report must not stall the others.
					Log.i(TAG, "RoadCrew vote dropped, report no longer active: " + report.getId());
					RoadCrewReportsRepository.removeReport(app, report.getId());
					continue;
				}
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
					RoadCrewReportDirection.parse(object.optString("direction", RoadCrewReportDirection.UNKNOWN.name())),
					object.has("directionBearing") ? (float) object.optDouble("directionBearing", Double.NaN) : Float.NaN,
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
		body.put("direction", report.getDirection().name());
		if (report.hasDirectionBearing()) {
			body.put("directionBearing", report.getDirectionBearing());
		}

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
		// A 409 on a vote is success only when the server says it is a duplicate. A
		// report that is no longer active is not "sent": taking it for success recorded
		// votes on closed requests as delivered, and failing the whole sync on it would
		// stall every other pending report. syncPendingReports handles it per report.
		if (allowDuplicateVote && responseCode == HttpURLConnection.HTTP_CONFLICT) {
			if (isDuplicateVote(responseBody)) {
				return new JSONObject();
			}
			throw new ReportNoLongerActiveException(path, responseBody);
		}
		if (responseCode < 200 || responseCode >= 300) {
			throw new IOException("RoadCrew API " + path + " failed with HTTP " + responseCode + ": " + responseBody);
		}
		return responseBody.isEmpty() ? new JSONObject() : new JSONObject(responseBody);
	}

	public interface ReportLookupCallback {
		void onFound(@NonNull RoadCrewReport report);

		void onClosed();

		void onNotFound();

		void onError(@NonNull Exception error);
	}

	/**
	 * One report by its id, whatever its status - so a notice can open its request on
	 * a phone that has no nearby list: just started, or the author has driven on. An
	 * active report is taken as the server holds it; a closed one is only reported.
	 */
	public static void fetchReport(@NonNull OsmandApplication app, @NonNull String reportId,
			@NonNull ReportLookupCallback callback) {
		EXECUTOR.execute(() -> {
			try {
				String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
				HttpURLConnection connection = openConnection("/v1/reports/" + reportId, deviceId, "GET", false);
				int responseCode = connection.getResponseCode();
				String responseBody = readResponseBody(connection, responseCode);
				connection.disconnect();
				if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
					app.runInUIThread(callback::onNotFound);
					return;
				}
				if (responseCode < 200 || responseCode >= 300) {
					throw new IOException("RoadCrew API report " + reportId + " failed with HTTP " + responseCode);
				}
				JSONObject json = new JSONObject(responseBody).optJSONObject("report");
				if (json == null) {
					app.runInUIThread(callback::onNotFound);
					return;
				}
				if (!"ACTIVE".equals(json.optString("status", ""))) {
					RoadCrewReportsRepository.removeReport(app, reportId);
					app.runInUIThread(callback::onClosed);
					return;
				}
				RoadCrewReport report = readRemoteReport(json);
				if (report == null) {
					app.runInUIThread(callback::onNotFound);
					return;
				}
				RoadCrewReportsRepository.applyServerState(app, report);
				app.runInUIThread(() -> callback.onFound(report));
			} catch (IOException | JSONException e) {
				Log.w(TAG, "RoadCrew report lookup failed", e);
				app.runInUIThread(() -> callback.onError(e));
			}
		});
	}

	public interface HelpAnswerCallback {
		void onOutcome(@NonNull HelpAnswerOutcome outcome);
	}

	/**
	 * The author's "still need help", as its own command rather than a stored vote.
	 *
	 * A stored vote is one per report and device, and the author must be able to
	 * answer every clock. It goes out at once and reports what the server did; the
	 * report is then refreshed from the server, so the map shows the truth rather
	 * than an optimistic local colour. There is no automatic retry: a lost reply is
	 * UNCONFIRMED and said so. {@code clock} names the clock being answered, or null
	 * for the current one.
	 */
	public static void answerHelpClock(@NonNull OsmandApplication app, @NonNull String reportId,
			@Nullable Integer clock, @NonNull HelpAnswerCallback callback) {
		EXECUTOR.execute(() -> {
			HelpAnswerOutcome outcome = answerHelpClockBlocking(app, reportId, clock, 0, 0, true);
			app.runInUIThread(() -> callback.onOutcome(outcome));
		});
	}

	/**
	 * The same, on the caller's thread; timeouts of 0 keep the defaults. A notice's
	 * button has about ten seconds in all, so it passes short timeouts and no refresh:
	 * the report is refreshed by the next sync instead of by a second request here.
	 */
	@NonNull
	public static HelpAnswerOutcome answerHelpClockBlocking(@NonNull OsmandApplication app,
			@NonNull String reportId, @Nullable Integer clock, int connectTimeoutMillis, int readTimeoutMillis,
			boolean refreshAfter) {
		try {
			String deviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
			JSONObject body = new JSONObject();
			body.put("vote", "CONFIRMED");
			if (clock != null) {
				body.put("clock", clock.intValue());
			}
			HttpURLConnection connection = openConnection("/v1/reports/" + reportId + "/votes", deviceId);
			if (connectTimeoutMillis > 0) {
				connection.setConnectTimeout(connectTimeoutMillis);
			}
			if (readTimeoutMillis > 0) {
				connection.setReadTimeout(readTimeoutMillis);
			}
			byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(bytes.length);
			try (OutputStream outputStream = connection.getOutputStream()) {
				outputStream.write(bytes);
			}
			int responseCode = connection.getResponseCode();
			String responseBody = readResponseBody(connection, responseCode);
			connection.disconnect();
			String result = "";
			try {
				result = responseBody.isEmpty() ? "" : new JSONObject(responseBody).optString("result", "");
			} catch (JSONException e) {
				result = "";
			}
			HelpAnswerOutcome outcome = HelpAnswerOutcome.from(responseCode, result);
			if (outcome.requestIsActive() && refreshAfter) {
				refreshReportFromServer(app, deviceId, reportId);
			} else if (outcome.requestIsClosed()) {
				RoadCrewReportsRepository.removeReport(app, reportId);
				app.runInUIThread(() -> app.getOsmandMap().refreshMap());
			}
			return outcome;
		} catch (IOException | JSONException e) {
			Log.w(TAG, "RoadCrew Help answer not confirmed", e);
			return HelpAnswerOutcome.UNCONFIRMED;
		}
	}

	/** One report, as the server holds it now; a failure here leaves the local copy as it was. */
	private static void refreshReportFromServer(@NonNull OsmandApplication app, @NonNull String deviceId,
			@NonNull String reportId) {
		try {
			JSONObject response = getJson("/v1/reports/" + reportId, deviceId);
			JSONObject json = response.optJSONObject("report");
			RoadCrewReport report = json == null ? null : readRemoteReport(json);
			if (report != null) {
				RoadCrewReportsRepository.applyServerState(app, report);
				app.runInUIThread(() -> app.getOsmandMap().refreshMap());
			}
		} catch (IOException | JSONException e) {
			Log.w(TAG, "RoadCrew report refresh failed", e);
		}
	}

	private static boolean isDuplicateVote(@NonNull String responseBody) {
		try {
			return !responseBody.isEmpty() && new JSONObject(responseBody).optBoolean("duplicate", false);
		} catch (JSONException e) {
			return false;
		}
	}

	/** The server refused a vote because the report is no longer active. */
	static final class ReportNoLongerActiveException extends IOException {
		ReportNoLongerActiveException(@NonNull String path, @NonNull String body) {
			super("RoadCrew API " + path + " refused, report no longer active: " + body);
		}
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

		RoadCrewNotification(@NonNull String id, @NonNull String reportId, @NonNull String kind,
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
