package net.osmand.plus.roadcrew.tacho;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import net.osmand.plus.R;
import net.osmand.plus.utils.AndroidUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The driver card reminder (Galin, 25.09.2026): a notification at 08:00 every
 * day from the 25th to the 30th day after the last download this phone knows
 * of - the card read in the reader, or downloaded with RoadCrew. When is in
 * {@link RoadCrewTachoReminderPlan}. One alarm at a time; each one arms the
 * next, and a reboot or an app update arms it again.
 */
public final class RoadCrewTachoReminder extends BroadcastReceiver {

	static final String PREFS = "roadcrew_tacho";
	/** LastCardDownload, seconds since 1970: the latest this phone has seen. */
	static final String PREF_LAST_DOWNLOAD = "last_download_at";

	private static final String ACTION_REMIND = "net.osmand.plus.roadcrew.tacho.REMIND";
	private static final String CHANNEL_ID = "roadcrew_tacho_reminder_v1";
	private static final int NOTIFICATION_ID = 0x7AC0;

	/** Arms the alarm for the next reminder, or clears it when none is left. */
	public static void schedule(@NonNull Context context) {
		AlarmManager alarms = context.getSystemService(AlarmManager.class);
		if (alarms == null) {
			return;
		}
		PendingIntent pending = alarmIntent(context);
		alarms.cancel(pending);
		long next = RoadCrewTachoReminderPlan.next(lastDownload(context), System.currentTimeMillis(),
				TimeZone.getDefault());
		if (next > 0) {
			// Inexact on purpose: no exact-alarm permission for a reminder a few
			// minutes either way does not change.
			alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
		}
	}

	/** Stores a download date the phone has just learned of; a newer one moves the reminders. */
	static void remember(@NonNull Context context, long epochSeconds) {
		if (epochSeconds <= lastDownload(context)) {
			return;
		}
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
				.putLong(PREF_LAST_DOWNLOAD, epochSeconds).apply();
		NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
		schedule(context);
	}

	static long lastDownload(@NonNull Context context) {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(PREF_LAST_DOWNLOAD, 0);
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		if (ACTION_REMIND.equals(intent.getAction())) {
			long last = lastDownload(context);
			long now = System.currentTimeMillis();
			if (RoadCrewTachoReminderPlan.remindsOn(last, now, TimeZone.getDefault())) {
				show(context, last, now);
			}
		}
		// The next day's reminder - or the first one again after a reboot or an update.
		schedule(context);
	}

	private static PendingIntent alarmIntent(@NonNull Context context) {
		Intent intent = new Intent(context, RoadCrewTachoReminder.class).setAction(ACTION_REMIND);
		return PendingIntent.getBroadcast(context, NOTIFICATION_ID, intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private static void show(@NonNull Context context, long last, long now) {
		if (!AndroidUtils.hasPostNotificationPermission(context)) {
			return;
		}
		ensureChannel(context);
		TimeZone zone = TimeZone.getDefault();
		String deadline = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
				.format(new Date(RoadCrewTachoReminderPlan.deadline(last, zone)));
		int left = RoadCrewTachoReminderPlan.daysLeft(last, now, zone);
		String body;
		if (left > 0) {
			body = context.getResources().getQuantityString(R.plurals.roadcrew_tacho_reminder_left, left, deadline, left);
		} else if (left == 0) {
			body = context.getString(R.string.roadcrew_tacho_reminder_today, deadline);
		} else {
			body = context.getString(R.string.roadcrew_tacho_reminder_overdue, deadline);
		}
		Intent open = new Intent(context, RoadCrewTachoCardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
				.setSmallIcon(R.drawable.roadcrew_tacho_ic_card)
				.setContentTitle(context.getString(R.string.roadcrew_tacho_reminder_title))
				.setContentText(body)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(body))
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setAutoCancel(true)
				.setContentIntent(content);
		NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
	}

	private static void ensureChannel(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationManager manager = context.getSystemService(NotificationManager.class);
		if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
				context.getString(R.string.roadcrew_tacho_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT);
		channel.setDescription(context.getString(R.string.roadcrew_tacho_reminder_channel_description));
		manager.createNotificationChannel(channel);
	}
}
