package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RoadCrewReportsRepository {

	private static final String PREFS_NAME = "roadcrew_reports";
	private static final String KEY_REPORTS_JSON = "reports_json";
	private static final String KEY_LOCAL_DEVICE_ID = "local_device_id";
	private static final String KEY_SYNCED_IDS_JSON = "synced_ids_json";
	private static final int MAX_SYNCED_IDS = 50;

	private static final List<RoadCrewReport> REPORTS = new ArrayList<>();
	// Deliberately not restored: after a restart a pending POST may have committed.
	private static final Set<String> NEVER_ATTEMPTED_IDS = new HashSet<>();
	private static boolean loaded;

	private RoadCrewReportsRepository() {
	}

	public static synchronized void addReport(@NonNull OsmandApplication app, @NonNull RoadCrewReport report) {
		ensureLoaded(app);
		REPORTS.add(report);
		if (report.getSyncState() == RoadCrewReportSyncState.PENDING_CREATE
				&& report.getId().startsWith("local-")) {
			NEVER_ATTEMPTED_IDS.add(report.getId());
		}
		save(app);
	}

	static synchronized void beginReportCreate(@NonNull String reportId) {
		NEVER_ATTEMPTED_IDS.remove(reportId);
	}

	public static synchronized boolean confirmReport(@NonNull OsmandApplication app, @NonNull String reportId) {
		return updateReportVote(app, reportId, true);
	}

	public static synchronized boolean denyReport(@NonNull OsmandApplication app, @NonNull String reportId) {
		return updateReportVote(app, reportId, false);
	}

	public static synchronized boolean markReportSynced(@NonNull OsmandApplication app, @NonNull String reportId,
			@NonNull String syncedReportId, long syncedExpiresAtMillis) {
		ensureLoaded(app);
		for (int i = 0; i < REPORTS.size(); i++) {
			RoadCrewReport report = REPORTS.get(i);
			if (report.getId().equals(reportId)) {
				REPORTS.set(i, report.withSynced(syncedReportId, syncedExpiresAtMillis));
				NEVER_ATTEMPTED_IDS.remove(reportId);
				save(app);
				rememberSyncedId(app, reportId, syncedReportId);
				return true;
			}
		}
		return false;
	}

	public static synchronized boolean removeReport(@NonNull OsmandApplication app, @NonNull String reportId) {
		ensureLoaded(app);
		for (int i = 0; i < REPORTS.size(); i++) {
			if (REPORTS.get(i).getId().equals(reportId)) {
				REPORTS.remove(i);
				NEVER_ATTEMPTED_IDS.remove(reportId);
				save(app);
				return true;
			}
		}
		return false;
	}

	public static synchronized boolean mergeRemoteReports(@NonNull OsmandApplication app,
			@NonNull List<RoadCrewReport> remoteReports) {
		ensureLoaded(app);
		boolean changed = false;
		for (RoadCrewReport remoteReport : remoteReports) {
			int index = findReportIndex(remoteReport.getId());
			if (index == -1) {
				REPORTS.add(remoteReport);
				changed = true;
				continue;
			}
			RoadCrewReport existingReport = REPORTS.get(index);
			if (existingReport.getSyncState() == RoadCrewReportSyncState.PENDING_CREATE
					|| existingReport.getSyncState() == RoadCrewReportSyncState.PENDING_UPDATE
					|| existingReport.getSyncState() == RoadCrewReportSyncState.PENDING_DELETE) {
				continue;
			}
			RoadCrewReport mergedReport = remoteReport.withLocalVote(existingReport.getLocalVote());
			if (!hasSameServerState(existingReport, mergedReport)) {
				REPORTS.set(index, mergedReport);
				changed = true;
			}
		}
		if (changed) {
			save(app);
		}
		return changed;
	}

	/**
	 * One report as the server now holds it, after an action whose outcome the server
	 * has just stated. Unlike mergeRemoteReports it does not skip a pending local
	 * copy: for this report the server's answer is the newer truth. The local vote
	 * value is kept.
	 */
	public static synchronized void applyServerState(@NonNull OsmandApplication app,
			@NonNull RoadCrewReport remoteReport) {
		ensureLoaded(app);
		int index = findReportIndex(remoteReport.getId());
		if (index == -1) {
			REPORTS.add(remoteReport);
		} else {
			REPORTS.set(index, remoteReport.withLocalVote(REPORTS.get(index).getLocalVote()));
		}
		save(app);
	}

	@NonNull
	public static synchronized List<RoadCrewReport> getReports(@NonNull OsmandApplication app) {
		ensureLoaded(app);
		pruneExpiredReports(app);
		return Collections.unmodifiableList(new ArrayList<>(REPORTS));
	}

	@NonNull
	public static synchronized List<RoadCrewReport> getVisibleReports(@NonNull OsmandApplication app) {
		ensureLoaded(app);
		pruneExpiredReports(app);
		List<RoadCrewReport> visibleReports = new ArrayList<>();
		for (RoadCrewReport report : REPORTS) {
			if (!report.shouldHideLocally()) {
				visibleReports.add(report);
			}
		}
		return Collections.unmodifiableList(visibleReports);
	}

	@NonNull
	public static synchronized String getLocalDeviceId(@NonNull OsmandApplication app) {
		SharedPreferences preferences = getPreferences(app);
		String localDeviceId = preferences.getString(KEY_LOCAL_DEVICE_ID, null);
		if (localDeviceId == null) {
			localDeviceId = "device-" + UUID.randomUUID();
			preferences.edit().putString(KEY_LOCAL_DEVICE_ID, localDeviceId).commit();
		}
		return localDeviceId;
	}

	@NonNull
	public static synchronized String findSyncedReportIdMatching(@NonNull OsmandApplication app,
			@NonNull RoadCrewReport localReport) {
		ensureLoaded(app);
		for (RoadCrewReport report : REPORTS) {
			if (report.getSyncState() == RoadCrewReportSyncState.SYNCED
					&& !report.getId().startsWith("local-")
					&& !report.getId().startsWith("seed-")
					&& isSameReportContent(report, localReport)) {
				return report.getId();
			}
		}
		return "";
	}

	/**
	 * The server request "Приключи" closes for a Help that may still be held under
	 * its local id: dialogs and the map keep the report they were opened with,
	 * while the sync renames it to the server id and the next fetch replaces its
	 * content with the server's copy.
	 */
	@NonNull
	static synchronized RoadCrewHelpResolveTarget findHelpResolveTarget(@NonNull OsmandApplication app,
			@NonNull RoadCrewReport report) {
		ensureLoaded(app);
		String reportId = report.getId();
		int index = findReportIndex(reportId);
		boolean stillPendingLocally = index != -1
				&& REPORTS.get(index).getSyncState() == RoadCrewReportSyncState.PENDING_CREATE;
		return RoadCrewHelpResolveTarget.choose(reportId,
				stillPendingLocally && NEVER_ATTEMPTED_IDS.contains(reportId), syncedIdFor(app, reportId),
				findSyncedReportIdMatching(app, report));
	}

	/** Which server id a local report became; kept for the last MAX_SYNCED_IDS reports. */
	static synchronized void rememberSyncedId(@NonNull OsmandApplication app, @NonNull String localId,
			@NonNull String syncedId) {
		if (localId.equals(syncedId) || RoadCrewHelpResolveTarget.isServerId(localId)) {
			return;
		}
		JSONObject ids = readSyncedIds(app);
		if (syncedId.equals(ids.optString(localId, ""))) {
			return;
		}
		try {
			ids.put(localId, syncedId);
		} catch (JSONException ignored) {
			return;
		}
		while (ids.length() > MAX_SYNCED_IDS) {
			Iterator<String> oldest = ids.keys();
			ids.remove(oldest.next());
		}
		getPreferences(app).edit().putString(KEY_SYNCED_IDS_JSON, ids.toString()).commit();
	}

	@NonNull
	private static String syncedIdFor(@NonNull OsmandApplication app, @NonNull String localId) {
		return readSyncedIds(app).optString(localId, "");
	}

	@NonNull
	private static JSONObject readSyncedIds(@NonNull OsmandApplication app) {
		String json = getPreferences(app).getString(KEY_SYNCED_IDS_JSON, null);
		if (json != null) {
			try {
				return new JSONObject(json);
			} catch (JSONException ignored) {
				// Unreadable: start over; the fallbacks in findHelpResolveTarget still apply.
			}
		}
		return new JSONObject();
	}

	private static void ensureLoaded(@NonNull OsmandApplication app) {
		if (loaded) {
			return;
		}
		REPORTS.clear();
		loadPersistedReports(app);
		loaded = true;
	}

	private static boolean updateReportVote(@NonNull OsmandApplication app, @NonNull String reportId, boolean confirmed) {
		ensureLoaded(app);
		for (int i = 0; i < REPORTS.size(); i++) {
			RoadCrewReport report = REPORTS.get(i);
			if (report.getId().equals(reportId)) {
				if (report.hasLocalVote()) {
					return false;
				}
				RoadCrewReport updatedReport = report.withVote(confirmed);
				REPORTS.set(i, updatedReport);
				save(app);
				return true;
			}
		}
		return false;
	}

	private static int findReportIndex(@NonNull String reportId) {
		for (int i = 0; i < REPORTS.size(); i++) {
			if (REPORTS.get(i).getId().equals(reportId)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean hasSameServerState(@NonNull RoadCrewReport first, @NonNull RoadCrewReport second) {
		LatLon firstLocation = first.getLocation();
		LatLon secondLocation = second.getLocation();
		return first.getId().equals(second.getId())
				&& first.getType() == second.getType()
				&& firstLocation.getLatitude() == secondLocation.getLatitude()
				&& firstLocation.getLongitude() == secondLocation.getLongitude()
				&& first.getCreatedAtMillis() == second.getCreatedAtMillis()
				&& first.getExpiresAtMillis() == second.getExpiresAtMillis()
				&& first.getCreatedBy().equals(second.getCreatedBy())
				&& first.getDetails().equals(second.getDetails())
				&& first.getDirection() == second.getDirection()
				&& Float.compare(first.getDirectionBearing(), second.getDirectionBearing()) == 0
				&& first.getSyncState() == second.getSyncState()
				&& first.getConfirmedCount() == second.getConfirmedCount()
				&& first.getDeniedCount() == second.getDeniedCount()
				&& first.getLocalVote() == second.getLocalVote()
				&& first.getProbablyResolvedAtMillis() == second.getProbablyResolvedAtMillis();
	}

	private static boolean isSameReportContent(@NonNull RoadCrewReport first, @NonNull RoadCrewReport second) {
		LatLon firstLocation = first.getLocation();
		LatLon secondLocation = second.getLocation();
		return first.getType() == second.getType()
				&& first.getCreatedAtMillis() == second.getCreatedAtMillis()
				&& first.getCreatedBy().equals(second.getCreatedBy())
				&& first.getDetails().equals(second.getDetails())
				&& first.getDirection() == second.getDirection()
				&& Float.compare(first.getDirectionBearing(), second.getDirectionBearing()) == 0
				&& firstLocation.getLatitude() == secondLocation.getLatitude()
				&& firstLocation.getLongitude() == secondLocation.getLongitude();
	}

	private static void loadPersistedReports(@NonNull OsmandApplication app) {
		String reportsJson = getPreferences(app).getString(KEY_REPORTS_JSON, null);
		if (reportsJson == null) {
			return;
		}
		try {
			JSONArray array = new JSONArray(reportsJson);
			boolean saveMigratedReports = false;
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.getJSONObject(i);
				saveMigratedReports |= !object.has("localVote");
				RoadCrewReport report = readReport(app, object);
				if (report != null) {
					REPORTS.add(report);
					saveMigratedReports |= object.optInt("confirmedCount", 0) != report.getConfirmedCount()
							|| object.optInt("deniedCount", 0) != report.getDeniedCount()
							|| !object.optString("localVote", RoadCrewReportLocalVote.NONE.name())
							.equals(report.getLocalVote().name());
				}
			}
			if (saveMigratedReports) {
				save(app);
			}
		} catch (JSONException e) {
			getPreferences(app).edit().remove(KEY_REPORTS_JSON).apply();
		}
	}

	private static void save(@NonNull OsmandApplication app) {
		JSONArray array = new JSONArray();
		for (int i = 0; i < REPORTS.size(); i++) {
			RoadCrewReport report = REPORTS.get(i);
			if (!report.isExpired(System.currentTimeMillis())) {
				array.put(writeReport(report));
			}
		}
		getPreferences(app).edit().putString(KEY_REPORTS_JSON, array.toString()).commit();
	}

	private static void pruneExpiredReports(@NonNull OsmandApplication app) {
		boolean changed = false;
		long now = System.currentTimeMillis();
		Iterator<RoadCrewReport> iterator = REPORTS.iterator();
		while (iterator.hasNext()) {
			RoadCrewReport report = iterator.next();
			if (report.isExpired(now)) {
				iterator.remove();
				NEVER_ATTEMPTED_IDS.remove(report.getId());
				changed = true;
			}
		}
		if (changed) {
			save(app);
		}
	}

	@NonNull
	private static SharedPreferences getPreferences(@NonNull OsmandApplication app) {
		return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	@NonNull
	private static JSONObject writeReport(@NonNull RoadCrewReport report) {
		JSONObject object = new JSONObject();
		try {
			LatLon location = report.getLocation();
			object.put("id", report.getId());
			object.put("type", report.getType().name());
			object.put("lat", location.getLatitude());
			object.put("lon", location.getLongitude());
			object.put("createdAtMillis", report.getCreatedAtMillis());
			object.put("expiresAtMillis", report.getExpiresAtMillis());
			object.put("createdBy", report.getCreatedBy());
			object.put("details", report.getDetails());
			object.put("direction", report.getDirection().name());
			if (report.hasDirectionBearing()) {
				object.put("directionBearing", report.getDirectionBearing());
			}
			object.put("syncState", report.getSyncState().name());
			object.put("confirmedCount", report.getConfirmedCount());
			object.put("deniedCount", report.getDeniedCount());
			object.put("localVote", report.getLocalVote().name());
			object.put("probablyResolvedAtMillis", report.getProbablyResolvedAtMillis());
		} catch (JSONException ignored) {
			// JSONObject backed by a Map should not fail for primitive values.
		}
		return object;
	}

	private static RoadCrewReport readReport(@NonNull OsmandApplication app, @NonNull JSONObject object) {
		try {
			RoadCrewReportType type = RoadCrewReportType.valueOf(object.getString("type"));
			LatLon location = new LatLon(object.getDouble("lat"), object.getDouble("lon"));
			long createdAtMillis = object.getLong("createdAtMillis");
			String localDeviceId = getLocalDeviceId(app);
			String createdBy = object.optString("createdBy", localDeviceId);
			int confirmedCount = object.optInt("confirmedCount", 0);
			int deniedCount = object.optInt("deniedCount", 0);
			RoadCrewReportSyncState syncState =
					readSyncState(object.optString("syncState", RoadCrewReportSyncState.PENDING_CREATE.name()));
			RoadCrewReportLocalVote localVote = readLocalVote(object, createdBy, localDeviceId,
					confirmedCount, deniedCount);
			if (syncState == RoadCrewReportSyncState.PENDING_CREATE && localDeviceId.equals(createdBy)) {
				confirmedCount = localVote == RoadCrewReportLocalVote.CONFIRMED ? 1 : 0;
				deniedCount = localVote == RoadCrewReportLocalVote.DENIED ? 1 : 0;
			}
			return new RoadCrewReport(
					object.optString("id", "local-" + UUID.randomUUID()),
					type,
					location,
					createdAtMillis,
					object.optLong("expiresAtMillis", createdAtMillis + type.getDefaultLifetimeMillis()),
					createdBy,
					object.optString("details", ""),
					RoadCrewReportDirection.parse(object.optString("direction", RoadCrewReportDirection.UNKNOWN.name())),
					object.has("directionBearing") ? (float) object.optDouble("directionBearing", Double.NaN) : Float.NaN,
					syncState,
					confirmedCount,
					deniedCount,
					localVote,
					object.optLong("probablyResolvedAtMillis", 0)
			);
		} catch (IllegalArgumentException | JSONException e) {
			return null;
		}
	}

	@NonNull
	private static RoadCrewReportSyncState readSyncState(@NonNull String value) {
		try {
			return RoadCrewReportSyncState.valueOf(value);
		} catch (IllegalArgumentException e) {
			return RoadCrewReportSyncState.PENDING_CREATE;
		}
	}

	@NonNull
	private static RoadCrewReportLocalVote readLocalVote(@NonNull JSONObject object, @NonNull String createdBy,
			@NonNull String localDeviceId, int confirmedCount, int deniedCount) {
		if (!object.has("localVote")) {
			if (localDeviceId.equals(createdBy)) {
				if (deniedCount > 0) {
					return RoadCrewReportLocalVote.DENIED;
				}
				if (confirmedCount > 0) {
					return RoadCrewReportLocalVote.CONFIRMED;
				}
			}
			return RoadCrewReportLocalVote.NONE;
		}
		try {
			return RoadCrewReportLocalVote.valueOf(object.optString("localVote", RoadCrewReportLocalVote.NONE.name()));
		} catch (IllegalArgumentException e) {
			return RoadCrewReportLocalVote.NONE;
		}
	}
}
