package net.osmand.plus.roadcrew;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;

import java.util.UUID;

public class RoadCrewReport {

	private static final int MIN_DENIED_COUNT_TO_HIDE = 3;
	private static final long HELP_PROBABLY_RESOLVED_TTL_MILLIS = 45 * 60 * 1000;

	private final String id;
	private final RoadCrewReportType type;
	private final LatLon location;
	private final long createdAtMillis;
	private final long expiresAtMillis;
	private final String createdBy;
	private final String details;
	private final RoadCrewReportDirection direction;
	private final float directionBearing;
	private final RoadCrewReportSyncState syncState;
	private final int confirmedCount;
	private final int deniedCount;
	private final RoadCrewReportLocalVote localVote;
	private final long probablyResolvedAtMillis;

	public RoadCrewReport(@NonNull String id, @NonNull RoadCrewReportType type, @NonNull LatLon location,
			long createdAtMillis, long expiresAtMillis, @NonNull String createdBy,
			@NonNull String details, @NonNull RoadCrewReportSyncState syncState,
			int confirmedCount, int deniedCount, @NonNull RoadCrewReportLocalVote localVote,
			long probablyResolvedAtMillis) {
		this(id, type, location, createdAtMillis, expiresAtMillis, createdBy, details,
				RoadCrewReportDirection.UNKNOWN, Float.NaN, syncState, confirmedCount, deniedCount,
				localVote, probablyResolvedAtMillis);
	}

	public RoadCrewReport(@NonNull String id, @NonNull RoadCrewReportType type, @NonNull LatLon location,
			long createdAtMillis, long expiresAtMillis, @NonNull String createdBy,
			@NonNull String details, @NonNull RoadCrewReportDirection direction, float directionBearing,
			@NonNull RoadCrewReportSyncState syncState, int confirmedCount, int deniedCount,
			@NonNull RoadCrewReportLocalVote localVote, long probablyResolvedAtMillis) {
		this.id = id;
		this.type = type;
		this.location = location;
		this.createdAtMillis = createdAtMillis;
		this.expiresAtMillis = expiresAtMillis;
		this.createdBy = createdBy;
		this.details = details;
		this.direction = direction;
		this.directionBearing = normalizeBearing(directionBearing);
		this.syncState = syncState;
		this.confirmedCount = confirmedCount;
		this.deniedCount = deniedCount;
		this.localVote = localVote;
		this.probablyResolvedAtMillis = probablyResolvedAtMillis;
	}

	@NonNull
	public static RoadCrewReport createLocal(@NonNull RoadCrewReportType type, @NonNull LatLon location,
			long createdAtMillis, @NonNull String localDeviceId) {
		return createLocal(type, location, createdAtMillis, localDeviceId, "");
	}

	@NonNull
	public static RoadCrewReport createLocal(@NonNull RoadCrewReportType type, @NonNull LatLon location,
			long createdAtMillis, @NonNull String localDeviceId, @NonNull String details) {
		return createLocal(type, location, createdAtMillis, localDeviceId, details,
				RoadCrewReportDirection.UNKNOWN, Float.NaN);
	}

	@NonNull
	public static RoadCrewReport createLocal(@NonNull RoadCrewReportType type, @NonNull LatLon location,
			long createdAtMillis, @NonNull String localDeviceId, @NonNull String details,
			@NonNull RoadCrewReportDirection direction, float directionBearing) {
		return new RoadCrewReport(
				"local-" + UUID.randomUUID(),
				type,
				location,
				createdAtMillis,
				createdAtMillis + type.getDefaultLifetimeMillis(),
				localDeviceId,
				details,
				direction,
				directionBearing,
				RoadCrewReportSyncState.PENDING_CREATE,
				0,
				0,
				RoadCrewReportLocalVote.NONE,
				0
		);
	}

	@NonNull
	public String getId() {
		return id;
	}

	@NonNull
	public RoadCrewReportType getType() {
		return type;
	}

	@NonNull
	public LatLon getLocation() {
		return location;
	}

	public long getCreatedAtMillis() {
		return createdAtMillis;
	}

	public long getExpiresAtMillis() {
		return expiresAtMillis;
	}

	@NonNull
	public String getCreatedBy() {
		return createdBy;
	}

	@NonNull
	public String getDetails() {
		return details;
	}

	@NonNull
	public RoadCrewReportDirection getDirection() {
		return direction;
	}

	public float getDirectionBearing() {
		return directionBearing;
	}

	public boolean hasDirectionBearing() {
		return Float.isFinite(directionBearing);
	}

	public boolean appliesToBearing(float bearing) {
		if (direction != RoadCrewReportDirection.ONE_DIRECTION || !hasDirectionBearing()
				|| !Float.isFinite(bearing)) {
			return true;
		}
		float difference = Math.abs(normalizeSignedDegrees(bearing - directionBearing));
		return difference <= 90;
	}

	@NonNull
	public RoadCrewReportSyncState getSyncState() {
		return syncState;
	}

	public int getConfirmedCount() {
		return confirmedCount;
	}

	public int getDeniedCount() {
		return deniedCount;
	}

	@NonNull
	public RoadCrewReportLocalVote getLocalVote() {
		return localVote;
	}

	public long getProbablyResolvedAtMillis() {
		return probablyResolvedAtMillis;
	}

	public boolean hasLocalVote() {
		return localVote != RoadCrewReportLocalVote.NONE;
	}

	@NonNull
	public RoadCrewReport withVote(boolean confirmed) {
		if (hasLocalVote()) {
			return this;
		}
		return new RoadCrewReport(
				id,
				type,
				location,
				createdAtMillis,
				expiresAtMillis,
				createdBy,
				details,
				direction,
				directionBearing,
				syncState == RoadCrewReportSyncState.PENDING_CREATE
						? RoadCrewReportSyncState.PENDING_CREATE
						: RoadCrewReportSyncState.PENDING_UPDATE,
				confirmed ? confirmedCount + 1 : confirmedCount,
				confirmed ? deniedCount : deniedCount + 1,
				confirmed ? RoadCrewReportLocalVote.CONFIRMED : RoadCrewReportLocalVote.DENIED,
				type == RoadCrewReportType.HELP
						? (confirmed ? 0 : System.currentTimeMillis())
						: probablyResolvedAtMillis
		);
	}

	@NonNull
	public RoadCrewReport withSynced(@NonNull String syncedId, long syncedExpiresAtMillis) {
		return new RoadCrewReport(
				syncedId,
				type,
				location,
				createdAtMillis,
				syncedExpiresAtMillis,
				createdBy,
				details,
				direction,
				directionBearing,
				RoadCrewReportSyncState.SYNCED,
				confirmedCount,
				deniedCount,
				localVote,
				probablyResolvedAtMillis
		);
	}

	@NonNull
	public RoadCrewReport withLocalVote(@NonNull RoadCrewReportLocalVote localVote) {
		return new RoadCrewReport(
				id,
				type,
				location,
				createdAtMillis,
				expiresAtMillis,
				createdBy,
				details,
				direction,
				directionBearing,
				syncState,
				confirmedCount,
				deniedCount,
				localVote,
				probablyResolvedAtMillis
		);
	}

	@NonNull
	public RoadCrewReport withProbablyResolvedAt(long probablyResolvedAtMillis) {
		return new RoadCrewReport(
				id,
				type,
				location,
				createdAtMillis,
				expiresAtMillis,
				createdBy,
				details,
				direction,
				directionBearing,
				syncState,
				confirmedCount,
				deniedCount,
				localVote,
				probablyResolvedAtMillis
		);
	}

	public boolean isExpired(long now) {
		if (isHelpProbablyResolved() && now - probablyResolvedAtMillis >= HELP_PROBABLY_RESOLVED_TTL_MILLIS) {
			return true;
		}
		return now >= expiresAtMillis;
	}

	public boolean isHelpProbablyResolved() {
		return type == RoadCrewReportType.HELP && probablyResolvedAtMillis > 0;
	}

	public boolean shouldHideLocally() {
		return type != RoadCrewReportType.HELP
				&& deniedCount >= MIN_DENIED_COUNT_TO_HIDE
				&& deniedCount > confirmedCount;
	}

	private static float normalizeBearing(float bearing) {
		if (!Float.isFinite(bearing)) {
			return Float.NaN;
		}
		return (bearing % 360 + 360) % 360;
	}

	private static float normalizeSignedDegrees(float value) {
		return (value % 360 + 540) % 360 - 180;
	}
}
