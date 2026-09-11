package net.osmand.plus.roadcrew;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.AndroidUtils;

/**
 * The notice "someone marked your Help request resolved" and its two buttons
 * (ROADMAP 238, decisions 2 and the confirmation that followed).
 *
 * "Все още ми трябва помощ" is a broadcast to RoadCrewHelpActionReceiver and answers
 * in the background. "Проблем е решен" is not: it ends the request and closes its
 * chat with no undo, so it opens the app on the same confirmation the panel asks.
 * Each button's PendingIntent is distinct per report, per action and - for the
 * answer - per clock, so two notices never share one; and an answer to an old clock
 * never overwrites the notice of a newer one.
 */
final class RoadCrewHelpNotice {

	static final String ACTION_STILL_NEEDED = "org.roadcrew.app.action.HELP_STILL_NEEDED";
	private static final String ACTION_RESOLVE_PREFIX = "org.roadcrew.app.action.HELP_RESOLVE:";
	static final int NOTICE_ID = 43000;
	private static final String PREFERENCES = "roadcrew_help_notice";

	private RoadCrewHelpNotice() {
	}

	@NonNull
	static String tag(@NonNull String reportId) {
		return "roadcrew-help:" + reportId;
	}

	@NonNull
	static Uri answerUri(@NonNull String reportId, int clock) {
		return Uri.parse("roadcrew://help/" + Uri.encode(reportId) + "/clock/" + clock);
	}

	/** Adds both buttons, and remembers which clock the notice for this report now answers. */
	static void addActions(@NonNull Context context, @NonNull NotificationCompat.Builder builder,
			@NonNull String reportId, int clock) {
		preferences(context).edit().putInt(reportId, clock).apply();

		Intent answer = new Intent(context, RoadCrewHelpActionReceiver.class)
				.setAction(ACTION_STILL_NEEDED)
				.setData(answerUri(reportId, clock));
		PendingIntent answerIntent = PendingIntent.getBroadcast(context, 0, answer,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		Intent resolve = new Intent(context, MapActivity.class)
				.setAction(ACTION_RESOLVE_PREFIX + reportId)
				.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
				.putExtra(RoadCrewReportsLayer.PUSH_KIND_EXTRA, RoadCrewReportsLayer.KIND_HELP_RESOLVE_CONFIRM)
				.putExtra(RoadCrewReportsLayer.PUSH_REFERENCE_ID_EXTRA, reportId);
		PendingIntent resolveIntent = PendingIntent.getActivity(context, 0, resolve,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		builder.addAction(R.drawable.ic_roadcrew_report,
				context.getString(R.string.roadcrew_help_still_need_help), answerIntent);
		builder.addAction(R.drawable.ic_roadcrew_report,
				context.getString(R.string.roadcrew_help_notice_problem_solved), resolveIntent);
	}

	static void cancel(@NonNull Context context, @NonNull String reportId) {
		NotificationManagerCompat.from(context).cancel(tag(reportId), NOTICE_ID);
	}

	/**
	 * Replaces the notice with what the answer did - without its buttons - unless a
	 * notice for a newer clock has taken its place: an old answer must not erase it.
	 */
	static void showOutcome(@NonNull Context context, @NonNull String reportId, int clock,
			@NonNull HelpAnswerOutcome outcome) {
		if (preferences(context).getInt(reportId, -1) != clock
				|| !AndroidUtils.hasPostNotificationPermission(context)) {
			return;
		}
		Intent open = new Intent(context, MapActivity.class)
				.setAction("org.roadcrew.app.action.HELP_OPEN:" + reportId)
				.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
				.putExtra(RoadCrewReportsLayer.PUSH_KIND_EXTRA, RoadCrewReportsLayer.KIND_HELP_PROBABLY_RESOLVED)
				.putExtra(RoadCrewReportsLayer.PUSH_REFERENCE_ID_EXTRA, reportId);
		PendingIntent openIntent = PendingIntent.getActivity(context, 0, open,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		String text = context.getString(RoadCrewReportsLayer.helpAnswerMessage(outcome));
		NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
				RoadCrewFirebaseMessagingService.CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_roadcrew_report)
				.setContentTitle(context.getString(R.string.roadcrew_help_notice_outcome_title))
				.setContentText(text)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(text))
				.setOnlyAlertOnce(true)
				.setSilent(true)
				.setAutoCancel(true)
				.setContentIntent(openIntent);
		NotificationManagerCompat.from(context).notify(tag(reportId), NOTICE_ID, builder.build());
	}

	@NonNull
	private static SharedPreferences preferences(@NonNull Context context) {
		return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
	}
}
