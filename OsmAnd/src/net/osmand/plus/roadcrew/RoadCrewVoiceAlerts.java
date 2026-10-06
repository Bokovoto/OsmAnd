package net.osmand.plus.roadcrew;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.StateChangedListener;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.plus.voice.CommandPlayer;
import net.osmand.util.MapUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class RoadCrewVoiceAlerts implements TextToSpeech.OnInitListener {

	private static final long CHECK_INTERVAL_MILLIS = 5 * 1000;
	private static final long GLOBAL_COOLDOWN_MILLIS = 15 * 1000;
	private static final double ANNOUNCE_RADIUS_METERS = 1800;
	private static final double CLOSE_RADIUS_METERS = 700;
	private static final double ROUTE_CORRIDOR_METERS = 200;
	private static final double ROUTE_APPROACH_ALERT_METERS = 2000;
	private static final double ROUTE_FINAL_ALERT_METERS = 900;
	private static final double AHEAD_BEARING_DEGREES = 70;
	private static final float MIN_HEADING_SPEED_MPS = 2.0f;
	private static final long OLD_REPORT_MIN_AGE_MILLIS = 20 * 60 * 1000;
	private static final long EXPIRING_SOON_MILLIS = 10 * 60 * 1000;
	private static final double OLD_REPORT_LIFETIME_RATIO = 0.7;
	/** How often a waiting warning looks whether the navigation has gone quiet. */
	private static final long DRAIN_MILLIS = 200;
	/** No warning lasts longer: a lost "done" from the voice engine must not silence RoadCrew. */
	private static final long LONGEST_SENTENCE_MILLIS = 10_000;

	private final OsmandApplication app;
	private final TextToSpeech textToSpeech;
	private final StateChangedListener<Boolean> muteListener;
	private final Set<String> announcedAlertKeys = new HashSet<>();
	private final RoadCrewWeighStations.Voice weighStationVoice = new RoadCrewWeighStations.Voice();
	private final RoadCrewCameras.Voice cameraVoice = new RoadCrewCameras.Voice();
	private final RoadCrewCameras.Voice cameraZoneVoice = new RoadCrewCameras.Voice();
	/** Galin, 06.10.2026: never over the navigation - the second waits and follows. */
	private final RoadCrewSpeechQueue queue = new RoadCrewSpeechQueue();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable drainLater = this::drain;
	private volatile boolean speaking;
	private volatile long speakingSince;

	private long lastCheckMillis;
	private long lastSpokenMillis;
	private boolean ready;
	private boolean bulgarianVoice;

	RoadCrewVoiceAlerts(@NonNull OsmandApplication app) {
		this.app = app;
		textToSpeech = new TextToSpeech(app, this);
		muteListener = muted -> {
			if (Boolean.TRUE.equals(muted)) {
				queue.clear();
				textToSpeech.stop();
				speaking = false;
			}
		};
		app.getSettings().VOICE_MUTE.addListener(muteListener);
	}

	@Override
	public void onInit(int status) {
		if (status != TextToSpeech.SUCCESS) {
			return;
		}
		Locale locale = Locale.getDefault();
		bulgarianVoice = "bg".equals(locale.getLanguage());
		int result = textToSpeech.setLanguage(bulgarianVoice ? new Locale("bg", "BG") : Locale.ENGLISH);
		if ((result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
				&& bulgarianVoice) {
			bulgarianVoice = false;
			result = textToSpeech.setLanguage(Locale.ENGLISH);
		}
		ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
		textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
			@Override
			public void onStart(String utteranceId) {
				speaking = true;
			}

			@Override
			public void onDone(String utteranceId) {
				handler.post(() -> {
					speaking = false;
					drain();
				});
			}

			@Override
			public void onError(String utteranceId) {
				handler.post(() -> {
					speaking = false;
					drain();
				});
			}

			@Override
			public void onStop(String utteranceId, boolean interrupted) {
				handler.post(() -> {
					speaking = false;
					drain();
				});
			}
		});
	}

	/** RoadCrew's own sentence is being said - the navigation waits for it. */
	boolean isSpeaking() {
		if (speaking && System.currentTimeMillis() - speakingSince > LONGEST_SENTENCE_MILLIS) {
			speaking = false;
		}
		return speaking;
	}

	void check(@NonNull List<RoadCrewReport> reports, @Nullable Location location) {
		long now = System.currentTimeMillis();
		if (!ready || app.getSettings().VOICE_MUTE.get()
				|| now - lastCheckMillis < CHECK_INTERVAL_MILLIS
				|| now - lastSpokenMillis < GLOBAL_COOLDOWN_MILLIS) {
			return;
		}
		lastCheckMillis = now;

		if (location == null) {
			return;
		}

		String localDeviceId = RoadCrewReportsRepository.getLocalDeviceId(app);
		Candidate bestCandidate = null;
		for (RoadCrewReport report : reports) {
			if (!isEligible(report, localDeviceId, now)
					|| (location.hasBearing() && !report.appliesToBearing(location.getBearing()))) {
				continue;
			}
			LatLon reportLocation = report.getLocation();
			double distance = MapUtils.getDistance(location.getLatitude(), location.getLongitude(),
					reportLocation.getLatitude(), reportLocation.getLongitude());
			RouteMatch routeMatch = findRouteMatch(location, reportLocation);
			boolean headingAvailable = hasUsefulHeading(location);
			boolean ahead = headingAvailable && isAhead(location, reportLocation);
			if (routeMatch == null && distance > ANNOUNCE_RADIUS_METERS) {
				continue;
			}
			if (routeMatch == null && !ahead && distance > CLOSE_RADIUS_METERS) {
				continue;
			}
			double alertDistance = routeMatch != null ? routeMatch.routeDistanceMeters : distance;
			AlertStage stage = getAlertStage(alertDistance, routeMatch != null);
			if (announcedAlertKeys.contains(getAlertKey(report, stage))) {
				continue;
			}
			if (bestCandidate == null || alertDistance < bestCandidate.distanceMeters) {
				bestCandidate = new Candidate(report, alertDistance, ahead, routeMatch != null, stage,
						isUncertain(report, now));
			}
		}

		if (bestCandidate != null) {
			announcedAlertKeys.add(getAlertKey(bestCandidate.report, bestCandidate.stage));
			lastSpokenMillis = now;
			speak(buildMessage(bestCandidate));
		}
	}

	/**
	 * "Стационарен кантар след 500 метра" - once per pass, only for a station on
	 * the route (RoadCrewWeighStations). It waits for the same pause as the
	 * reports so the two never talk over each other.
	 */
	void checkWeighStation(@Nullable RoadCrewWeighStations.Ahead ahead) {
		long now = System.currentTimeMillis();
		if (!ready || app.getSettings().VOICE_MUTE.get()
				|| now - lastSpokenMillis < GLOBAL_COOLDOWN_MILLIS || !weighStationVoice.shouldSpeak(ahead, now)) {
			return;
		}
		weighStationVoice.spoken(ahead.station, now);
		lastSpokenMillis = now;
		speak(bulgarianVoice
				? "Стационарен кантар след " + formatBulgarianDistance(ahead.meters) + "."
				: "Fixed weigh station in " + formatDistance(ahead.meters) + ".");
	}

	/**
	 * Camera nearby - the POI does not identify the controlled carriageway.
	 * Twice per pass (RoadCrewCameras.Voice: with the text at 1 km, again at
	 * 500 m - Galin, 06.10.2026), after the same pause as the reports so the
	 * two never talk over each other.
	 * The driver's switch is OsmAnd's own for camera voice, on by default
	 * (Galin, 02.10.2026: he turns it off himself where the law asks it).
	 */
	void checkCamera(@Nullable RoadCrewCameras.Ahead ahead) {
		long now = System.currentTimeMillis();
		if (!ready || ahead == null || app.getSettings().VOICE_MUTE.get() || !app.getSettings().SPEAK_SPEED_CAMERA.get()
				|| now - lastSpokenMillis < GLOBAL_COOLDOWN_MILLIS
				|| ahead.camera == null) {
			return;
		}
		RoadCrewCameras.Stage stage = cameraVoice.toSpeak(ahead.camera.id, ahead.meters, now);
		if (stage == null) {
			return;
		}
		cameraVoice.spoken(ahead.camera.id, stage, now);
		lastSpokenMillis = now;
		// No accent mark: the voice drops a word that carries one. The apostrophe
		// makes it read "ка'мера" by its rules - stressed on the first syllable
		// (ROADMAP 374).
		String cameraId = ahead.camera.id;
		speak(bulgarianVoice
				? "Ка'мера наблизо."
				: "Speed camera nearby.", () -> stillAhead(cameraId));
	}

	/** France: "Опасна зона." once when the zone begins - no distance, it would place the camera. */
	void checkCameraZone(@Nullable RoadCrewCameras.Zone zone) {
		long now = System.currentTimeMillis();
		if (!ready || zone == null || app.getSettings().VOICE_MUTE.get() || !app.getSettings().SPEAK_SPEED_CAMERA.get()
				|| now - lastSpokenMillis < GLOBAL_COOLDOWN_MILLIS
				|| cameraZoneVoice.toSpeak(zone.camera.id, 0, now) == null) {
			return;
		}
		cameraZoneVoice.spoken(zone.camera.id, RoadCrewCameras.Stage.NEAR, now);
		lastSpokenMillis = now;
		speak(bulgarianVoice ? "Опасна зона." : "Danger zone.",
				() -> RoadCrewRoadAlerts.snapshot().camera.zone != null);
	}

	void shutdown() {
		handler.removeCallbacks(drainLater);
		queue.clear();
		app.getSettings().VOICE_MUTE.removeListener(muteListener);
		textToSpeech.shutdown();
		ready = false;
	}

	private boolean isEligible(@NonNull RoadCrewReport report, @NonNull String localDeviceId, long now) {
		return !report.getId().startsWith("seed-")
				&& !report.getCreatedBy().equals(localDeviceId)
				&& !report.hasLocalVote()
				&& !report.shouldHideLocally()
				&& !report.isHelpProbablyResolved()
				&& !report.isExpired(now);
	}

	@NonNull
	private AlertStage getAlertStage(double distanceMeters, boolean routeAlert) {
		if (!routeAlert) {
			return distanceMeters <= CLOSE_RADIUS_METERS ? AlertStage.NEAR_FINAL : AlertStage.NEAR_EARLY;
		}
		if (distanceMeters <= ROUTE_FINAL_ALERT_METERS) {
			return AlertStage.ROUTE_FINAL;
		}
		if (distanceMeters <= ROUTE_APPROACH_ALERT_METERS) {
			return AlertStage.ROUTE_APPROACH;
		}
		return AlertStage.ROUTE_EARLY;
	}

	@NonNull
	private String getAlertKey(@NonNull RoadCrewReport report, @NonNull AlertStage stage) {
		return report.getId() + ":" + stage.name();
	}

	private boolean hasUsefulHeading(@NonNull Location location) {
		return location.hasBearing()
				&& location.hasSpeed()
				&& location.getSpeed() >= MIN_HEADING_SPEED_MPS;
	}

	private boolean isAhead(@NonNull Location location, @NonNull LatLon target) {
		double bearingToTarget = bearingDegrees(location.getLatitude(), location.getLongitude(),
				target.getLatitude(), target.getLongitude());
		double diff = Math.abs(normalizeDegrees(bearingToTarget - location.getBearing()));
		return diff <= AHEAD_BEARING_DEGREES;
	}

	private RouteMatch findRouteMatch(@NonNull Location currentLocation, @NonNull LatLon target) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		if (!routingHelper.isRouteCalculated() || !routingHelper.isFollowingMode()) {
			return null;
		}
		List<Location> routeLocations = routingHelper.getRoute().getRouteLocations();
		if (routeLocations.isEmpty()) {
			return null;
		}

		Location previous = currentLocation;
		double distanceToSegmentStart = 0;
		RouteMatch bestMatch = null;
		for (Location next : routeLocations) {
			double segmentDistance = MapUtils.getDistance(previous.getLatitude(), previous.getLongitude(),
					next.getLatitude(), next.getLongitude());
			if (segmentDistance <= 0) {
				previous = next;
				continue;
			}
			double projectionCoeff = MapUtils.getProjectionCoeff(target.getLatitude(), target.getLongitude(),
					previous.getLatitude(), previous.getLongitude(), next.getLatitude(), next.getLongitude());
			LatLon projected = MapUtils.getProjection(target.getLatitude(), target.getLongitude(),
					previous.getLatitude(), previous.getLongitude(), next.getLatitude(), next.getLongitude());
			double offRouteDistance = MapUtils.getDistance(target.getLatitude(), target.getLongitude(),
					projected.getLatitude(), projected.getLongitude());
			double routeDistance = distanceToSegmentStart + segmentDistance * projectionCoeff;
			if (offRouteDistance <= ROUTE_CORRIDOR_METERS
					&& routeDistance >= 0
					&& (bestMatch == null || routeDistance < bestMatch.routeDistanceMeters)) {
				bestMatch = new RouteMatch(routeDistance);
			}
			distanceToSegmentStart += segmentDistance;
			previous = next;
		}
		return bestMatch;
	}

	private boolean isUncertain(@NonNull RoadCrewReport report, long now) {
		if (report.getDeniedCount() > 0 && report.getDeniedCount() >= report.getConfirmedCount()) {
			return true;
		}
		long lifetime = report.getExpiresAtMillis() - report.getCreatedAtMillis();
		if (lifetime <= 0) {
			return true;
		}
		long age = now - report.getCreatedAtMillis();
		long remaining = report.getExpiresAtMillis() - now;
		return age >= OLD_REPORT_MIN_AGE_MILLIS
				&& (age >= lifetime * OLD_REPORT_LIFETIME_RATIO || remaining <= EXPIRING_SOON_MILLIS);
	}

	private double bearingDegrees(double startLat, double startLon, double endLat, double endLon) {
		double startLatRad = Math.toRadians(startLat);
		double endLatRad = Math.toRadians(endLat);
		double lonDiffRad = Math.toRadians(endLon - startLon);
		double y = Math.sin(lonDiffRad) * Math.cos(endLatRad);
		double x = Math.cos(startLatRad) * Math.sin(endLatRad)
				- Math.sin(startLatRad) * Math.cos(endLatRad) * Math.cos(lonDiffRad);
		return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
	}

	private double normalizeDegrees(double degrees) {
		double normalized = (degrees + 540.0) % 360.0 - 180.0;
		return normalized == -180.0 ? 180.0 : normalized;
	}

	@NonNull
	private String buildMessage(@NonNull Candidate candidate) {
		if (bulgarianVoice) {
			return buildBulgarianMessage(candidate);
		}
		if (candidate.stage == AlertStage.ROUTE_FINAL || candidate.stage == AlertStage.NEAR_FINAL) {
			return getEnglishFinalMessage(candidate);
		}
		if (candidate.routeAlert) {
			return getEnglishPrefix(candidate) + " on your route in "
					+ formatDistance(candidate.distanceMeters) + ".";
		}
		String place = candidate.ahead ? "ahead" : "nearby";
		return getEnglishPrefix(candidate) + " " + place + " in "
				+ formatDistance(candidate.distanceMeters) + ".";
	}

	@NonNull
	private String buildBulgarianMessage(@NonNull Candidate candidate) {
		String distance = formatBulgarianDistance(candidate.distanceMeters);
		String type = getBulgarianTypePhrase(candidate.report.getType());
		if (candidate.stage == AlertStage.ROUTE_FINAL || candidate.stage == AlertStage.NEAR_FINAL) {
			if (candidate.uncertain) {
				return "Бъдете внимателни. След " + distance
						+ " наближавате стар маркиран пост: " + type + ".";
			}
			return "След " + distance + ": " + type + ".";
		}
		if (candidate.uncertain) {
			if (candidate.routeAlert) {
				return "Бъдете внимателни. По маршрута след " + distance + " има стар маркиран пост: " + type + ".";
			}
			return "Бъдете внимателни. Наближавате стар маркиран пост: " + type + ", след " + distance + ".";
		}
		if (candidate.routeAlert) {
			return type + " по маршрута след " + distance + ".";
		}
		String place = candidate.ahead ? "напред" : "наблизо";
		return type + " " + place + " след " + distance + ".";
	}

	@NonNull
	private String getEnglishFinalMessage(@NonNull Candidate candidate) {
		if (candidate.uncertain) {
			return "Be careful. In " + formatDistance(candidate.distanceMeters)
					+ " you are approaching an older marked " + getEnglishTypePhrase(candidate.report.getType()) + ".";
		}
		return "In " + formatDistance(candidate.distanceMeters) + ": "
				+ getEnglishTypePhrase(candidate.report.getType()) + ".";
	}

	@NonNull
	private String getEnglishPrefix(@NonNull Candidate candidate) {
		String type = getEnglishTypePhrase(candidate.report.getType());
		if (candidate.uncertain) {
			return "Be careful. Older marked " + type;
		}
		return capitalize(type);
	}

	@NonNull
	private String capitalize(@NonNull String text) {
		if (text.isEmpty()) {
			return text;
		}
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	@NonNull
	private String getEnglishTypePhrase(@NonNull RoadCrewReportType type) {
		switch (type) {
			case DAI:
				return "traffic control";
			case POLICE:
				return "police";
			case CAMERA:
				return "camera";
			case WEIGH_STATION:
				return "weigh station";
			case DANGER:
				return "danger";
			case HELP:
				return "driver needs help";
			default:
				return "RoadCrew report";
		}
	}

	@NonNull
	private String getBulgarianTypePhrase(@NonNull RoadCrewReportType type) {
		switch (type) {
			case DAI:
				return "трафик контрол";
			case POLICE:
				return "полиция";
			case CAMERA:
				return "ка'мера";
			case WEIGH_STATION:
				return "кантар";
			case DANGER:
				return "опасност";
			case HELP:
				return "шофьор има нужда от помощ";
			default:
				return "сигнал RoadCrew";
		}
	}

	@NonNull
	private String formatDistance(double distanceMeters) {
		if (distanceMeters < 1000) {
			long roundedMeters = Math.max(50, Math.round(distanceMeters / 50.0) * 50);
			return roundedMeters + " meters";
		}
		return String.format(Locale.US, "%.1f kilometers", distanceMeters / 1000.0);
	}

	@NonNull
	private String formatBulgarianDistance(double distanceMeters) {
		if (distanceMeters < 1000) {
			long roundedMeters = Math.max(50, Math.round(distanceMeters / 50.0) * 50);
			return roundedMeters + " метра";
		}
		return String.format(Locale.US, "%.1f километра", distanceMeters / 1000.0);
	}

	private void speak(@NonNull String message) {
		speak(message, () -> true);
	}

	/**
	 * Through the queue: said now when the navigation is silent, else right
	 * after it - and only while stillTrue holds (Galin, 06.10.2026).
	 */
	private void speak(@NonNull String message, @NonNull BooleanSupplier stillTrue) {
		queue.add(message, System.currentTimeMillis(), stillTrue);
		drain();
	}

	/** Says the next waiting warning once both voices are silent; looks again shortly if not. */
	private void drain() {
		handler.removeCallbacks(drainLater);
		if (!ready || isSpeaking()) {
			if (!queue.isEmpty()) {
				handler.postDelayed(drainLater, DRAIN_MILLIS);
			}
			return;
		}
		String text = queue.next(navigationSpeaking(), System.currentTimeMillis());
		if (text != null) {
			speaking = true;
			speakingSince = System.currentTimeMillis();
			textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "roadcrew-" + System.currentTimeMillis());
		} else if (!queue.isEmpty()) {
			handler.postDelayed(drainLater, DRAIN_MILLIS);
		}
	}

	/** The navigation's own voice - spoken prompts or recorded ones - is talking. */
	private boolean navigationSpeaking() {
		VoiceRouter router = app.getRoutingHelper().getVoiceRouter();
		CommandPlayer player = router == null ? null : router.getPlayer();
		return player != null && player.isSpeaking();
	}

	/** The camera this warning is about is still the one ahead - not passed while waiting. */
	private static boolean stillAhead(@NonNull String cameraId) {
		RoadCrewCameras.Ahead ahead = RoadCrewRoadAlerts.snapshot().camera.ahead;
		return ahead != null && ahead.camera != null && cameraId.equals(ahead.camera.id);
	}

	private static final class Candidate {
		private final RoadCrewReport report;
		private final double distanceMeters;
		private final boolean ahead;
		private final boolean routeAlert;
		private final AlertStage stage;
		private final boolean uncertain;

		private Candidate(@NonNull RoadCrewReport report, double distanceMeters, boolean ahead, boolean routeAlert,
				@NonNull AlertStage stage, boolean uncertain) {
			this.report = report;
			this.distanceMeters = distanceMeters;
			this.ahead = ahead;
			this.routeAlert = routeAlert;
			this.stage = stage;
			this.uncertain = uncertain;
		}
	}

	private enum AlertStage {
		ROUTE_EARLY,
		ROUTE_APPROACH,
		ROUTE_FINAL,
		NEAR_EARLY,
		NEAR_FINAL
	}

	private static final class RouteMatch {
		private final double routeDistanceMeters;

		private RouteMatch(double routeDistanceMeters) {
			this.routeDistanceMeters = routeDistanceMeters;
		}
	}
}
