package net.osmand.plus.roadcrew;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.Location;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.util.MapUtils;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class RoadCrewPlacesController {

	private static final int MIN_ZOOM = 8;
	private static final long REFRESH_INTERVAL_MILLIS = 60_000;
	private static final long OPEN_CHANNEL_REFRESH_MILLIS = 15_000;
	private static final float TOUCH_RADIUS_DP = 38;
	private static final double MOVING_SPEED_LIMIT_METERS_PER_SECOND = 2.5;
	private static final ExecutorService POI_EXECUTOR = Executors.newSingleThreadExecutor();

	private final OsmandApplication app;
	private final Supplier<MapActivity> mapActivitySupplier;
	private final Supplier<OsmandMapTileView> mapViewSupplier;
	private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint markerBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint markerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path markerPath = new Path();
	private volatile List<RoadCrewPlace> places = Collections.emptyList();
	private boolean refreshRunning;
	private long lastRefreshMillis;
	@Nullable private LatLon lastRefreshCenter;

	RoadCrewPlacesController(@NonNull OsmandApplication app,
			@NonNull Supplier<MapActivity> mapActivitySupplier,
			@NonNull Supplier<OsmandMapTileView> mapViewSupplier) {
		this.app = app;
		this.mapActivitySupplier = mapActivitySupplier;
		this.mapViewSupplier = mapViewSupplier;
		createResources();
	}

	private void createResources() {
		markerPaint.setColor(0xff19a974);
		markerPaint.setStyle(Paint.Style.FILL);
		markerBorderPaint.setColor(Color.WHITE);
		markerBorderPaint.setStyle(Paint.Style.STROKE);
		markerBorderPaint.setStrokeWidth(dp(2));
		markerTextPaint.setColor(Color.WHITE);
		markerTextPaint.setTextAlign(Paint.Align.CENTER);
		markerTextPaint.setFakeBoldText(true);
		markerTextPaint.setTextSize(sp(12));
		labelPaint.setColor(0xee171a1f);
		labelPaint.setStyle(Paint.Style.FILL);
		labelTextPaint.setColor(Color.WHITE);
		labelTextPaint.setTextSize(sp(11));
		labelTextPaint.setFakeBoldText(true);
	}

	void draw(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox) {
		refreshIfNeeded(tileBox.getCenterLatLon());
		if (tileBox.getZoom() < MIN_ZOOM) return;
		for (RoadCrewPlace place : places) {
			LatLon location = place.getLocation();
			if (!tileBox.containsLatLon(location)) continue;
			float x = tileBox.getPixXFromLatLon(location.getLatitude(), location.getLongitude());
			float y = tileBox.getPixYFromLatLon(location.getLatitude(), location.getLongitude());
			drawMarker(canvas, place, x, y);
		}
	}

	@Nullable
	RoadCrewPlace findTapped(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		if (tileBox.getZoom() < MIN_ZOOM) return null;
		float radius = dp(TOUCH_RADIUS_DP);
		RoadCrewPlace best = null;
		double bestDistance = Double.MAX_VALUE;
		for (RoadCrewPlace place : places) {
			LatLon location = place.getLocation();
			float x = tileBox.getPixXFromLatLon(location.getLatitude(), location.getLongitude());
			float y = tileBox.getPixYFromLatLon(location.getLatitude(), location.getLongitude()) - dp(8);
			double distance = Math.hypot(point.x - x, point.y - y);
			if (distance <= radius && distance < bestDistance) {
				best = place;
				bestDistance = distance;
			}
		}
		return best;
	}

	void showHome() {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null) return;
		LatLon center = currentLocationOrMapCenter();
		refresh(center, () -> showHomeNow(activity));
	}

	void showPlace(@NonNull RoadCrewPlace place) {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null) return;
		LinearLayout content = RoadCrewUi.createPanel(activity, place.getName());
		TextView loading = RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_place_loading));
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		dialog.show();
		String[] signature = {""};
		loadAndRenderPlace(activity, dialog, content, place, loading, signature, false);
	}

	private void showHomeNow(@NonNull MapActivity activity) {
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_places_title));
		RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_places_body));
		RoadCrewUi.addFullWidthButton(activity, content, activity.getString(R.string.roadcrew_places_map_parking), true,
				v -> showParkingPicker());
		RoadCrewUi.addFullWidthButton(activity, content, activity.getString(R.string.roadcrew_places_create_here), false,
				v -> showKindPicker());
		RoadCrewUi.addSectionTitle(activity, content, activity.getString(R.string.roadcrew_places_nearby));
		if (places.isEmpty()) {
			RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_places_empty));
		} else {
			int count = Math.min(20, places.size());
			for (int i = 0; i < count; i++) {
				RoadCrewPlace place = places.get(i);
				String label = place.getName() + "  ·  " + formatDistance(place.getDistanceKm());
				if (place.getActiveMessageCount() > 0) label += "  ·  " + place.getActiveMessageCount();
				RoadCrewUi.addFullWidthButton(activity, content, label, false, v -> showPlace(place));
			}
		}
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
		dialog.show();
	}

	private void showParkingPicker() {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null) return;
		LatLon center = currentLocationOrMapCenter();
		app.showToastMessage(R.string.roadcrew_places_parking_loading);
		POI_EXECUTOR.execute(() -> {
			List<Amenity> result = new ArrayList<>();
			try {
				PoiUIFilter filter = app.getPoiFilters().getFilterById(PoiUIFilter.STD_PREFIX + "parking");
				if (filter != null) {
					result.addAll(filter.initializeNewSearch(center.getLatitude(), center.getLongitude(), 40, null, -1));
					result.sort(Comparator.comparingDouble(a -> MapUtils.getDistance(center, a.getLocation())));
				}
			} catch (RuntimeException ignored) {
				// A missing or updating offline map is shown as an empty result below.
			}
			List<Amenity> parking = result;
			app.runInUIThread(() -> showParkingResults(activity, center, parking));
		});
	}

	private void showParkingResults(@NonNull MapActivity activity, @NonNull LatLon center,
			@NonNull List<Amenity> parking) {
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_places_map_parking));
		if (parking.isEmpty()) {
			RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_places_parking_empty));
		} else {
			int count = Math.min(30, parking.size());
			for (int i = 0; i < count; i++) {
				Amenity amenity = parking.get(i);
				String name = amenityName(activity, amenity);
				double km = MapUtils.getDistance(center, amenity.getLocation()) / 1000.0;
				RoadCrewUi.addFullWidthButton(activity, content, name + "  ·  " + formatDistance(km), false,
						v -> createPlaceFromAmenity(activity, amenity, name));
			}
		}
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
		dialog.show();
	}

	private void createPlaceFromAmenity(@NonNull MapActivity activity, @NonNull Amenity amenity,
			@NonNull String name) {
		String sourceId = amenity.getId() == null ? "" : String.valueOf(amenity.getId());
		RoadCrewPlacesApi.createPlace(app, "PARKING", name, amenity.getLocation(),
				sourceId.isEmpty() ? "ROADCREW" : "OSM", sourceId, new RoadCrewPlacesApi.Callback<>() {
					@Override public void onSuccess(@NonNull String id) { onPlaceCreated(id, amenity.getLocation()); }
					@Override public void onError(@NonNull Exception error) { showRequestError(activity, error); }
				});
	}

	private void showKindPicker() {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null) return;
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_place_kind_title));
		String[] kinds = {"PARKING", "PORT", "FACTORY", "BORDER", "SERVICE", "ROAD_INFO", "OTHER"};
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		for (String kind : kinds) {
			RoadCrewUi.addFullWidthButton(activity, content, kindTitle(activity, kind), false, v -> {
				dialog.dismiss();
				showCreatePlaceDialog(kind);
			});
		}
		dialog.show();
	}

	private void showCreatePlaceDialog(@NonNull String kind) {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null) return;
		EditText name = RoadCrewUi.createInput(activity, activity.getString(R.string.roadcrew_place_name_hint), false);
		name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
		LinearLayout content = RoadCrewUi.createPanel(activity, kindTitle(activity, kind));
		RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_place_position_note));
		RoadCrewUi.addInput(activity, content, name);
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_save), true, v -> {
			String value = name.getText().toString().trim();
			if (value.isEmpty()) return;
			dialog.dismiss();
			LatLon location = currentLocationOrMapCenter();
			RoadCrewPlacesApi.createPlace(app, kind, value, location, "ROADCREW", "",
					new RoadCrewPlacesApi.Callback<>() {
						@Override public void onSuccess(@NonNull String id) { onPlaceCreated(id, location); }
						@Override public void onError(@NonNull Exception error) { showRequestError(activity, error); }
					});
		});
		dialog.show();
	}

	private void onPlaceCreated(@NonNull String id, @NonNull LatLon location) {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null) return;
		app.showToastMessage(R.string.roadcrew_place_created);
		lastRefreshMillis = 0;
		refresh(location, () -> {
			RoadCrewPlace created = findById(id);
			if (created != null) showPlace(created);
		});
	}

	private void loadAndRenderPlace(@NonNull MapActivity activity, @NonNull AlertDialog dialog,
			@NonNull LinearLayout content, @NonNull RoadCrewPlace fallback, @Nullable TextView loading,
			@NonNull String[] signature, boolean periodic) {
		RoadCrewPlacesApi.getPlace(app, fallback.getId(), new RoadCrewPlacesApi.Callback<>() {
			@Override public void onSuccess(@NonNull RoadCrewPlace.Details details) {
				if (!dialog.isShowing()) return;
				String current = detailsSignature(details);
				if (!current.equals(signature[0])) {
					signature[0] = current;
					renderPlaceDetails(activity, dialog, content, details);
				}
				if (!periodic) schedulePlaceRefresh(activity, dialog, content, details.place, signature);
			}
			@Override public void onError(@NonNull Exception error) {
				if (loading != null) loading.setText(activity.getString(R.string.roadcrew_place_load_failed));
			}
		});
	}

	private void schedulePlaceRefresh(@NonNull MapActivity activity, @NonNull AlertDialog dialog,
			@NonNull LinearLayout content, @NonNull RoadCrewPlace place, @NonNull String[] signature) {
		content.postDelayed(() -> {
			if (dialog.isShowing()) {
				loadAndRenderPlace(activity, dialog, content, place, null, signature, true);
				schedulePlaceRefresh(activity, dialog, content, place, signature);
			}
		}, OPEN_CHANNEL_REFRESH_MILLIS);
	}

	private void renderPlaceDetails(@NonNull MapActivity activity, @NonNull AlertDialog dialog,
			@NonNull LinearLayout content, @NonNull RoadCrewPlace.Details details) {
		if (content.getChildCount() > 1) content.removeViews(1, content.getChildCount() - 1);
		String placeSummary = kindTitle(activity, details.place.getKind());
		if (details.place.getDistanceKm() > 0) placeSummary += "  ·  " + formatDistance(details.place.getDistanceKm());
		RoadCrewUi.addBody(activity, content, placeSummary);
		if (details.ratingCount > 0) {
			RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_place_rating_summary,
					details.averageRating, details.ratingCount, details.securityRating, details.quietRating,
					details.accessRating, details.facilitiesRating));
			if (details.theftReports > 0) RoadCrewUi.addBody(activity, content,
					activity.getString(R.string.roadcrew_place_theft_reports, details.theftReports));
		}
		RoadCrewUi.addSectionTitle(activity, content, activity.getString(R.string.roadcrew_place_current_info));
		if (details.messages.isEmpty()) {
			RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_place_no_messages));
		} else {
			for (RoadCrewPlace.Message message : details.messages) addMessage(activity, content, details.place, message);
		}
		RoadCrewUi.addFullWidthButton(activity, content, activity.getString(R.string.roadcrew_place_add_information), true,
				v -> showMessageCategory(details.place));
		if ("PARKING".equals(details.place.getKind())) {
			RoadCrewUi.addFullWidthButton(activity, content, activity.getString(R.string.roadcrew_place_rate_parking), false,
					v -> showReviewDialog(details.place));
		}
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
	}

	private void addMessage(@NonNull MapActivity activity, @NonNull LinearLayout content,
			@NonNull RoadCrewPlace place, @NonNull RoadCrewPlace.Message message) {
		String author = message.displayName.isEmpty() ? activity.getString(R.string.roadcrew_place_driver) : message.displayName;
		String verified = message.verifiedVisit ? "  ·  " + activity.getString(R.string.roadcrew_place_verified_visit) : "";
		RoadCrewUi.addSectionTitle(activity, content, messageCategoryTitle(activity, message.category));
		RoadCrewUi.addBody(activity, content, message.body + "\n" + author + verified + "  ·  "
				+ formatRelative(activity, message.createdAt) + formatExpiry(activity, message.expiresAt));
		if (message.expiresAt > 0) {
			LinearLayout votes = RoadCrewUi.addButtonRow(activity, content);
			RoadCrewUi.addButton(activity, votes,
					activity.getString(R.string.roadcrew_place_still_valid_count, message.stillValidCount), false,
					v -> voteMessage(activity, place, message.id, "STILL_VALID"));
			RoadCrewUi.addButton(activity, votes,
					activity.getString(R.string.roadcrew_place_outdated_count, message.outdatedCount), false,
					v -> voteMessage(activity, place, message.id, "OUTDATED"));
		}
	}

	private void showMessageCategory(@NonNull RoadCrewPlace place) {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null || !ensureStationary(activity)) return;
		String[] categories = {"GENERAL", "ENTRANCE", "DOCUMENTS", "QUEUE", "PARKING_AVAILABILITY", "ACCESS_PROBLEM", "HAZARD"};
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_place_info_type));
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		for (String category : categories) {
			RoadCrewUi.addFullWidthButton(activity, content, messageCategoryTitle(activity, category), false, v -> {
				dialog.dismiss();
				showMessageDialog(place, category);
			});
		}
		dialog.show();
	}

	private void showMessageDialog(@NonNull RoadCrewPlace place, @NonNull String category) {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null || !ensureStationary(activity)) return;
		EditText input = RoadCrewUi.createInput(activity, activity.getString(R.string.roadcrew_place_message_hint), true);
		input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(600)});
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		LinearLayout content = RoadCrewUi.createPanel(activity, messageCategoryTitle(activity, category));
		if (isTemporaryCategory(category)) RoadCrewUi.addBody(activity, content,
				activity.getString(R.string.roadcrew_place_temporary_note));
		RoadCrewUi.addInput(activity, content, input);
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_send), true, v -> {
			String text = input.getText().toString().trim();
			if (text.isEmpty()) return;
			dialog.dismiss();
			RoadCrewPlacesApi.createMessage(app, place.getId(), category, text, new RoadCrewPlacesApi.Callback<>() {
				@Override public void onSuccess(@NonNull Boolean result) {
					app.showToastMessage(R.string.roadcrew_place_message_sent);
					lastRefreshMillis = 0;
				}
				@Override public void onError(@NonNull Exception error) { showRequestError(activity, error); }
			});
		});
		dialog.show();
	}

	private void showReviewDialog(@NonNull RoadCrewPlace place) {
		MapActivity activity = mapActivitySupplier.get();
		if (activity == null || !ensureStationary(activity)) return;
		LinearLayout content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_place_rate_parking));
		SeekBar security = addScore(activity, content, R.string.roadcrew_place_score_security);
		SeekBar quiet = addScore(activity, content, R.string.roadcrew_place_score_quiet);
		SeekBar access = addScore(activity, content, R.string.roadcrew_place_score_access);
		SeekBar facilities = addScore(activity, content, R.string.roadcrew_place_score_facilities);
		CheckBox theft = new CheckBox(activity);
		theft.setText(R.string.roadcrew_place_theft_checkbox);
		theft.setTextColor(RoadCrewUi.TEXT);
		content.addView(theft, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		EditText comment = RoadCrewUi.createInput(activity, activity.getString(R.string.roadcrew_place_review_hint), true);
		comment.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
		RoadCrewUi.addInput(activity, content, comment);
		AlertDialog dialog = RoadCrewUi.createDialog(activity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(activity, content);
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(activity, buttons, activity.getString(R.string.roadcrew_button_save), true, v -> {
			dialog.dismiss();
			RoadCrewPlacesApi.saveReview(app, place.getId(), score(security), score(quiet), score(access),
					score(facilities), theft.isChecked(), comment.getText().toString().trim(), new RoadCrewPlacesApi.Callback<>() {
						@Override public void onSuccess(@NonNull Boolean result) { app.showToastMessage(R.string.roadcrew_place_review_saved); }
						@Override public void onError(@NonNull Exception error) { showRequestError(activity, error); }
					});
		});
		dialog.show();
	}

	private SeekBar addScore(@NonNull MapActivity activity, @NonNull LinearLayout content, int titleRes) {
		TextView label = RoadCrewUi.addSectionTitle(activity, content, activity.getString(titleRes) + ": 3/5");
		SeekBar bar = new SeekBar(activity);
		bar.setMax(4);
		bar.setProgress(2);
		bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				label.setText(activity.getString(titleRes) + ": " + (progress + 1) + "/5");
			}
			@Override public void onStartTrackingTouch(SeekBar seekBar) { }
			@Override public void onStopTrackingTouch(SeekBar seekBar) { }
		});
		content.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
		return bar;
	}

	private void voteMessage(@NonNull MapActivity activity, @NonNull RoadCrewPlace place,
			@NonNull String messageId, @NonNull String vote) {
		RoadCrewPlacesApi.voteMessage(app, messageId, vote, new RoadCrewPlacesApi.Callback<>() {
			@Override public void onSuccess(@NonNull Boolean result) { app.showToastMessage(R.string.roadcrew_place_vote_saved); }
			@Override public void onError(@NonNull Exception error) { showRequestError(activity, error); }
		});
	}

	private void refreshIfNeeded(@NonNull LatLon center) {
		long now = System.currentTimeMillis();
		if (!refreshRunning && (now - lastRefreshMillis >= REFRESH_INTERVAL_MILLIS
				|| lastRefreshCenter == null || MapUtils.getDistance(center, lastRefreshCenter) > 25_000)) {
			refresh(center, null);
		}
	}

	private void refresh(@NonNull LatLon center, @Nullable Runnable done) {
		if (refreshRunning) {
			if (done != null) done.run();
			return;
		}
		refreshRunning = true;
		RoadCrewPlacesApi.listPlaces(app, center, 100, new RoadCrewPlacesApi.Callback<>() {
			@Override public void onSuccess(@NonNull List<RoadCrewPlace> result) {
				places = result;
				lastRefreshMillis = System.currentTimeMillis();
				lastRefreshCenter = center;
				refreshRunning = false;
				OsmandMapTileView mapView = mapViewSupplier.get();
				if (mapView != null) mapView.refreshMap();
				if (done != null) done.run();
			}
			@Override public void onError(@NonNull Exception error) {
				refreshRunning = false;
				if (done != null) done.run();
			}
		});
	}

	private void drawMarker(@NonNull Canvas canvas, @NonNull RoadCrewPlace place, float x, float y) {
		float radius = dp(15);
		float centerY = y - dp(9);
		markerPath.reset();
		markerPath.addCircle(x, centerY, radius, Path.Direction.CW);
		markerPath.moveTo(x - dp(6), centerY + radius - dp(2));
		markerPath.lineTo(x, y + dp(4));
		markerPath.lineTo(x + dp(6), centerY + radius - dp(2));
		markerPath.close();
		canvas.drawPath(markerPath, markerPaint);
		canvas.drawCircle(x, centerY, radius, markerBorderPaint);
		Paint.FontMetrics metrics = markerTextPaint.getFontMetrics();
		canvas.drawText(shortKind(place.getKind()), x, centerY - (metrics.ascent + metrics.descent) / 2, markerTextPaint);
		if (place.getActiveMessageCount() > 0) {
			String label = String.valueOf(place.getActiveMessageCount());
			float width = labelTextPaint.measureText(label) + dp(12);
			RectF rect = new RectF(x + dp(10), centerY - dp(22), x + dp(10) + width, centerY - dp(4));
			canvas.drawRoundRect(rect, dp(8), dp(8), labelPaint);
			canvas.drawText(label, rect.centerX(), rect.centerY() - (labelTextPaint.ascent() + labelTextPaint.descent()) / 2,
					centered(labelTextPaint));
		}
	}

	private Paint centered(@NonNull Paint paint) { paint.setTextAlign(Paint.Align.CENTER); return paint; }

	private boolean ensureStationary(@NonNull MapActivity activity) {
		Location location = app.getLocationProvider().getLastKnownLocation();
		if (location != null && location.hasSpeed() && location.getSpeed() > MOVING_SPEED_LIMIT_METERS_PER_SECOND) {
			app.showToastMessage(R.string.roadcrew_place_stop_to_post);
			return false;
		}
		return true;
	}

	@NonNull
	private LatLon currentLocationOrMapCenter() {
		Location location = app.getLocationProvider().getLastKnownLocation();
		if (location != null) return new LatLon(location.getLatitude(), location.getLongitude());
		OsmandMapTileView mapView = mapViewSupplier.get();
		return mapView == null ? new LatLon(42.7339, 25.4858) : mapView.getCurrentRotatedTileBox().getCenterLatLon();
	}

	@Nullable private RoadCrewPlace findById(@NonNull String id) {
		for (RoadCrewPlace place : places) if (id.equals(place.getId())) return place;
		return null;
	}

	@NonNull private String amenityName(@NonNull MapActivity activity, @NonNull Amenity amenity) {
		String name = amenity.getName();
		return name == null || name.trim().isEmpty() ? activity.getString(R.string.roadcrew_places_unnamed_parking) : name.trim();
	}

	private void showRequestError(@NonNull MapActivity activity, @NonNull Exception error) {
		String message = error.getMessage();
		app.showToastMessage(message == null || message.isEmpty() ? activity.getString(R.string.roadcrew_place_request_failed) : message);
	}

	@NonNull private String kindTitle(@NonNull MapActivity activity, @NonNull String kind) {
		switch (kind) {
			case "PARKING": return activity.getString(R.string.roadcrew_place_kind_parking);
			case "PORT": return activity.getString(R.string.roadcrew_place_kind_port);
			case "FACTORY": return activity.getString(R.string.roadcrew_place_kind_factory);
			case "BORDER": return activity.getString(R.string.roadcrew_place_kind_border);
			case "SERVICE": return activity.getString(R.string.roadcrew_place_kind_service);
			case "ROAD_INFO": return activity.getString(R.string.roadcrew_place_kind_road_info);
			default: return activity.getString(R.string.roadcrew_place_kind_other);
		}
	}

	@NonNull private String messageCategoryTitle(@NonNull MapActivity activity, @NonNull String category) {
		switch (category) {
			case "ENTRANCE": return activity.getString(R.string.roadcrew_place_category_entrance);
			case "DOCUMENTS": return activity.getString(R.string.roadcrew_place_category_documents);
			case "QUEUE": return activity.getString(R.string.roadcrew_place_category_queue);
			case "PARKING_AVAILABILITY": return activity.getString(R.string.roadcrew_place_category_parking);
			case "ACCESS_PROBLEM": return activity.getString(R.string.roadcrew_place_category_access);
			case "HAZARD": return activity.getString(R.string.roadcrew_place_category_hazard);
			default: return activity.getString(R.string.roadcrew_place_category_general);
		}
	}

	private boolean isTemporaryCategory(@NonNull String category) {
		return "QUEUE".equals(category) || "PARKING_AVAILABILITY".equals(category)
				|| "ACCESS_PROBLEM".equals(category) || "HAZARD".equals(category);
	}

	@NonNull private String shortKind(@NonNull String kind) {
		switch (kind) {
			case "PARKING": return "P";
			case "PORT": return "T";
			case "FACTORY": return "F";
			case "BORDER": return "B";
			case "SERVICE": return "S";
			default: return "i";
		}
	}

	@NonNull private String formatDistance(double km) {
		return km < 1 ? Math.round(km * 1000) + " m" : String.format(Locale.getDefault(), "%.1f km", km);
	}

	@NonNull private String formatRelative(@NonNull MapActivity activity, long time) {
		long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60_000);
		return minutes < 1 ? activity.getString(R.string.roadcrew_time_just_now)
				: minutes < 24 * 60 ? activity.getString(R.string.roadcrew_time_minutes_ago, minutes)
				: DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(time);
	}

	@NonNull private String formatExpiry(@NonNull MapActivity activity, long expiresAt) {
		if (expiresAt <= 0) return "";
		long minutes = Math.max(0, (expiresAt - System.currentTimeMillis()) / 60_000);
		return "  ·  " + activity.getString(R.string.roadcrew_place_expires_in, minutes);
	}

	@NonNull private String detailsSignature(@NonNull RoadCrewPlace.Details details) {
		StringBuilder result = new StringBuilder().append(details.ratingCount).append(':').append(details.averageRating);
		for (RoadCrewPlace.Message message : details.messages) result.append('|').append(message.id).append(':')
				.append(message.stillValidCount).append(':').append(message.outdatedCount);
		return result.toString();
	}

	private int score(@NonNull SeekBar bar) { return bar.getProgress() + 1; }
	private int dp(float value) { return (int) (value * app.getResources().getDisplayMetrics().density); }
	private float sp(float value) { return value * app.getResources().getDisplayMetrics().scaledDensity; }
}
