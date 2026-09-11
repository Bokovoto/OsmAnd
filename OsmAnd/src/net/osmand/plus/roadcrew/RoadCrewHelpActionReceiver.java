package net.osmand.plus.roadcrew;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;

import java.util.List;

/**
 * A tap on "Все още ми трябва помощ" in the notice.
 *
 * goAsync keeps the process for one short attempt - the budget is about ten
 * seconds in all, so the connect and read timeouts together stay under it - and
 * finish() runs on every path. There is no durable queue behind this: a reply
 * that never comes back is shown as unconfirmed, not as done. Authorship is
 * checked by the server, not trusted from the intent.
 */
public class RoadCrewHelpActionReceiver extends BroadcastReceiver {

	private static final int CONNECT_TIMEOUT_MILLIS = 4_000;
	private static final int READ_TIMEOUT_MILLIS = 4_000;

	@Override
	public void onReceive(@NonNull Context context, @NonNull Intent intent) {
		Uri data = intent.getData();
		if (!RoadCrewHelpNotice.ACTION_STILL_NEEDED.equals(intent.getAction()) || data == null) {
			return;
		}
		List<String> segments = data.getPathSegments();
		if (!"help".equals(data.getHost()) || segments.size() != 3 || !"clock".equals(segments.get(1))) {
			return;
		}
		String reportId = segments.get(0);
		int clock;
		try {
			clock = Integer.parseInt(segments.get(2));
		} catch (NumberFormatException e) {
			return;
		}
		Context appContext = context.getApplicationContext();
		OsmandApplication app = (OsmandApplication) appContext;
		PendingResult pending = goAsync();
		new Thread(() -> {
			HelpAnswerOutcome outcome = HelpAnswerOutcome.UNCONFIRMED;
			try {
				outcome = RoadCrewReportsSync.answerHelpClockBlocking(app, reportId, clock,
						CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS, false);
			} finally {
				try {
					RoadCrewHelpNotice.showOutcome(appContext, reportId, clock, outcome);
				} finally {
					pending.finish();
				}
			}
		}, "RoadCrewHelpAnswer").start();
	}
}
