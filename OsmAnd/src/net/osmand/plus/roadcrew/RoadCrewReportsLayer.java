package net.osmand.plus.roadcrew;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathDashPathEffect;
import android.graphics.PointF;
import android.graphics.RectF;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.roadcrew.RoadCrewReportsSync.RoadCrewChatMessage;
import net.osmand.plus.roadcrew.RoadCrewReportsSync.RoadCrewNotification;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.ContextMenuLayer.IContextMenuProvider;
import net.osmand.plus.views.layers.MapSelectionResult;
import net.osmand.plus.views.layers.MapSelectionRules;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class RoadCrewReportsLayer extends OsmandMapLayer implements IContextMenuProvider {

	private static final String ROADCREW_PACKAGE = "org.roadcrew.app";
	private static final int MIN_ZOOM = 5;
	private static final long PROXIMITY_CHECK_INTERVAL_MILLIS = 5 * 1000;
	private static final long PROXIMITY_STARTUP_GRACE_MILLIS = 15 * 1000;
	private static final long MIN_REPORT_AGE_FOR_PROMPT_MILLIS = 2 * 60 * 1000;
	private static final long NOTIFICATION_CHECK_INTERVAL_MILLIS = 20 * 1000;
	private static final long HELP_CHAT_REFRESH_INTERVAL_MILLIS = 2 * 1000;
	private static final double PROMPT_RADIUS_METERS = 700;
	private static final float MARKER_TOUCH_RADIUS_DP = 36;
	private static final int HELP_CHAT_MESSAGE_MAX_LENGTH = 1000;
	private static final String PUSH_KIND_EXTRA = "roadcrew_push_kind";
	private static final String PUSH_REFERENCE_ID_EXTRA = "roadcrew_push_reference_id";

	private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint markerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint directionBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint directionArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionSlashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionRoadHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionRoadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint restrictionRoadStripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path restrictionRoadPath = new Path();
	private final Path restrictionTruckPath = new Path();
	private final Path markerPath = new Path();
	private final Path directionArrowPath = new Path();
	private final RectF labelRect = new RectF();
	private final RectF touchLabelRect = new RectF();
	private final Set<String> promptedReportIds = new HashSet<>();
	private final Set<String> shownNotificationIds = new HashSet<>();
	private final Set<String> openHelpReportIds = new HashSet<>();
	private final Set<String> openDirectChatRoomIds = new HashSet<>();
	private static RoadCrewReportsLayer activeLayer;

	private long lastProximityCheckMillis;
	private long lastNotificationCheckMillis;
	private boolean proximityPromptVisible;
	private boolean notificationPromptVisible;
	@Nullable
	private AlertDialog activeNotificationDialog;
	@Nullable
	private RoadCrewVoiceAlerts voiceAlerts;
	@Nullable
	private RoadCrewTruckRestrictionsProvider truckRestrictionsProvider;
	@Nullable
	private RoadCrewMapObservationCoordinator mapObservationCoordinator;
	@Nullable
	private RoadCrewPlacesController placesController;
	private RoadCrewValidationController validationController;
	private final long createdAtMillis = System.currentTimeMillis();

	public RoadCrewReportsLayer(@NonNull OsmandApplication app) {
		super(app);
	}

	public static boolean isEnabled(@NonNull OsmandApplication app) {
		return ROADCREW_PACKAGE.equals(app.getPackageName());
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		activeLayer = this;
		if (voiceAlerts != null) {
			voiceAlerts.shutdown();
		}
		voiceAlerts = new RoadCrewVoiceAlerts(getApplication());
		if (truckRestrictionsProvider != null) {
			truckRestrictionsProvider.shutdown();
		}
		truckRestrictionsProvider = new RoadCrewTruckRestrictionsProvider(getApplication());
		RoadCrewMapObservationCoordinator.ensureStarted(getApplication());
		RoadCrewMapObservationCoordinator.onMapActivityAvailable(getApplication());
		RoadCrewShadowRouteDiagnostics.ensureStarted(getApplication());
		mapObservationCoordinator = RoadCrewMapObservationCoordinator.getInstance(getApplication());
		placesController = new RoadCrewPlacesController(getApplication(), this::getMapActivity, this::getMapView);
		if (validationController != null) { validationController.close(); }
		validationController = new RoadCrewValidationController(getApplication(), this::getMapActivity,
				() -> notificationPromptVisible || proximityPromptVisible);
		createResources();
	}

	@Override
	public void destroyLayer() {
		if (validationController != null) { validationController.close(); validationController = null; }
		super.destroyLayer();
		if (activeLayer == this) {
			activeLayer = null;
		}
		if (voiceAlerts != null) {
			voiceAlerts.shutdown();
			voiceAlerts = null;
		}
		if (truckRestrictionsProvider != null) {
			truckRestrictionsProvider.shutdown();
			truckRestrictionsProvider = null;
		}
		if (mapObservationCoordinator != null) {
			mapObservationCoordinator = null;
		}
		placesController = null;
	}

	static void setMapObservationEnabled(@NonNull OsmandApplication app, boolean enabled) {
		RoadCrewMapObservationCoordinator.setEnabledForApp(app, enabled);
	}

	static void requestSegmentValidation() {
		if (activeLayer != null && activeLayer.validationController != null) {
			activeLayer.validationController.requestManually();
		}
	}

	static void showPendingTripReviews() {
		if (activeLayer != null && activeLayer.validationController != null) {
			activeLayer.validationController.requestPendingTrips();
		}
	}

	static void openInboxNotification(@NonNull MapActivity mapActivity,
			@NonNull RoadCrewNotificationInbox.Entry entry) {
		if (activeLayer == null) {
			mapActivity.getApp().showToastMessage(R.string.roadcrew_layer_not_ready);
			return;
		}
		RoadCrewNotification notification = new RoadCrewNotification(entry.id, entry.referenceId,
				entry.kind, entry.title, entry.body, entry.createdAt);
		if ("HELP_NEARBY".equals(entry.kind)) {
			activeLayer.showHelpNotificationDialog(mapActivity, notification);
		} else if ("HELP_CHAT_MESSAGE".equals(entry.kind)) {
			activeLayer.showHelpChatMessageNotificationDialog(mapActivity, notification);
		} else if ("DIRECT_CHAT_MESSAGE".equals(entry.kind)) {
			activeLayer.showDirectChatNotificationDialog(mapActivity, notification);
		} else if ("PLATE_SAFETY_ALERT".equals(entry.kind)) {
			activeLayer.showPlateSafetyAlertDialog(mapActivity, notification);
		} else {
			activeLayer.showGenericNotificationDialog(mapActivity, notification);
		}
	}

	static void showPlaceChannels() {
		if (activeLayer != null && activeLayer.placesController != null) {
			activeLayer.placesController.showHome();
		}
	}

	public static void showNearbyHelpReports(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app) {
		if (activeLayer == null) {
			app.showToastMessage(R.string.roadcrew_layer_not_ready);
			return;
		}
		activeLayer.showNearbyHelpReportsDialog(mapActivity);
	}

	public static boolean handlePushIntent(@NonNull MapActivity mapActivity, @Nullable Intent intent) {
		if (activeLayer == null || intent == null) {
			return false;
		}
		String kind = intent.getStringExtra(PUSH_KIND_EXTRA);
		String referenceId = intent.getStringExtra(PUSH_REFERENCE_ID_EXTRA);
		if (kind == null || kind.trim().isEmpty() || referenceId == null || referenceId.trim().isEmpty()) {
			return false;
		}
		intent.removeExtra(PUSH_KIND_EXTRA);
		intent.removeExtra(PUSH_REFERENCE_ID_EXTRA);
		RoadCrewNotificationInbox.markByReference(mapActivity, kind, referenceId);
		RoadCrewNeonHud.apply(mapActivity);
		activeLayer.openPushReference(mapActivity, kind, referenceId);
		return true;
	}

	@Override
	protected void updateResources() {
		super.updateResources();
		createResources();
	}

	private void createResources() {
		markerStrokePaint.setStyle(Paint.Style.STROKE);
		markerStrokePaint.setStrokeWidth(dp(2));
		markerStrokePaint.setColor(Color.WHITE);

		textPaint.setColor(Color.WHITE);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setFakeBoldText(true);
		textPaint.setTextSize(sp(12));

		labelBackgroundPaint.setStyle(Paint.Style.FILL);
		labelBackgroundPaint.setColor(Color.argb(230, 11, 31, 42));

		labelStrokePaint.setStyle(Paint.Style.STROKE);
		labelStrokePaint.setStrokeWidth(dp(1));
		labelStrokePaint.setColor(Color.argb(210, 255, 255, 255));

		directionBadgePaint.setStyle(Paint.Style.FILL);
		directionBadgePaint.setColor(Color.rgb(11, 31, 42));

		directionArrowPaint.setStyle(Paint.Style.STROKE);
		directionArrowPaint.setStrokeWidth(dp(1.6f));
		directionArrowPaint.setStrokeCap(Paint.Cap.ROUND);
		directionArrowPaint.setStrokeJoin(Paint.Join.ROUND);
		directionArrowPaint.setColor(Color.WHITE);

		restrictionFillPaint.setStyle(Paint.Style.FILL);
		restrictionFillPaint.setColor(Color.WHITE);

		restrictionBorderPaint.setStyle(Paint.Style.STROKE);
		restrictionBorderPaint.setStrokeWidth(dp(2));
		restrictionBorderPaint.setColor(Color.rgb(220, 38, 38));

		restrictionTextPaint.setColor(Color.rgb(17, 24, 39));
		restrictionTextPaint.setTextAlign(Paint.Align.CENTER);
		restrictionTextPaint.setFakeBoldText(true);

		restrictionIconPaint.setStyle(Paint.Style.FILL);
		restrictionIconPaint.setColor(Color.rgb(17, 24, 39));

		restrictionShadowPaint.setStyle(Paint.Style.FILL);
		restrictionShadowPaint.setColor(Color.argb(82, 0, 0, 0));

		restrictionSlashPaint.setStyle(Paint.Style.STROKE);
		restrictionSlashPaint.setStrokeWidth(dp(3));
		restrictionSlashPaint.setColor(Color.rgb(220, 38, 38));
		restrictionSlashPaint.setStrokeCap(Paint.Cap.ROUND);

		restrictionRoadHaloPaint.setStyle(Paint.Style.STROKE);
		restrictionRoadHaloPaint.setStrokeWidth(dp(15));
		restrictionRoadHaloPaint.setColor(Color.argb(235, 255, 255, 255));
		restrictionRoadHaloPaint.setStrokeCap(Paint.Cap.ROUND);
		restrictionRoadHaloPaint.setStrokeJoin(Paint.Join.ROUND);

		restrictionRoadPaint.setStyle(Paint.Style.STROKE);
		restrictionRoadPaint.setStrokeWidth(dp(11));
		restrictionRoadPaint.setColor(Color.argb(235, 250, 204, 21));
		restrictionRoadPaint.setStrokeCap(Paint.Cap.ROUND);
		restrictionRoadPaint.setStrokeJoin(Paint.Join.ROUND);

		restrictionRoadStripePaint.setStyle(Paint.Style.FILL_AND_STROKE);
		restrictionRoadStripePaint.setColor(Color.argb(225, 17, 24, 39));
		restrictionRoadStripePaint.setPathEffect(createWarningTapeStripeEffect());
	}

	@NonNull
	private PathDashPathEffect createWarningTapeStripeEffect() {
		float roadHalfWidth = dp(4.4f);
		float stripeWidth = dp(4.8f);
		float stripeLean = dp(6.2f);
		Path stripe = new Path();
		stripe.moveTo(-stripeWidth / 2f - stripeLean, -roadHalfWidth);
		stripe.lineTo(stripeWidth / 2f - stripeLean, -roadHalfWidth);
		stripe.lineTo(stripeWidth / 2f + stripeLean, roadHalfWidth);
		stripe.lineTo(-stripeWidth / 2f + stripeLean, roadHalfWidth);
		stripe.close();
		return new PathDashPathEffect(stripe, dp(13), 0, PathDashPathEffect.Style.ROTATE);
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		List<RoadCrewReport> reports = RoadCrewReportsRepository.getVisibleReports(getApplication());
		RoadCrewReportsSync.syncPeriodically(getApplication());
		if (validationController == null || !validationController.isShowing()) {
			checkHelpNotifications();
			checkNearbyReports(reports);
		}
		checkVoiceAlerts(reports);
		if (tileBox.getZoom() < MIN_ZOOM) {
			return;
		}
		drawTruckRestrictions(canvas, tileBox);
		if (placesController != null) {
			placesController.draw(canvas, tileBox);
		}
		for (RoadCrewReport report : reports) {
			LatLon latLon = report.getLocation();
			if (!tileBox.containsLatLon(latLon)) {
				continue;
			}
			float x = tileBox.getPixXFromLatLon(latLon.getLatitude(), latLon.getLongitude());
			float y = tileBox.getPixYFromLatLon(latLon.getLatitude(), latLon.getLongitude());
			drawReport(canvas, tileBox, report, x, y);
		}
	}

	private void checkVoiceAlerts(@NonNull List<RoadCrewReport> reports) {
		if (voiceAlerts != null) {
			voiceAlerts.check(reports);
		}
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	private void drawTruckRestrictions(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox) {
		if (truckRestrictionsProvider == null || !RoadCrewSettings.shouldShowTruckRestrictions(getApplication())) {
			return;
		}
		List<RoadCrewTruckRestrictionsProvider.TruckRestriction> restrictions =
				truckRestrictionsProvider.getRestrictions(tileBox);
		if (restrictions.isEmpty()) {
			return;
		}
		drawTruckRestrictionRoads(canvas, tileBox, restrictions);
		List<PointF> drawnCenters = new ArrayList<>();
		float minimumGap = dp(46);
		int drawn = 0;
		for (RoadCrewTruckRestrictionsProvider.TruckRestriction restriction : restrictions) {
			if (!tileBox.containsLatLon(restriction.latitude, restriction.longitude)) {
				continue;
			}
			float x = tileBox.getPixXFromLatLon(restriction.latitude, restriction.longitude);
			float y = tileBox.getPixYFromLatLon(restriction.latitude, restriction.longitude);
			if (isOverlappingExistingRestriction(drawnCenters, x, y, minimumGap)) {
				continue;
			}
			drawTruckRestrictionSign(canvas, restriction, x, y);
			drawnCenters.add(new PointF(x, y));
			drawn++;
			if (drawn >= 80) {
				break;
			}
		}
	}

	private void drawTruckRestrictionRoads(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
			@NonNull List<RoadCrewTruckRestrictionsProvider.TruckRestriction> restrictions) {
		Set<String> drawnRoads = new HashSet<>();
		int drawn = 0;
		for (RoadCrewTruckRestrictionsProvider.TruckRestriction restriction : restrictions) {
			if (restriction.roadGeometry.size() < 2 || !drawnRoads.add(restriction.geometryKey)) {
				continue;
			}
			if (drawTruckRestrictionRoad(canvas, tileBox, restriction.roadGeometry)) {
				drawn++;
			}
			if (drawn >= 80) {
				break;
			}
		}
	}

	private boolean drawTruckRestrictionRoad(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
			@NonNull List<LatLon> roadGeometry) {
		restrictionRoadPath.reset();
		boolean pathStarted = false;
		boolean hasVisiblePoint = false;
		for (LatLon latLon : roadGeometry) {
			float x = tileBox.getPixXFromLatLon(latLon.getLatitude(), latLon.getLongitude());
			float y = tileBox.getPixYFromLatLon(latLon.getLatitude(), latLon.getLongitude());
			if (tileBox.containsLatLon(latLon)) {
				hasVisiblePoint = true;
			}
			if (pathStarted) {
				restrictionRoadPath.lineTo(x, y);
			} else {
				restrictionRoadPath.moveTo(x, y);
				pathStarted = true;
			}
		}
		if (!pathStarted || !hasVisiblePoint) {
			return false;
		}
		canvas.drawPath(restrictionRoadPath, restrictionRoadHaloPaint);
		canvas.drawPath(restrictionRoadPath, restrictionRoadPaint);
		canvas.drawPath(restrictionRoadPath, restrictionRoadStripePaint);
		return true;
	}

	private boolean isOverlappingExistingRestriction(@NonNull List<PointF> drawnCenters, float x, float y,
			float minimumGap) {
		for (PointF center : drawnCenters) {
			if (Math.hypot(center.x - x, center.y - y) < minimumGap) {
				return true;
			}
		}
		return false;
	}

	private void drawTruckRestrictionSign(@NonNull Canvas canvas,
			@NonNull RoadCrewTruckRestrictionsProvider.TruckRestriction restriction, float x, float y) {
		if (restriction.kind == RoadCrewTruckRestrictionsProvider.RestrictionKind.HGV_NO) {
			drawTruckNoEntrySign(canvas, x, y);
			return;
		}

		String label = restriction.label;
		float textSize = label.length() > 5 ? sp(8) : label.length() > 4 ? sp(9) : sp(10);
		restrictionTextPaint.setTextSize(textSize);
		float radius = Math.max(dp(14), restrictionTextPaint.measureText(label) / 2f + dp(5));
		float shadowOffset = dp(2);

		canvas.drawCircle(x + shadowOffset, y + shadowOffset, radius + dp(1), restrictionShadowPaint);
		canvas.drawCircle(x, y, radius, restrictionFillPaint);
		canvas.drawCircle(x, y, radius, restrictionBorderPaint);

		Paint.FontMetrics metrics = restrictionTextPaint.getFontMetrics();
		float baseline = y - (metrics.ascent + metrics.descent) / 2f;
		canvas.drawText(label, x, baseline, restrictionTextPaint);

		if (restriction.kind == RoadCrewTruckRestrictionsProvider.RestrictionKind.HAZMAT_NO) {
			canvas.drawLine(x - radius * 0.58f, y + radius * 0.58f,
					x + radius * 0.58f, y - radius * 0.58f, restrictionSlashPaint);
		}
	}

	private void drawTruckNoEntrySign(@NonNull Canvas canvas, float x, float y) {
		float radius = dp(14);
		float shadowOffset = dp(2);
		canvas.drawCircle(x + shadowOffset, y + shadowOffset, radius + dp(1), restrictionShadowPaint);
		canvas.drawCircle(x, y, radius, restrictionFillPaint);
		canvas.drawCircle(x, y, radius, restrictionBorderPaint);

		float truckLeft = x - radius * 0.63f;
		float truckRight = x + radius * 0.58f;
		float truckTop = y - radius * 0.22f;
		float truckBottom = y + radius * 0.26f;
		float cargoLeft = x - radius * 0.06f;
		float cargoBottom = y + radius * 0.18f;
		canvas.drawRect(cargoLeft, truckTop, truckRight, cargoBottom, restrictionIconPaint);

		restrictionTruckPath.reset();
		restrictionTruckPath.moveTo(truckLeft, cargoBottom);
		restrictionTruckPath.lineTo(truckLeft, y - radius * 0.02f);
		restrictionTruckPath.lineTo(x - radius * 0.43f, truckTop);
		restrictionTruckPath.lineTo(cargoLeft - radius * 0.06f, truckTop);
		restrictionTruckPath.lineTo(cargoLeft - radius * 0.06f, cargoBottom);
		restrictionTruckPath.close();
		canvas.drawPath(restrictionTruckPath, restrictionIconPaint);

		restrictionTruckPath.reset();
		restrictionTruckPath.moveTo(x - radius * 0.48f, y - radius * 0.13f);
		restrictionTruckPath.lineTo(x - radius * 0.37f, truckTop + radius * 0.06f);
		restrictionTruckPath.lineTo(x - radius * 0.16f, truckTop + radius * 0.06f);
		restrictionTruckPath.lineTo(x - radius * 0.16f, y - radius * 0.04f);
		restrictionTruckPath.close();
		canvas.drawPath(restrictionTruckPath, restrictionFillPaint);

		canvas.drawRect(truckLeft, cargoBottom, truckRight, truckBottom, restrictionIconPaint);
		drawTruckWheel(canvas, x - radius * 0.39f, y + radius * 0.29f, radius);
		drawTruckWheel(canvas, x + radius * 0.36f, y + radius * 0.29f, radius);
	}

	private void drawTruckWheel(@NonNull Canvas canvas, float x, float y, float signRadius) {
		float outer = signRadius * 0.15f;
		float inner = signRadius * 0.07f;
		canvas.drawCircle(x, y, outer, restrictionIconPaint);
		canvas.drawCircle(x, y, inner, restrictionFillPaint);
	}

	@Override
	public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		if (tileBox.getZoom() < MIN_ZOOM) {
			return false;
		}
		RoadCrewPlace place = placesController == null ? null : placesController.findTapped(point, tileBox);
		if (place != null) {
			placesController.showPlace(place);
			return true;
		}
		RoadCrewReport report = findTappedReport(point, tileBox);
		if (report == null) {
			return false;
		}
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return false;
		}
		showReportDetailsDialog(mapActivity, report);
		return true;
	}

	@Override
	public void collectObjectsFromPoint(@NonNull MapSelectionResult result, @NonNull MapSelectionRules rules) {
		if (result.getTileBox().getZoom() < MIN_ZOOM) {
			return;
		}
		RoadCrewPlace place = placesController == null ? null : placesController.findTapped(result.getPoint(), result.getTileBox());
		if (place != null) {
			result.collect(place, this);
			result.setObjectLatLon(place.getLocation());
			return;
		}
		RoadCrewReport report = findTappedReport(result.getPoint(), result.getTileBox());
		if (report != null) {
			result.collect(report, this);
			result.setObjectLatLon(report.getLocation());
		}
	}

	@Override
	public boolean runExclusiveAction(@Nullable Object object, boolean unknownLocation) {
		if (object instanceof RoadCrewPlace place && placesController != null) {
			placesController.showPlace(place);
			return true;
		}
		if (!(object instanceof RoadCrewReport report)) {
			return false;
		}
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return false;
		}
		showReportDetailsDialog(mapActivity, report);
		return true;
	}

	@Override
	public LatLon getObjectLocation(Object object) {
		if (object instanceof RoadCrewPlace place) {
			return place.getLocation();
		}
		if (object instanceof RoadCrewReport report) {
			return report.getLocation();
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object object) {
		if (object instanceof RoadCrewPlace place) {
			return new PointDescription(PointDescription.POINT_TYPE_MARKER, place.getName());
		}
		if (object instanceof RoadCrewReport report) {
			return new PointDescription(PointDescription.POINT_TYPE_MARKER, report.getType().getTitle(getApplication()));
		}
		return null;
	}

	private void drawReport(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
			@NonNull RoadCrewReport report, float x, float y) {
		float radius = dp(14);
		float pointerHeight = dp(8);
		float markerCenterY = y - pointerHeight;

		markerPath.reset();
		markerPath.addCircle(x, markerCenterY, radius, Path.Direction.CW);
		markerPath.moveTo(x - dp(6), markerCenterY + radius - dp(2));
		markerPath.lineTo(x, markerCenterY + radius + pointerHeight);
		markerPath.lineTo(x + dp(6), markerCenterY + radius - dp(2));
		markerPath.close();

		markerPaint.setColor(report.isHelpProbablyResolved() ? Color.rgb(156, 163, 175) : report.getType().getColor());
		canvas.drawPath(markerPath, markerPaint);
		canvas.drawPath(markerPath, markerStrokePaint);
		canvas.drawText(report.getType().getShortLabel(), x, markerCenterY + dp(5), textPaint);
		drawDirectionBadge(canvas, tileBox, report, x, markerCenterY);

		drawLabel(canvas, report, x, markerCenterY - radius - dp(8));
	}

	private void drawDirectionBadge(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
			@NonNull RoadCrewReport report, float x, float markerCenterY) {
		if (report.getDirection() == RoadCrewReportDirection.UNKNOWN) {
			return;
		}
		float badgeX = x + dp(11);
		float badgeY = markerCenterY + dp(10);
		float badgeRadius = dp(7);
		canvas.drawCircle(badgeX, badgeY, badgeRadius, directionBadgePaint);
		canvas.drawCircle(badgeX, badgeY, badgeRadius, markerStrokePaint);

		float angle = 0;
		if (report.hasDirectionBearing()) {
			float startY = tileBox.getPixYFromLatLon(report.getLocation().getLatitude(),
					report.getLocation().getLongitude());
			LatLon end = MapUtils.rhumbDestinationPoint(report.getLocation(), 30,
					report.getDirectionBearing());
			float endX = tileBox.getPixXFromLatLon(end.getLatitude(), end.getLongitude());
			float endY = tileBox.getPixYFromLatLon(end.getLatitude(), end.getLongitude());
			angle = (float) Math.toDegrees(Math.atan2(endY - startY, endX - x));
		}
		canvas.save();
		canvas.translate(badgeX, badgeY);
		canvas.rotate(angle);
		directionArrowPath.reset();
		float tail = dp(3.7f);
		float head = dp(4.1f);
		float wing = dp(2.2f);
		directionArrowPath.moveTo(-tail, 0);
		directionArrowPath.lineTo(head, 0);
		directionArrowPath.moveTo(head, 0);
		directionArrowPath.lineTo(head - wing, -wing);
		directionArrowPath.moveTo(head, 0);
		directionArrowPath.lineTo(head - wing, wing);
		if (report.getDirection() == RoadCrewReportDirection.BOTH_DIRECTIONS) {
			directionArrowPath.moveTo(-tail, 0);
			directionArrowPath.lineTo(-tail + wing, -wing);
			directionArrowPath.moveTo(-tail, 0);
			directionArrowPath.lineTo(-tail + wing, wing);
		}
		canvas.drawPath(directionArrowPath, directionArrowPaint);
		canvas.restore();
	}

	private void drawLabel(@NonNull Canvas canvas, @NonNull RoadCrewReport report, float x, float y) {
		LabelLayout layout = calculateLabelLayout(report, x, y, canvas.getWidth(), labelRect);
		float cornerRadius = dp(6);
		canvas.drawRoundRect(labelRect, cornerRadius, cornerRadius, labelBackgroundPaint);
		canvas.drawRoundRect(labelRect, cornerRadius, cornerRadius, labelStrokePaint);
		canvas.drawText(layout.label, layout.centerX, layout.textBaseline, textPaint);
	}

	private void checkNearbyReports(@NonNull List<RoadCrewReport> reports) {
		long now = System.currentTimeMillis();
		if (proximityPromptVisible
				|| now - createdAtMillis < PROXIMITY_STARTUP_GRACE_MILLIS
				|| now - lastProximityCheckMillis < PROXIMITY_CHECK_INTERVAL_MILLIS) {
			return;
		}
		lastProximityCheckMillis = now;

		MapActivity mapActivity = getMapActivity();
		Location location = getApplication().getLocationProvider().getLastKnownLocation();
		if (mapActivity == null || location == null) {
			return;
		}

		RoadCrewReport nearestReport = null;
		double nearestDistance = PROMPT_RADIUS_METERS;
		for (RoadCrewReport report : reports) {
			if (location.hasBearing() && !report.appliesToBearing(location.getBearing())) {
				continue;
			}
			if (promptedReportIds.contains(report.getId())
					|| report.hasLocalVote()
					|| isReportAuthor(report)
					|| report.isExpired(now)
					|| now - report.getCreatedAtMillis() < MIN_REPORT_AGE_FOR_PROMPT_MILLIS) {
				continue;
			}
			LatLon reportLocation = report.getLocation();
			double distance = MapUtils.getDistance(location.getLatitude(), location.getLongitude(),
					reportLocation.getLatitude(), reportLocation.getLongitude());
			if (distance <= nearestDistance) {
				nearestDistance = distance;
				nearestReport = report;
			}
		}
		if (nearestReport != null) {
			showProximityPrompt(mapActivity, nearestReport);
		}
	}

	private RoadCrewReport findTappedReport(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
		List<RoadCrewReport> reports = RoadCrewReportsRepository.getVisibleReports(getApplication());
		float touchRadius = dp(MARKER_TOUCH_RADIUS_DP);
		RoadCrewReport nearestReport = null;
		double nearestDistance = touchRadius;
		for (int i = reports.size() - 1; i >= 0; i--) {
			RoadCrewReport report = reports.get(i);
			LatLon latLon = report.getLocation();
			if (!tileBox.containsLatLon(latLon)) {
				continue;
			}
			float x = tileBox.getPixXFromLatLon(latLon.getLatitude(), latLon.getLongitude());
			float markerCenterY = tileBox.getPixYFromLatLon(latLon.getLatitude(), latLon.getLongitude()) - dp(8);
			float labelY = markerCenterY - dp(14) - dp(8);
			calculateLabelLayout(report, x, labelY, tileBox.getPixWidth(), touchLabelRect);
			if (touchLabelRect.contains(point.x, point.y)) {
				return report;
			}
			double distance = Math.hypot(point.x - x, point.y - markerCenterY);
			if (distance <= nearestDistance) {
				nearestDistance = distance;
				nearestReport = report;
			}
		}
		return nearestReport;
	}

	@NonNull
	private LabelLayout calculateLabelLayout(@NonNull RoadCrewReport report, float x, float y, float canvasWidth,
			@NonNull RectF outRect) {
		String label = report.getType().getTitle(getApplication()) + " - " + formatAge(report);
		float paddingX = dp(8);
		float paddingY = dp(4);
		float textWidth = textPaint.measureText(label);
		Paint.FontMetrics metrics = textPaint.getFontMetrics();
		float textHeight = metrics.descent - metrics.ascent;
		float labelHalfWidth = textWidth / 2f + paddingX;
		float edgePadding = dp(4);
		float centerX = Math.max(labelHalfWidth + edgePadding,
				Math.min(x, canvasWidth - labelHalfWidth - edgePadding));
		outRect.set(
				centerX - labelHalfWidth,
				y - textHeight - paddingY,
				centerX + labelHalfWidth,
				y + paddingY
		);
		return new LabelLayout(label, centerX, y - textHeight / 2f - metrics.ascent / 2f);
	}

	private void showReportDetailsDialog(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report) {
		if (report.getType() == RoadCrewReportType.HELP) {
			showHelpReportDetailsDialog(mapActivity, report);
			return;
		}
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, report.getType().getTitle(mapActivity));
		RoadCrewUi.addBody(mapActivity, content, createReportDetailsMessage(report));
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		if (report.hasLocalVote()) {
			RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_close), true, v -> dialog.dismiss());
		} else {
			RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_report_gone), false, v -> {
				dialog.dismiss();
				handleReportVote(report, false);
			});
			RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
			RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_report_still_there), true, v -> {
				dialog.dismiss();
				handleReportVote(report, true);
			});
		}
		dialog.show();
	}

	private void showHelpReportDetailsDialog(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report) {
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, report.getType().getTitle(mapActivity));
		RoadCrewUi.addBody(mapActivity, content, createReportDetailsMessage(report));

		LinearLayout actions = new LinearLayout(mapActivity);
		actions.setOrientation(LinearLayout.VERTICAL);
		content.addView(actions, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		RoadCrewUi.addSectionTitle(mapActivity, content, mapActivity.getString(R.string.roadcrew_help_chat_title));

		TextView messagesView = new TextView(mapActivity);
		messagesView.setText(mapActivity.getString(R.string.roadcrew_chat_loading));
		RoadCrewUi.addMessageArea(mapActivity, content, messagesView, 260);

		EditText input = createHelpChatInput(mapActivity);
		RoadCrewUi.addInput(mapActivity, content, input);

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
		Button sendButton = RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_send), true,
				v -> sendHelpChatMessage(input, messagesView, report));
		sendButton.setEnabled(false);

		addHelpPanelActions(mapActivity, report, actions, dialog);
		dialog.setOnShowListener(d -> {
			RoadCrewUi.applyWindow(dialog);
			prepareHelpPanelChat(mapActivity, report, messagesView, sendButton, dialog);
		});
		dialog.show();
	}

	private void showNearbyHelpReportsDialog(@NonNull MapActivity mapActivity) {
		List<RoadCrewReport> helpReports = getNearbyHelpReports();
		if (helpReports.isEmpty()) {
			getApplication().showToastMessage(R.string.roadcrew_nearby_help_empty);
			return;
		}
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_nearby_help_title));
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		for (RoadCrewReport helpReport : helpReports) {
			RoadCrewUi.addFullWidthButton(mapActivity, content, formatNearbyHelpListItem(helpReport),
					false, v -> {
						dialog.dismiss();
						showHelpReportDetailsDialog(mapActivity, helpReport);
					});
		}
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_close), true, v -> dialog.dismiss());
		dialog.show();
	}

	@NonNull
	private List<RoadCrewReport> getNearbyHelpReports() {
		List<RoadCrewReport> helpReports = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (RoadCrewReport report : RoadCrewReportsRepository.getVisibleReports(getApplication())) {
			if (report.getType() == RoadCrewReportType.HELP && !report.isExpired(now)) {
				helpReports.add(report);
			}
		}
		Location location = getApplication().getLocationProvider().getLastKnownLocation();
		if (location != null) {
			Collections.sort(helpReports, Comparator.comparingDouble(report -> {
				LatLon reportLocation = report.getLocation();
				return MapUtils.getDistance(location.getLatitude(), location.getLongitude(),
						reportLocation.getLatitude(), reportLocation.getLongitude());
			}));
		}
		return helpReports;
	}

	@NonNull
	private String formatNearbyHelpListItem(@NonNull RoadCrewReport report) {
		StringBuilder builder = new StringBuilder();
		builder.append(formatNearbyHelpDistance(report))
				.append(" - ")
				.append(formatAge(report));
		if (report.isHelpProbablyResolved()) {
			builder.append(" - ").append(getContext().getString(R.string.roadcrew_nearby_help_probably_resolved));
		}
		if (!report.getDetails().isEmpty()) {
			builder.append("\n").append(report.getDetails());
		}
		return builder.toString();
	}

	@NonNull
	private String formatNearbyHelpDistance(@NonNull RoadCrewReport report) {
		Location location = getApplication().getLocationProvider().getLastKnownLocation();
		if (location == null) {
			return getContext().getString(R.string.roadcrew_nearby_help_distance_unknown);
		}
		LatLon reportLocation = report.getLocation();
		double distanceMeters = MapUtils.getDistance(location.getLatitude(), location.getLongitude(),
				reportLocation.getLatitude(), reportLocation.getLongitude());
		if (distanceMeters < 1000) {
			return Math.round(distanceMeters) + " m";
		}
		return Math.round(distanceMeters / 100.0) / 10.0 + " km";
	}

	private void addHelpPanelActions(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report,
			@NonNull LinearLayout actions, @NonNull AlertDialog dialog) {
		if (isReportAuthor(report) && !report.getId().startsWith("seed-")) {
			if (report.isHelpProbablyResolved()) {
				actions.addView(createActionButton(mapActivity, mapActivity.getString(R.string.roadcrew_help_still_need_help), () -> {
					dialog.dismiss();
					handleReportVote(report, true);
				}));
				actions.addView(createActionButton(mapActivity, mapActivity.getString(R.string.roadcrew_help_resolved), () -> {
					dialog.dismiss();
					confirmResolveHelpReport(mapActivity, report);
				}));
			} else {
				actions.addView(createActionButton(mapActivity, mapActivity.getString(R.string.roadcrew_help_resolved), () -> {
					dialog.dismiss();
					confirmResolveHelpReport(mapActivity, report);
				}));
			}
		} else if (!report.hasLocalVote()) {
			actions.addView(createActionButton(mapActivity, mapActivity.getString(R.string.roadcrew_help_looks_resolved), () -> {
				dialog.dismiss();
				handleReportVote(report, false);
			}));
		}
	}

	@NonNull
	private Button createActionButton(@NonNull MapActivity mapActivity, @NonNull String title, @NonNull Runnable action) {
		Button button = new Button(mapActivity);
		button.setText(title);
		button.setAllCaps(false);
		button.setTextColor(RoadCrewUi.TEXT);
		button.setTextSize(14);
		button.setBackground(RoadCrewUi.roundRect(RoadCrewUi.SURFACE_LIGHT, (int) dp(16)));
		button.setOnClickListener(v -> action.run());
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(46));
		params.topMargin = (int) dp(8);
		button.setLayoutParams(params);
		return button;
	}

	@NonNull
	private String createReportDetailsMessage(@NonNull RoadCrewReport report) {
		String details = report.getDetails();
		String detailsText = report.getType() == RoadCrewReportType.HELP && !details.isEmpty()
				? "\n" + getContext().getString(R.string.roadcrew_report_details_need, details)
				: "";
		return getContext().getString(R.string.roadcrew_report_details_reported, formatReportedAge(report))
				+ detailsText
				+ "\n" + formatReportDirection(report)
				+ (isReportAuthor(report) ? "\n" + getContext().getString(R.string.roadcrew_report_owner_you) : "")
				+ "\n" + getContext().getString(R.string.roadcrew_report_details_still_there, report.getConfirmedCount())
				+ "\n" + getContext().getString(R.string.roadcrew_report_details_gone, report.getDeniedCount())
				+ "\n" + getContext().getString(R.string.roadcrew_report_details_your_vote, formatLocalVote(report))
				+ "\n" + getContext().getString(R.string.roadcrew_report_details_status, formatReportStatus(report));
	}

	@NonNull
	private String formatReportDirection(@NonNull RoadCrewReport report) {
		return switch (report.getDirection()) {
			case ONE_DIRECTION -> getContext().getString(R.string.roadcrew_report_direction_detail_one);
			case BOTH_DIRECTIONS -> getContext().getString(R.string.roadcrew_report_direction_detail_both);
			case UNKNOWN -> getContext().getString(R.string.roadcrew_report_direction_detail_unknown);
		};
	}

	@NonNull
	private String formatReportStatus(@NonNull RoadCrewReport report) {
		if (report.shouldHideLocally()) {
			return getContext().getString(R.string.roadcrew_report_status_hidden);
		}
		if (report.isHelpProbablyResolved()) {
			return getContext().getString(R.string.roadcrew_report_status_probably_resolved);
		}
		return getContext().getString(R.string.roadcrew_report_status_active);
	}

	private void confirmResolveHelpReport(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report) {
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_help_resolve_title));
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_help_resolve_body));
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_cancel), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_help_resolved), true, v -> {
			dialog.dismiss();
			handleResolveHelpReport(report);
		});
		dialog.show();
	}

	private void handleResolveHelpReport(@NonNull RoadCrewReport report) {
		if (report.getId().startsWith("seed-")) {
			getApplication().showToastMessage(R.string.roadcrew_help_resolve_demo_failed);
			return;
		}
		if (!isReportAuthor(report)) {
			getApplication().showToastMessage(R.string.roadcrew_help_resolve_author_only);
			return;
		}
		getApplication().showToastMessage(R.string.roadcrew_help_resolving);
		RoadCrewReportsSync.resolveHelpReport(getApplication(), report,
				new RoadCrewReportsSync.HelpResolveCallback() {
					@Override
					public void onSuccess() {
						getMapView().refreshMap();
						getApplication().showToastMessage(R.string.roadcrew_help_resolved_done);
					}

					@Override
					public void onError(@NonNull Exception error) {
						getApplication().showToastMessage(R.string.roadcrew_help_resolve_failed);
					}
				});
	}

	private void showProximityPrompt(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report) {
		proximityPromptVisible = true;
		promptedReportIds.add(report.getId());
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_report_confirm_title));
		RoadCrewUi.addBody(mapActivity, content,
				mapActivity.getString(R.string.roadcrew_report_confirm_still_there, report.getType().getTitle(mapActivity)));
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_report_gone), false, v -> {
			dialog.dismiss();
			handleReportVote(report, false);
		});
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_later), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_report_still_there), true, v -> {
			dialog.dismiss();
			handleReportVote(report, true);
		});
		dialog.setOnDismissListener(d -> proximityPromptVisible = false);
		dialog.show();
	}

	private void handleReportVote(@NonNull RoadCrewReport report, boolean confirmed) {
		boolean saved = confirmed
				? RoadCrewReportsRepository.confirmReport(getApplication(), report.getId())
				: RoadCrewReportsRepository.denyReport(getApplication(), report.getId());
		getMapView().refreshMap();
		if (saved) {
			RoadCrewReportsSync.syncNow(getApplication());
			getApplication().showToastMessage(confirmed ? R.string.roadcrew_report_vote_confirmed : R.string.roadcrew_report_vote_gone);
		} else {
			getApplication().showToastMessage(R.string.roadcrew_report_vote_already);
		}
	}

	private boolean isReportAuthor(@NonNull RoadCrewReport report) {
		return report.getCreatedBy().equals(RoadCrewReportsRepository.getLocalDeviceId(getApplication()));
	}

	private void checkHelpNotifications() {
		long now = System.currentTimeMillis();
		if (notificationPromptVisible || now - lastNotificationCheckMillis < NOTIFICATION_CHECK_INTERVAL_MILLIS) {
			return;
		}
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null) {
			return;
		}
		lastNotificationCheckMillis = now;
		RoadCrewReportsSync.fetchNotifications(getApplication(), new RoadCrewReportsSync.NotificationsCallback() {
			@Override
			public void onNotifications(@NonNull List<RoadCrewNotification> notifications) {
				RoadCrewNotificationInbox.store(getApplication(), notifications);
				RoadCrewNeonHud.apply(mapActivity);
				for (RoadCrewNotification notification : notifications) {
					if ("HELP_NEARBY".equals(notification.getKind())
							&& !shownNotificationIds.contains(notification.getId())) {
						shownNotificationIds.add(notification.getId());
						showHelpNotificationDialog(mapActivity, notification);
						return;
					}
					if ("PLATE_SAFETY_ALERT".equals(notification.getKind())
							&& !shownNotificationIds.contains(notification.getId())) {
						shownNotificationIds.add(notification.getId());
						showPlateSafetyAlertDialog(mapActivity, notification);
						return;
					}
					if ("DIRECT_CHAT_MESSAGE".equals(notification.getKind())
							&& !shownNotificationIds.contains(notification.getId())) {
						shownNotificationIds.add(notification.getId());
						if (openDirectChatRoomIds.contains(notification.getReportId())) {
							continue;
						}
						showDirectChatNotificationDialog(mapActivity, notification);
						return;
					}
					if ("HELP_CHAT_MESSAGE".equals(notification.getKind())
							&& !shownNotificationIds.contains(notification.getId())) {
						shownNotificationIds.add(notification.getId());
						if (openHelpReportIds.contains(notification.getReportId())) {
							continue;
						}
						showHelpChatMessageNotificationDialog(mapActivity, notification);
						return;
					}
				}
			}

			@Override
			public void onError(@NonNull Exception error) {
				// Network failures are expected on the road; the next polling cycle will retry.
			}
		});
	}

	private void showHelpNotificationDialog(@NonNull MapActivity mapActivity,
			@NonNull RoadCrewNotification notification) {
		notificationPromptVisible = true;
		dismissActiveNotificationDialog();
		LinearLayout content = RoadCrewUi.createPanel(mapActivity,
				notification.getTitle().isEmpty() ? mapActivity.getString(R.string.roadcrew_push_help_nearby_title) : notification.getTitle());
		RoadCrewUi.addBody(mapActivity, content, notification.getBody());
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_later), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_join_chat), true, v -> {
			acknowledgeNotification(mapActivity, notification.getId());
			dialog.dismiss();
			joinAndOpenHelpChat(mapActivity, notification.getReportId());
		});
		setActiveNotificationDialog(dialog);
		dialog.show();
	}

	private void showPlateSafetyAlertDialog(@NonNull MapActivity mapActivity,
			@NonNull RoadCrewNotification notification) {
		notificationPromptVisible = true;
		dismissActiveNotificationDialog();
		LinearLayout content = RoadCrewUi.createPanel(mapActivity,
				notification.getTitle().isEmpty() ? mapActivity.getString(R.string.roadcrew_push_plate_alert_title) : notification.getTitle());
		RoadCrewUi.addBody(mapActivity, content, notification.getBody());
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_ok), false, v -> {
			acknowledgeNotification(mapActivity, notification.getId());
			dialog.dismiss();
		});
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_open_chat), true, v -> {
			acknowledgeNotification(mapActivity, notification.getId());
			dialog.dismiss();
			openPlateAlertChat(mapActivity, notification.getId());
		});
		setActiveNotificationDialog(dialog);
		dialog.show();
	}

	private void showDirectChatNotificationDialog(@NonNull MapActivity mapActivity,
			@NonNull RoadCrewNotification notification) {
		notificationPromptVisible = true;
		dismissActiveNotificationDialog();
		LinearLayout content = RoadCrewUi.createPanel(mapActivity,
				notification.getTitle().isEmpty() ? mapActivity.getString(R.string.roadcrew_push_direct_chat_title) : notification.getTitle());
		RoadCrewUi.addBody(mapActivity, content, notification.getBody());
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_later), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_open_chat), true, v -> {
			acknowledgeNotification(mapActivity, notification.getId());
			dialog.dismiss();
			showDirectChatDialog(mapActivity, notification.getReportId());
		});
		setActiveNotificationDialog(dialog);
		dialog.show();
	}

	private void showHelpChatMessageNotificationDialog(@NonNull MapActivity mapActivity,
			@NonNull RoadCrewNotification notification) {
		notificationPromptVisible = true;
		dismissActiveNotificationDialog();
		LinearLayout content = RoadCrewUi.createPanel(mapActivity,
				notification.getTitle().isEmpty() ? mapActivity.getString(R.string.roadcrew_push_help_chat_title) : notification.getTitle());
		RoadCrewUi.addBody(mapActivity, content, notification.getBody());
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_later), false, v -> dialog.dismiss());
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_open_chat), true, v -> {
			acknowledgeNotification(mapActivity, notification.getId());
			dialog.dismiss();
			joinAndOpenHelpChat(mapActivity, notification.getReportId());
		});
		setActiveNotificationDialog(dialog);
		dialog.show();
	}

	private void openPushReference(@NonNull MapActivity mapActivity, @NonNull String kind,
			@NonNull String referenceId) {
		dismissActiveNotificationDialog();
		if ("HELP_NEARBY".equals(kind) || "HELP_CHAT_MESSAGE".equals(kind)) {
			joinAndOpenHelpChat(mapActivity, referenceId);
		} else if ("DIRECT_CHAT_MESSAGE".equals(kind)) {
			showDirectChatDialog(mapActivity, referenceId);
		} else if ("PLATE_SAFETY_ALERT".equals(kind)) {
			openPlateAlertChat(mapActivity, referenceId);
		}
	}

	private void acknowledgeNotification(@NonNull MapActivity mapActivity, @NonNull String id) {
		RoadCrewNotificationInbox.markRead(mapActivity, id);
		RoadCrewNeonHud.apply(mapActivity);
	}

	private void showGenericNotificationDialog(@NonNull MapActivity mapActivity,
			@NonNull RoadCrewNotification notification) {
		notificationPromptVisible = true;
		dismissActiveNotificationDialog();
		LinearLayout content = RoadCrewUi.createPanel(mapActivity,
				notification.getTitle().isEmpty() ? mapActivity.getString(R.string.roadcrew_inbox_notification)
						: notification.getTitle());
		RoadCrewUi.addBody(mapActivity, content, notification.getBody());
		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		RoadCrewUi.addFullWidthButton(mapActivity, content, mapActivity.getString(R.string.shared_string_close),
				false, v -> dialog.dismiss());
		setActiveNotificationDialog(dialog);
		dialog.show();
	}

	private void setActiveNotificationDialog(@NonNull AlertDialog dialog) {
		activeNotificationDialog = dialog;
		dialog.setOnDismissListener(d -> {
			if (activeNotificationDialog == dialog) {
				activeNotificationDialog = null;
			}
			notificationPromptVisible = false;
		});
	}

	private void dismissActiveNotificationDialog() {
		AlertDialog dialog = activeNotificationDialog;
		activeNotificationDialog = null;
		notificationPromptVisible = false;
		if (dialog != null && dialog.isShowing()) {
			dialog.dismiss();
		}
	}

	private void openPlateAlertChat(@NonNull MapActivity mapActivity, @NonNull String plateAlertId) {
		RoadCrewReportsSync.openPlateAlertChat(getApplication(), plateAlertId, new RoadCrewReportsSync.HelpChatCallback() {
			@Override
			public void onSuccess(@NonNull String chatRoomId) {
				showDirectChatDialog(mapActivity, chatRoomId);
			}

			@Override
			public void onError(@NonNull Exception error) {
				getApplication().showToastMessage(R.string.roadcrew_driver_chat_open_failed);
			}
		});
	}

	private void joinAndOpenHelpChat(@NonNull MapActivity mapActivity, @NonNull String reportId) {
		RoadCrewReportsSync.joinHelpChat(getApplication(), reportId, new RoadCrewReportsSync.HelpChatCallback() {
			@Override
			public void onSuccess(@NonNull String chatRoomId) {
				showHelpChatDialog(mapActivity, reportId);
			}

			@Override
			public void onError(@NonNull Exception error) {
				getApplication().showToastMessage(R.string.roadcrew_help_chat_open_failed);
			}
		});
	}

	private void openHelpChatFromReport(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report) {
		if (isRemoteReport(report)) {
			joinAndOpenHelpChat(mapActivity, report.getId());
		} else {
			if (report.getId().startsWith("seed-")) {
				getApplication().showToastMessage(R.string.roadcrew_help_chat_demo_unavailable);
				return;
			}
			getApplication().showToastMessage(R.string.roadcrew_help_chat_syncing);
			RoadCrewReportsSync.syncHelpReportAndJoinChat(getApplication(), report,
					new RoadCrewReportsSync.HelpReportChatCallback() {
						@Override
						public void onSuccess(@NonNull String reportId, @NonNull String chatRoomId) {
							getMapView().refreshMap();
							showHelpChatDialog(mapActivity, reportId);
						}

						@Override
						public void onError(@NonNull Exception error) {
							getApplication().showToastMessage(R.string.roadcrew_help_chat_open_failed);
						}
					});
		}
	}

	private void showHelpChatDialog(@NonNull MapActivity mapActivity, @NonNull String reportId) {
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_help_chat_title));

		TextView messagesView = new TextView(mapActivity);
		messagesView.setText(mapActivity.getString(R.string.roadcrew_chat_loading));
		RoadCrewUi.addMessageArea(mapActivity, content, messagesView, 240);

		EditText input = createHelpChatInput(mapActivity);
		RoadCrewUi.addInput(mapActivity, content, input);

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
		Button sendButton = RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_send), true,
				v -> sendHelpChatMessage(input, messagesView, reportId, null));
		sendButton.setOnClickListener(v -> sendHelpChatMessage(input, messagesView, reportId, sendButton));
		openHelpReportIds.add(reportId);
		dialog.setOnDismissListener(d -> openHelpReportIds.remove(reportId));
		dialog.show();
		fetchAndRenderHelpChatMessages(reportId, messagesView);
		scheduleHelpChatRefresh(reportId, messagesView, dialog);
	}

	private void showDirectChatDialog(@NonNull MapActivity mapActivity, @NonNull String chatRoomId) {
		if (chatRoomId.isEmpty()) {
			getApplication().showToastMessage(R.string.roadcrew_driver_chat_open_failed);
			return;
		}
		LinearLayout content = RoadCrewUi.createPanel(mapActivity, mapActivity.getString(R.string.roadcrew_driver_chat_title));

		TextView messagesView = new TextView(mapActivity);
		messagesView.setText(mapActivity.getString(R.string.roadcrew_chat_loading));
		RoadCrewUi.addMessageArea(mapActivity, content, messagesView, 240);

		EditText input = createHelpChatInput(mapActivity);
		RoadCrewUi.addInput(mapActivity, content, input);

		AlertDialog dialog = RoadCrewUi.createDialog(mapActivity, content);
		LinearLayout buttons = RoadCrewUi.addButtonRow(mapActivity, content);
		RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_close), false, v -> dialog.dismiss());
		Button sendButton = RoadCrewUi.addButton(mapActivity, buttons, mapActivity.getString(R.string.roadcrew_button_send), true,
				v -> sendDirectChatMessage(input, messagesView, chatRoomId, null));
		sendButton.setOnClickListener(v -> sendDirectChatMessage(input, messagesView, chatRoomId, sendButton));
		openDirectChatRoomIds.add(chatRoomId);
		dialog.setOnDismissListener(d -> openDirectChatRoomIds.remove(chatRoomId));
		dialog.show();
		fetchAndRenderDirectChatMessages(chatRoomId, messagesView);
		scheduleDirectChatRefresh(chatRoomId, messagesView, dialog);
	}

	@NonNull
	private EditText createHelpChatInput(@NonNull MapActivity mapActivity) {
		EditText input = RoadCrewUi.createInput(mapActivity, mapActivity.getString(R.string.roadcrew_chat_input_hint), true);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE);
		input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(HELP_CHAT_MESSAGE_MAX_LENGTH)});
		return input;
	}

	private void prepareHelpPanelChat(@NonNull MapActivity mapActivity, @NonNull RoadCrewReport report,
			@NonNull TextView messagesView, @NonNull Button sendButton, @NonNull AlertDialog dialog) {
		if (report.getId().startsWith("seed-")) {
			messagesView.setText(getContext().getString(R.string.roadcrew_help_chat_demo_unavailable));
			return;
		}
		if (isRemoteReport(report)) {
			joinHelpChatForPanel(report.getId(), messagesView, sendButton, dialog);
		} else {
			messagesView.setText(getContext().getString(R.string.roadcrew_help_chat_syncing));
			RoadCrewReportsSync.syncHelpReportAndJoinChat(getApplication(), report,
					new RoadCrewReportsSync.HelpReportChatCallback() {
						@Override
						public void onSuccess(@NonNull String reportId, @NonNull String chatRoomId) {
							getMapView().refreshMap();
							joinHelpChatForPanel(reportId, messagesView, sendButton, dialog);
						}

						@Override
						public void onError(@NonNull Exception error) {
							messagesView.setText(getContext().getString(R.string.roadcrew_help_chat_open_failed));
						}
					});
		}
	}

	private void joinHelpChatForPanel(@NonNull String reportId, @NonNull TextView messagesView,
			@NonNull Button sendButton, @NonNull AlertDialog dialog) {
		RoadCrewReportsSync.joinHelpChat(getApplication(), reportId, new RoadCrewReportsSync.HelpChatCallback() {
			@Override
			public void onSuccess(@NonNull String chatRoomId) {
				messagesView.setTag(reportId);
				openHelpReportIds.add(reportId);
				dialog.setOnDismissListener(d -> openHelpReportIds.remove(reportId));
				sendButton.setEnabled(true);
				fetchAndRenderHelpChatMessages(reportId, messagesView);
				scheduleHelpChatRefresh(reportId, messagesView, dialog);
			}

			@Override
			public void onError(@NonNull Exception error) {
				messagesView.setText(getContext().getString(R.string.roadcrew_help_chat_open_failed));
			}
		});
	}

	private void sendHelpChatMessage(@NonNull EditText input, @NonNull TextView messagesView,
			@NonNull RoadCrewReport report) {
		Object tag = messagesView.getTag();
		if (tag instanceof String reportId) {
			sendHelpChatMessage(input, messagesView, reportId, null);
		} else if (!report.getId().startsWith("seed-")) {
			getApplication().showToastMessage(R.string.roadcrew_help_chat_connecting);
		}
	}

	private void sendHelpChatMessage(@NonNull EditText input, @NonNull TextView messagesView,
			@NonNull String reportId, @Nullable Button sendButton) {
		String message = input.getText().toString().trim();
		if (message.isEmpty()) {
			return;
		}
		if (sendButton != null) {
			sendButton.setEnabled(false);
		}
		RoadCrewReportsSync.sendHelpChatMessage(getApplication(), reportId, message,
				new RoadCrewReportsSync.HelpChatCallback() {
					@Override
					public void onSuccess(@NonNull String chatRoomId) {
						input.setText("");
						if (sendButton != null) {
							sendButton.setEnabled(true);
						}
						fetchAndRenderHelpChatMessages(reportId, messagesView);
					}

					@Override
					public void onError(@NonNull Exception error) {
						if (sendButton != null) {
							sendButton.setEnabled(true);
						}
						getApplication().showToastMessage(R.string.roadcrew_chat_message_not_sent);
					}
				});
	}

	private void fetchAndRenderHelpChatMessages(@NonNull String reportId, @NonNull TextView messagesView) {
		RoadCrewReportsSync.fetchHelpChatMessages(getApplication(), reportId,
				new RoadCrewReportsSync.HelpChatMessagesCallback() {
					@Override
					public void onMessages(@NonNull List<RoadCrewChatMessage> messages) {
						messagesView.setText(formatHelpChatMessages(messages));
					}

					@Override
					public void onError(@NonNull Exception error) {
						messagesView.setText(getContext().getString(R.string.roadcrew_chat_load_failed));
					}
				});
	}

	private void sendDirectChatMessage(@NonNull EditText input, @NonNull TextView messagesView,
			@NonNull String chatRoomId, @Nullable Button sendButton) {
		String message = input.getText().toString().trim();
		if (message.isEmpty()) {
			return;
		}
		if (sendButton != null) {
			sendButton.setEnabled(false);
		}
		RoadCrewReportsSync.sendDirectChatMessage(getApplication(), chatRoomId, message,
				new RoadCrewReportsSync.HelpChatCallback() {
					@Override
					public void onSuccess(@NonNull String returnedChatRoomId) {
						input.setText("");
						if (sendButton != null) {
							sendButton.setEnabled(true);
						}
						fetchAndRenderDirectChatMessages(chatRoomId, messagesView);
					}

					@Override
					public void onError(@NonNull Exception error) {
						if (sendButton != null) {
							sendButton.setEnabled(true);
						}
						getApplication().showToastMessage(R.string.roadcrew_chat_message_not_sent);
					}
				});
	}

	private void fetchAndRenderDirectChatMessages(@NonNull String chatRoomId, @NonNull TextView messagesView) {
		RoadCrewReportsSync.fetchDirectChatMessages(getApplication(), chatRoomId,
				new RoadCrewReportsSync.HelpChatMessagesCallback() {
					@Override
					public void onMessages(@NonNull List<RoadCrewChatMessage> messages) {
						messagesView.setText(formatHelpChatMessages(messages));
					}

					@Override
					public void onError(@NonNull Exception error) {
						messagesView.setText(getContext().getString(R.string.roadcrew_chat_load_failed));
					}
				});
	}

	private void scheduleHelpChatRefresh(@NonNull String reportId, @NonNull TextView messagesView,
			@NonNull AlertDialog dialog) {
		getApplication().runInUIThread(() -> {
			if (!dialog.isShowing()) {
				return;
			}
			fetchAndRenderHelpChatMessages(reportId, messagesView);
			scheduleHelpChatRefresh(reportId, messagesView, dialog);
		}, HELP_CHAT_REFRESH_INTERVAL_MILLIS);
	}

	private void scheduleDirectChatRefresh(@NonNull String chatRoomId, @NonNull TextView messagesView,
			@NonNull AlertDialog dialog) {
		getApplication().runInUIThread(() -> {
			if (!dialog.isShowing()) {
				return;
			}
			fetchAndRenderDirectChatMessages(chatRoomId, messagesView);
			scheduleDirectChatRefresh(chatRoomId, messagesView, dialog);
		}, HELP_CHAT_REFRESH_INTERVAL_MILLIS);
	}

	@NonNull
	private String formatHelpChatMessages(@NonNull List<RoadCrewChatMessage> messages) {
		if (messages.isEmpty()) {
			return getContext().getString(R.string.roadcrew_chat_no_messages);
		}
		String localDeviceId = RoadCrewReportsRepository.getLocalDeviceId(getApplication());
		String localDisplayName = RoadCrewDriverProfile.load(getApplication()).getDisplayName();
		StringBuilder builder = new StringBuilder();
		for (RoadCrewChatMessage message : messages) {
			String author = formatChatAuthor(message, localDeviceId, localDisplayName);
			builder.append(author)
					.append(" - ")
					.append(formatMessageAge(message.getCreatedAtMillis()))
					.append('\n')
					.append(message.getBody())
					.append("\n\n");
		}
		return builder.toString().trim();
	}

	@NonNull
	private String formatChatAuthor(@NonNull RoadCrewChatMessage message, @NonNull String localDeviceId,
			@NonNull String localDisplayName) {
		String displayName = message.getDisplayName().trim();
		if (localDeviceId.equals(message.getDeviceId())) {
			return localDisplayName.isEmpty()
					? getContext().getString(R.string.roadcrew_chat_author_me)
					: getContext().getString(R.string.roadcrew_chat_author_me_named, localDisplayName);
		}
		return displayName.isEmpty() ? getContext().getString(R.string.roadcrew_chat_author_driver) : displayName;
	}

	private boolean isRemoteReport(@NonNull RoadCrewReport report) {
		return !report.getId().startsWith("local-") && !report.getId().startsWith("seed-");
	}

	private float dp(float value) {
		return value * getContext().getResources().getDisplayMetrics().density * getTextScale();
	}

	private float sp(float value) {
		return value * getContext().getResources().getDisplayMetrics().scaledDensity * getTextScale();
	}

	private String formatAge(@NonNull RoadCrewReport report) {
		long ageMillis = Math.max(0, System.currentTimeMillis() - report.getCreatedAtMillis());
		long ageMinutes = ageMillis / (60 * 1000);
		if (ageMinutes == 0) {
			return getContext().getString(R.string.roadcrew_time_now);
		}
		return getContext().getString(R.string.roadcrew_time_minutes, ageMinutes);
	}

	private String formatReportedAge(@NonNull RoadCrewReport report) {
		long ageMillis = Math.max(0, System.currentTimeMillis() - report.getCreatedAtMillis());
		long ageMinutes = ageMillis / (60 * 1000);
		if (ageMinutes == 0) {
			return getContext().getString(R.string.roadcrew_time_just_now);
		}
		return getContext().getString(R.string.roadcrew_time_minutes_ago, ageMinutes);
	}

	private String formatMessageAge(long createdAtMillis) {
		long ageMillis = Math.max(0, System.currentTimeMillis() - createdAtMillis);
		long ageMinutes = ageMillis / (60 * 1000);
		if (ageMinutes == 0) {
			return getContext().getString(R.string.roadcrew_time_now);
		}
		return getContext().getString(R.string.roadcrew_time_minutes_ago, ageMinutes);
	}

	@NonNull
	private String formatLocalVote(@NonNull RoadCrewReport report) {
		switch (report.getLocalVote()) {
			case CONFIRMED:
				return getContext().getString(R.string.roadcrew_report_still_there);
			case DENIED:
				return getContext().getString(R.string.roadcrew_report_gone);
			case NONE:
			default:
				return getContext().getString(R.string.roadcrew_report_vote_not_yet);
		}
	}

	private static final class LabelLayout {
		@NonNull
		private final String label;
		private final float centerX;
		private final float textBaseline;

		private LabelLayout(@NonNull String label, float centerX, float textBaseline) {
			this.label = label;
			this.centerX = centerX;
			this.textBaseline = textBaseline;
		}
	}
}
