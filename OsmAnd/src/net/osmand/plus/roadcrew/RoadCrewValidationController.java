package net.osmand.plus.roadcrew;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import net.osmand.Location;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RoadCrewValidationStopGate;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Separate from observation ingestion: answers never enable a routing override. */
final class RoadCrewValidationController {

	private static final String PREFS = "roadcrew_segment_validation";
	private static final long RETRY_MILLIS = 15 * 60_000L;
	private static final long PROMPT_INTERVAL = 24 * 60 * 60_000L;
	private final OsmandApplication app;
	private final Supplier<MapActivity> activitySupplier;
	private final BooleanSupplier otherPrompt;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final RoadCrewValidationStopGate stopGate = new RoadCrewValidationStopGate();
	private final SharedPreferences prefs;
	private final Runnable tick = this::tick;
	private volatile boolean closed;
	private boolean busy;
	private boolean safe;
	private long manualUntil;
	private AlertDialog dialog;

	RoadCrewValidationController(OsmandApplication app, Supplier<MapActivity> activitySupplier,
			BooleanSupplier otherPrompt) {
		this.app = app;
		this.activitySupplier = activitySupplier;
		this.otherPrompt = otherPrompt;
		prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		handler.post(tick);
	}

	void close() {
		closed = true;
		handler.removeCallbacksAndMessages(null);
		if (dialog != null) { dialog.dismiss(); }
		executor.shutdownNow();
	}

	boolean isShowing() {
		return dialog != null && dialog.isShowing();
	}

	static void clearLocalAnswers(Context context) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
	}

	void requestManually() {
		if (!RoadCrewMapObservationConsent.isEnabled(app)) {
			app.showToastMessage(R.string.roadcrew_validation_consent_required);
			return;
		}
		if (app.getRoutingHelper().isFollowingMode()) {
			app.showToastMessage(R.string.roadcrew_validation_stop_first);
			return;
		}
		manualUntil = System.currentTimeMillis() + 2 * 60_000;
		app.showToastMessage(safe ? R.string.roadcrew_validation_loading : R.string.roadcrew_validation_stop_first);
	}

	private void tick() {
		if (closed) { return; }
		handler.postDelayed(tick, 1000);
		MapActivity activity = activitySupplier.get();
		long now = System.currentTimeMillis();
		boolean consent = RoadCrewMapObservationConsent.isEnabled(app);
		safe = updateSafety();
		if (!safe && isShowing()) { dialog.dismiss(); }
		if (!consent) {
			if (prefs.contains("answer")) { clearLocalAnswers(app); }
			return;
		}
		if (busy || isShowing()) { return; }
		boolean manual = manualUntil > now;
		boolean pending = prefs.contains("answer");
		boolean canPrompt = safe && activity.hasWindowFocus() && !otherPrompt.getAsBoolean();
		long nextAttempt = prefs.getLong("next_attempt", 0);
		if (pending) {
			if (now < nextAttempt) { return; }
		} else if (!canPrompt || (!manual && now < prefs.getLong("next_prompt", 0))
				|| now < (manual ? prefs.getLong("last_attempt", 0) + 30_000 : nextAttempt)) {
			return;
		}
		String token = RoadCrewValidationApi.existingToken(app);
		if (token.isEmpty()) {
			if (manual) { app.showToastMessage(R.string.roadcrew_validation_none); manualUntil = 0; }
			return;
		}
		busy = true;
		manualUntil = 0;
		prefs.edit().putLong("last_attempt", now).putLong("next_attempt", now + RETRY_MILLIS).apply();
		executor.execute(() -> work(token, pending, manual));
	}

	private boolean updateSafety() {
		MapActivity activity = activitySupplier.get();
		Location location = app.getLocationProvider().getLastKnownLocation();
		boolean eligible = !closed && RoadCrewMapObservationConsent.isEnabled(app)
				&& activity != null && !activity.isFinishing() && !activity.isDestroyed()
				&& app.getSettings().MAP_ACTIVITY_ENABLED
				&& app.getSettings().getApplicationMode().isDerivedRoutingFrom(ApplicationMode.TRUCK)
				&& !app.getRoutingHelper().isFollowingMode()
				&& !app.getRoutingHelper().isRouteBeingCalculated()
				&& !app.getLocationProvider().getLocationSimulation().isRouteAnimating();
		return stopGate.update(SystemClock.elapsedRealtime(), eligible,
				location == null ? Long.MAX_VALUE : System.currentTimeMillis() - location.getTime(),
				location != null && location.hasSpeed(), location == null ? 0 : location.getSpeed(),
				location != null && location.hasAccuracy(), location == null ? 0 : location.getAccuracy());
	}

	private void work(String token, boolean pending, boolean manual) {
		try {
			if (closed || !RoadCrewMapObservationConsent.isEnabled(app)) { return; }
			String owner = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
			if (pending) {
				if (!owner.equals(prefs.getString("owner", ""))) {
					throw new RoadCrewValidationApi.RejectedAnswerException();
				}
				RoadCrewValidationApi.request(token, new JSONObject(prefs.getString("answer", "{}")));
				prefs.edit().remove("answer").remove("owner").apply();
				notifyUser(R.string.roadcrew_validation_sent);
				return;
			}
			JSONObject questionJson = RoadCrewValidationApi.request(token, null).optJSONObject("question");
			if (questionJson == null) {
				prefs.edit().putLong("next_prompt", System.currentTimeMillis() + 6 * 60 * 60_000L).apply();
				if (manual) { notifyUser(R.string.roadcrew_validation_none); }
				return;
			}
			RoadCrewValidationApi.Question question = new RoadCrewValidationApi.Question(questionJson);
			RoadCrewValidationMapView.MapData map = RoadCrewValidationMapView.load(app, question,
					() -> closed || !RoadCrewMapObservationConsent.isEnabled(app));
			if (map == null) {
				prefs.edit().putLong("next_prompt", System.currentTimeMillis() + 6 * 60 * 60_000L).apply();
				if (manual) { notifyUser(R.string.roadcrew_validation_map_unavailable); }
				return;
			}
			handler.post(() -> {
				MapActivity activity = activitySupplier.get();
				if (closed || !updateSafety() || activity == null || !activity.hasWindowFocus()
						|| otherPrompt.getAsBoolean() || !RoadCrewMapObservationConsent.isEnabled(app)) { return; }
				show(activity, question, map, owner);
			});
		} catch (RoadCrewValidationApi.RejectedAnswerException e) {
			if (pending) {
				prefs.edit().remove("answer").remove("owner").apply();
				notifyUser(R.string.roadcrew_validation_expired);
			}
		} catch (Exception e) {
			Log.w("RoadCrewValidation", "Validation request deferred", e);
			if (manual) { notifyUser(R.string.roadcrew_validation_offline); }
		} finally {
			if (!closed) { handler.post(() -> busy = false); }
		}
	}

	private void show(MapActivity activity, RoadCrewValidationApi.Question question,
			RoadCrewValidationMapView.MapData map, String owner) {
		prefs.edit().putLong("next_prompt", System.currentTimeMillis() + PROMPT_INTERVAL).apply();
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_validation_title));
		((TextView) content.getChildAt(0)).setTextSize(22);
		String road = map.roadName.isEmpty() ? activity.getString(R.string.roadcrew_validation_unnamed) : map.roadName;
		RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_validation_context, road,
				Math.round(question.key.getLengthMeters()),
				DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(question.passedAt))));
		LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		mapParams.topMargin = RoadCrewUi.dp(activity, 12);
		content.addView(new RoadCrewValidationMapView(activity, map), mapParams);
		RoadCrewUi.addSectionTitle(activity, content, activity.getString(R.string.roadcrew_validation_question));
		RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_validation_remember));
		dialog = RoadCrewUi.createDialog(activity, content);
		addAnswer(activity, content, question, owner, R.string.roadcrew_validation_suitable, "SUITABLE", true);
		addAnswer(activity, content, question, owner, R.string.roadcrew_validation_problem, "PROBLEM", false);
		addAnswer(activity, content, question, owner, R.string.roadcrew_validation_unsure, "UNSURE", false);
		fitButton(RoadCrewUi.addFullWidthButton(activity, content, activity.getString(R.string.roadcrew_validation_later),
				false, v -> dialog.dismiss()));
		dialog.setOnDismissListener(d -> dialog = null);
		dialog.show();
	}

	private void addAnswer(MapActivity activity, LinearLayout content, RoadCrewValidationApi.Question question,
			String owner, int title, String decision, boolean primary) {
		Button button = RoadCrewUi.addFullWidthButton(activity, content, activity.getString(title), primary, v -> {
			if (!updateSafety()) { dialog.dismiss(); return; }
			try {
				// Commit before dismissing: process death or a timeout must not lose an answer.
				boolean saved = prefs.edit().putString("answer", question.answer(decision).toString())
						.putString("owner", owner).putLong("next_attempt", 0).commit();
				if (saved) { dialog.dismiss(); app.showToastMessage(R.string.roadcrew_validation_queued); }
			} catch (Exception e) {
				Log.w("RoadCrewValidation", "Could not save answer", e);
			}
		});
		fitButton(button);
	}

	private void fitButton(Button button) {
		button.setSingleLine(false);
		button.setMinHeight(RoadCrewUi.dp(button.getContext(), 48));
		button.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
	}

	private void notifyUser(int resource) {
		handler.post(() -> {
			if (!closed && app.getSettings().MAP_ACTIVITY_ENABLED) { app.showToastMessage(resource); }
		});
	}
}
