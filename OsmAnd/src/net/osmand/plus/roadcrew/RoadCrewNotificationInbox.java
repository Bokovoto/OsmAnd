package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.roadcrew.RoadCrewReportsSync.RoadCrewNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable local history for notifications addressed to this driver. */
final class RoadCrewNotificationInbox {

	private static final String PREFS = "roadcrew_driver_notification_inbox";
	private static final String ENTRIES = "entries";
	private static final int MAX_ENTRIES = 100;

	private RoadCrewNotificationInbox() {
	}

	static synchronized void store(@NonNull OsmandApplication app,
			@NonNull List<RoadCrewNotification> notifications) {
		if (notifications.isEmpty()) { return; }
		List<Entry> current = read(app);
		Map<String, Entry> merged = new LinkedHashMap<>();
		for (Entry entry : current) { merged.put(entry.id, entry); }
		for (RoadCrewNotification notification : notifications) {
			// A polled server event supersedes the temporary copy created by FCM.
			boolean pushCopyWasRead = false;
			List<String> matchingPushIds = new ArrayList<>();
			for (Entry entry : merged.values()) {
				if (entry.pushCopy && entry.kind.equals(notification.getKind())
						&& (entry.referenceId.equals(notification.getReportId())
								|| ("PLATE_SAFETY_ALERT".equals(notification.getKind())
										&& entry.referenceId.equals(notification.getId())))) {
					matchingPushIds.add(entry.id);
					pushCopyWasRead |= entry.read;
				}
			}
			for (String pushId : matchingPushIds) { merged.remove(pushId); }
			Entry previous = merged.get(notification.getId());
			merged.put(notification.getId(), new Entry(notification.getId(), notification.getReportId(),
					notification.getKind(), notification.getTitle(), notification.getBody(),
					notification.getCreatedAtMillis(), pushCopyWasRead || previous != null && previous.read, false));
		}
		write(app, merged.values());
	}

	static synchronized void storePush(@NonNull OsmandApplication app, @NonNull String kind,
			@NonNull String referenceId, @NonNull String title, @NonNull String body) {
		long now = System.currentTimeMillis();
		List<Entry> current = read(app);
		Map<String, Entry> merged = new LinkedHashMap<>();
		for (Entry entry : current) { merged.put(entry.id, entry); }
		String id = "push:" + now + ":" + Integer.toHexString((kind + referenceId + title + body).hashCode());
		merged.put(id, new Entry(id, referenceId, kind, title, body, now, false, true));
		write(app, merged.values());
	}

	static synchronized int unreadCount(@NonNull Context context) {
		int count = 0;
		for (Entry entry : read(context)) { if (!entry.read) { count++; } }
		return count;
	}

	static synchronized void markRead(@NonNull Context context, @NonNull String id) {
		List<Entry> entries = read(context);
		for (Entry entry : entries) {
			if (entry.id.equals(id)) { entry.read = true; }
		}
		write(context, entries);
	}

	static synchronized void markByReference(@NonNull Context context, @NonNull String kind,
			@NonNull String referenceId) {
		List<Entry> entries = read(context);
		for (Entry entry : entries) {
			if (entry.kind.equals(kind) && entry.referenceId.equals(referenceId)) { entry.read = true; }
		}
		write(context, entries);
	}

	private static synchronized void markAllRead(@NonNull Context context) {
		List<Entry> entries = read(context);
		for (Entry entry : entries) { entry.read = true; }
		write(context, entries);
	}

	static void show(@NonNull MapActivity activity) {
		List<Entry> entries = read(activity);
		LinearLayout content = RoadCrewUi.createPanel(activity,
				activity.getString(R.string.roadcrew_inbox_title));
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		if (entries.isEmpty()) {
			RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_inbox_empty));
		} else {
			boolean hasUnread = false;
			DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
			for (Entry entry : entries) {
				hasUnread |= !entry.read;
				String title = entry.title.isEmpty()
						? activity.getString(R.string.roadcrew_inbox_notification) : entry.title;
				String text = (!entry.read ? activity.getString(R.string.roadcrew_inbox_new) + "  " : "")
						+ title + "\n" + entry.body + "\n" + format.format(entry.createdAt);
				Button button = RoadCrewUi.addFullWidthButton(activity, content, text, false, v -> {
					markRead(activity, entry.id);
					dialog.dismiss();
					RoadCrewNeonHud.apply(activity);
					RoadCrewReportsLayer.openInboxNotification(activity, entry);
				});
				button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
				button.setTextColor(entry.read ? RoadCrewUi.TEXT : RoadCrewUi.PRIMARY);
				button.setPadding(RoadCrewUi.dp(activity, 14), RoadCrewUi.dp(activity, 10),
						RoadCrewUi.dp(activity, 14), RoadCrewUi.dp(activity, 10));
				button.setMinHeight(RoadCrewUi.dp(activity, 76));
				button.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
			}
			if (hasUnread) {
				RoadCrewUi.addFullWidthButton(activity, content,
						activity.getString(R.string.roadcrew_inbox_mark_all_read), false, v -> {
					markAllRead(activity);
					dialog.dismiss();
					RoadCrewNeonHud.apply(activity);
					show(activity);
				});
			}
		}
		RoadCrewUi.addFullWidthButton(activity, content, activity.getString(R.string.shared_string_close),
				false, v -> dialog.dismiss());
		dialog.show();
	}

	@NonNull
	private static List<Entry> read(@NonNull Context context) {
		List<Entry> entries = new ArrayList<>();
		String encoded = prefs(context).getString(ENTRIES, "[]");
		try {
			JSONArray array = new JSONArray(encoded == null ? "[]" : encoded);
			for (int i = 0; i < array.length(); i++) {
				JSONObject object = array.optJSONObject(i);
				if (object == null || object.optString("id").isEmpty()) { continue; }
				entries.add(new Entry(object.optString("id"), object.optString("referenceId"),
						object.optString("kind"), object.optString("title"), object.optString("body"),
						object.optLong("createdAt"), object.optBoolean("read"), object.optBoolean("pushCopy")));
			}
		} catch (JSONException ignored) {
			prefs(context).edit().remove(ENTRIES).apply();
		}
		entries.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
		return entries;
	}

	private static void write(@NonNull Context context, @NonNull Iterable<Entry> source) {
		List<Entry> entries = new ArrayList<>();
		for (Entry entry : source) { entries.add(entry); }
		entries.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
		JSONArray array = new JSONArray();
		for (Entry entry : entries.subList(0, Math.min(entries.size(), MAX_ENTRIES))) {
			JSONObject object = new JSONObject();
			try {
				object.put("id", entry.id).put("referenceId", entry.referenceId).put("kind", entry.kind)
						.put("title", entry.title).put("body", entry.body).put("createdAt", entry.createdAt)
						.put("read", entry.read).put("pushCopy", entry.pushCopy);
				array.put(object);
			} catch (JSONException ignored) { }
		}
		prefs(context).edit().putString(ENTRIES, array.toString()).apply();
	}

	@NonNull
	private static SharedPreferences prefs(@NonNull Context context) {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	static final class Entry {
		@NonNull final String id;
		@NonNull final String referenceId;
		@NonNull final String kind;
		@NonNull final String title;
		@NonNull final String body;
		final long createdAt;
		boolean read;
		final boolean pushCopy;

		Entry(@NonNull String id, @NonNull String referenceId, @NonNull String kind,
				@NonNull String title, @NonNull String body, long createdAt, boolean read, boolean pushCopy) {
			this.id = id;
			this.referenceId = referenceId;
			this.kind = kind;
			this.title = title;
			this.body = body;
			this.createdAt = createdAt;
			this.read = read;
			this.pushCopy = pushCopy;
		}
	}
}
