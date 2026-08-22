package net.osmand.router;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import net.osmand.osm.edit.Node;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Immutable, restrictive-only runtime routing overlay used by the RoadCrew proof of concept.
 */
public final class RoadCrewRoutingOverlay {

	public static final int SCHEMA_VERSION = 1;
	public static final Snapshot EMPTY = new Snapshot("empty", 0, Collections.emptyList(), 0);

	private RoadCrewRoutingOverlay() {
	}

	public static Snapshot parse(Reader reader, long now) throws JsonParseException {
		OverlayJson json = new Gson().fromJson(reader, OverlayJson.class);
		if (json == null || json.schemaVersion != SCHEMA_VERSION) {
			throw new JsonParseException("Unsupported RoadCrew routing overlay schema");
		}
		List<Override> accepted = new ArrayList<>();
		int rejected = 0;
		if (json.overrides != null) {
			for (OverrideJson item : json.overrides) {
				Override override = toValidatedOverride(item, now);
				if (override != null) {
					accepted.add(override);
				} else {
					rejected++;
				}
			}
		}
		String revision = json.revision == null || json.revision.trim().isEmpty()
				? "local-unknown" : json.revision.trim();
		return new Snapshot(revision, json.generatedAt, accepted, rejected);
	}

	private static Override toValidatedOverride(OverrideJson item, long now) {
		if (item == null || !item.validated || item.id == null || item.id.trim().isEmpty() || item.operation == null) {
			return null;
		}
		if (item.validFrom > 0 && now < item.validFrom || item.validUntil > 0 && now >= item.validUntil) {
			return null;
		}
		Operation operation;
		Direction direction;
		try {
			operation = Operation.valueOf(item.operation.trim().toUpperCase(Locale.US));
			direction = item.direction == null ? Direction.BOTH
					: Direction.valueOf(item.direction.trim().toUpperCase(Locale.US));
		} catch (IllegalArgumentException e) {
			return null;
		}
		String profile = item.profile == null ? "truck" : item.profile.trim().toLowerCase(Locale.US);
		RoadCrewSegmentIdentity.SegmentKey segmentKey = null;
		if (item.segmentKey != null) {
			try {
				long osmWayId = Long.parseLong(item.segmentKey.osmWayId);
				segmentKey = RoadCrewSegmentIdentity.key(item.segmentKey.version, osmWayId,
						item.segmentKey.region, item.segmentKey.fromLatitude, item.segmentKey.fromLongitude,
						item.segmentKey.toLatitude, item.segmentKey.toLongitude,
						item.segmentKey.geometryFingerprint, item.segmentKey.lengthMeters);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		if (operation == Operation.BLOCK_ROAD) {
			long roadId;
			try {
				roadId = Long.parseLong(item.roadId);
			} catch (NumberFormatException e) {
				return null;
			}
			if (roadId <= 0 || direction != Direction.BOTH) {
				return null;
			}
			item.parsedRoadId = roadId;
		} else if (operation == Operation.SET_MAXHEIGHT) {
			if (!isValidCoordinate(item.latitude, item.longitude) || item.value <= 0) {
				return null;
			}
		} else {
			return null;
		}
		Double angle = item.directionAngle;
		if (angle != null && (!Double.isFinite(angle) || angle < 0 || angle >= 360)) {
			return null;
		}
		return new Override(item.id.trim(), operation, direction, profile, item.parsedRoadId, segmentKey,
				item.latitude, item.longitude, item.value, angle, item.validFrom, item.validUntil);
	}

	private static boolean isValidCoordinate(double latitude, double longitude) {
		return Double.isFinite(latitude) && Double.isFinite(longitude)
				&& latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
	}

	public enum Operation {
		BLOCK_ROAD,
		SET_MAXHEIGHT
	}

	public enum Direction {
		BOTH,
		FORWARD,
		BACKWARD
	}

	public static final class Snapshot {
		private final String revision;
		private final long generatedAt;
		private final List<Override> overrides;
		private final int rejectedCount;

		private Snapshot(String revision, long generatedAt, List<Override> overrides, int rejectedCount) {
			this.revision = revision;
			this.generatedAt = generatedAt;
			this.overrides = Collections.unmodifiableList(new ArrayList<>(overrides));
			this.rejectedCount = rejectedCount;
		}

		public Snapshot forProfile(String profile) {
			if (profile == null) {
				return EMPTY;
			}
			String normalized = profile.toLowerCase(Locale.US);
			List<Override> selected = new ArrayList<>();
			for (Override override : overrides) {
				if (normalized.equals(override.profile)) {
					selected.add(override);
				}
			}
			return selected.isEmpty() ? EMPTY : new Snapshot(revision, generatedAt, selected, rejectedCount);
		}

		public int applyTo(RoutingConfiguration configuration) {
			if (configuration == null || overrides.isEmpty()) {
				return 0;
			}
			for (Override override : overrides) {
				if (override.operation == Operation.BLOCK_ROAD) {
					configuration.router.addImpassableRoad(override.roadId);
				} else if (override.operation == Operation.SET_MAXHEIGHT) {
					configuration.addDirectionPoint(override.toDirectionPoint());
				}
			}
			configuration.attributes.put("roadcrewOverlayRevision", revision);
			return overrides.size();
		}

		public boolean isEmpty() {
			return overrides.isEmpty();
		}

		public String getRevision() {
			return revision;
		}

		public long getGeneratedAt() {
			return generatedAt;
		}

		public List<Override> getOverrides() {
			return overrides;
		}

		public int getRejectedCount() {
			return rejectedCount;
		}
	}

	public static final class Override {
		private final String id;
		private final Operation operation;
		private final Direction direction;
		private final String profile;
		private final long roadId;
		private final RoadCrewSegmentIdentity.SegmentKey segmentKey;
		private final double latitude;
		private final double longitude;
		private final double value;
		private final Double directionAngle;
		private final long validFrom;
		private final long validUntil;

		private Override(String id, Operation operation, Direction direction, String profile, long roadId,
				RoadCrewSegmentIdentity.SegmentKey segmentKey,
				double latitude, double longitude, double value, Double directionAngle, long validFrom, long validUntil) {
			this.id = id;
			this.operation = operation;
			this.direction = direction;
			this.profile = profile;
			this.roadId = roadId;
			this.segmentKey = segmentKey;
			this.latitude = latitude;
			this.longitude = longitude;
			this.value = value;
			this.directionAngle = directionAngle;
			this.validFrom = validFrom;
			this.validUntil = validUntil;
		}

		private Node toDirectionPoint() {
			Node node = new Node(latitude, longitude, -Math.abs((long) id.hashCode()));
			String tag = direction == Direction.FORWARD ? "maxheight:forward"
					: direction == Direction.BACKWARD ? "maxheight:backward" : "maxheight";
			node.putTag(tag, Double.toString(value));
			if (directionAngle != null) {
				node.putTag(RoutingConfiguration.DirectionPoint.ANGLE_TAG, Double.toString(directionAngle));
			}
			return node;
		}

		public String getId() {
			return id;
		}

		public Operation getOperation() {
			return operation;
		}

		public Direction getDirection() {
			return direction;
		}

		public long getRoadId() {
			return roadId;
		}

		public RoadCrewSegmentIdentity.SegmentKey getSegmentKey() {
			return segmentKey;
		}

		public long getValidFrom() {
			return validFrom;
		}

		public long getValidUntil() {
			return validUntil;
		}
	}

	private static final class OverlayJson {
		int schemaVersion;
		String revision;
		long generatedAt;
		List<OverrideJson> overrides;
	}

	private static final class OverrideJson {
		String id;
		String operation;
		String direction;
		String profile;
		boolean validated;
		String roadId;
		transient long parsedRoadId;
		SegmentKeyJson segmentKey;
		double latitude;
		double longitude;
		double value;
		Double directionAngle;
		long validFrom;
		long validUntil;
	}

	private static final class SegmentKeyJson {
		int version;
		String osmWayId;
		String region;
		double fromLatitude;
		double fromLongitude;
		double toLatitude;
		double toLongitude;
		String geometryFingerprint;
		double lengthMeters;
	}
}
