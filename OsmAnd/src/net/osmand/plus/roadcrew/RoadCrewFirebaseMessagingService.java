package net.osmand.plus.roadcrew;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.AndroidUtils;

import java.util.Map;

public class RoadCrewFirebaseMessagingService extends FirebaseMessagingService {

	private static final String CHANNEL_ID = "roadcrew_alerts_sound_v1";
	private static final int NOTIFICATION_ID_BASE = 42000;

	@Override
	public void onNewToken(@NonNull String token) {
		super.onNewToken(token);
		OsmandApplication app = (OsmandApplication) getApplication();
		RoadCrewPushNotifications.registerToken(app, token);
	}

	@Override
	public void onMessageReceived(@NonNull RemoteMessage message) {
		super.onMessageReceived(message);
		if (!RoadCrewReportsLayer.isEnabled((OsmandApplication) getApplication())) {
			return;
		}
		Map<String, String> data = message.getData();
		String title = valueOrDefault(data.get("title"), "RoadCrew");
		String body = valueOrDefault(data.get("body"), getString(R.string.roadcrew_push_default_body));
		String kind = valueOrDefault(data.get("kind"), "ROADCREW_EVENT");
		String referenceId = valueOrDefault(data.get("referenceId"), "");
		showNotification(title, body, kind, referenceId);
	}

	private void showNotification(@NonNull String title, @NonNull String body,
			@NonNull String kind, @NonNull String referenceId) {
		if (!AndroidUtils.hasPostNotificationPermission(this)) {
			return;
		}
		ensureChannel();

		Intent intent = new Intent(this, MapActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		intent.putExtra("roadcrew_push_kind", kind);
		intent.putExtra("roadcrew_push_reference_id", referenceId);
		int notificationId = notificationId(kind, referenceId);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_roadcrew_report)
				.setContentTitle(title)
				.setContentText(body)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(body))
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setDefaults(NotificationCompat.DEFAULT_ALL)
				.setSound(defaultNotificationSound())
				.setAutoCancel(true)
				.setContentIntent(pendingIntent);
		NotificationManagerCompat.from(this).notify(notificationId, builder.build());
	}

	private void ensureChannel() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
			return;
		}
		NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID,
				getString(R.string.roadcrew_notification_channel_name),
				NotificationManager.IMPORTANCE_HIGH);
		channel.setDescription(getString(R.string.roadcrew_notification_channel_description));
		channel.enableVibration(true);
		channel.setSound(defaultNotificationSound(), new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build());
		manager.createNotificationChannel(channel);
	}

	@NonNull
	private Uri defaultNotificationSound() {
		return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
	}

	private int notificationId(@NonNull String kind, @NonNull String referenceId) {
		return NOTIFICATION_ID_BASE + Math.abs((kind + referenceId).hashCode() % 10000);
	}

	@NonNull
	private String valueOrDefault(String value, @NonNull String fallback) {
		return value == null || value.trim().isEmpty() ? fallback : value;
	}
}
