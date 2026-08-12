package net.osmand.plus.roadcrew;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import net.osmand.plus.OsmandApplication;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public final class RoadCrewPushNotifications {

	private static final String TAG = "RoadCrewPush";
	private static String lastRequestedToken = "";

	private RoadCrewPushNotifications() {
	}

	public static void ensureRegistered(@NonNull OsmandApplication app) {
		if (!RoadCrewReportsLayer.isEnabled(app)) {
			return;
		}
		if (FirebaseApp.getApps(app).isEmpty()) {
			Log.d(TAG, "Firebase is not configured; push registration skipped.");
			return;
		}
		try {
			FirebaseMessaging.getInstance().getToken()
					.addOnSuccessListener(token -> registerToken(app, token))
					.addOnFailureListener(error -> Log.w(TAG, "Could not get FCM token", error));
		} catch (IllegalStateException error) {
			Log.w(TAG, "Firebase is not initialized; push registration skipped.", error);
		}
	}

	public static void registerToken(@NonNull OsmandApplication app, @NonNull String token) {
		if (!RoadCrewReportsLayer.isEnabled(app) || token.trim().isEmpty() || token.equals(lastRequestedToken)) {
			return;
		}
		lastRequestedToken = token;
		RoadCrewReportsSync.registerPushToken(app, token, new RoadCrewReportsSync.HelpResolveCallback() {
			@Override
			public void onSuccess() {
				Log.d(TAG, "RoadCrew FCM token registered.");
			}

			@Override
			public void onError(@NonNull Exception error) {
				if (error instanceof IOException || error instanceof JSONException) {
					Log.w(TAG, "RoadCrew FCM token registration failed", error);
				}
			}
		});
	}
}
