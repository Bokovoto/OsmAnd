package net.osmand.plus.roadcrew;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;

import java.util.Collections;
import java.util.List;

public class RoadCrewPlace {

	private final String id;
	private final String kind;
	private final String name;
	private final LatLon location;
	private final String sourceType;
	private final String sourceId;
	private final String latestCategory;
	private final String latestBody;
	private final long latestMessageAt;
	private final long latestExpiresAt;
	private final int activeMessageCount;
	private final int reviewCount;
	private final double averageRating;
	private final double distanceKm;

	public RoadCrewPlace(@NonNull String id, @NonNull String kind, @NonNull String name,
			@NonNull LatLon location, @NonNull String sourceType, @NonNull String sourceId,
			@NonNull String latestCategory, @NonNull String latestBody, long latestMessageAt,
			long latestExpiresAt, int activeMessageCount, int reviewCount, double averageRating,
			double distanceKm) {
		this.id = id;
		this.kind = kind;
		this.name = name;
		this.location = location;
		this.sourceType = sourceType;
		this.sourceId = sourceId;
		this.latestCategory = latestCategory;
		this.latestBody = latestBody;
		this.latestMessageAt = latestMessageAt;
		this.latestExpiresAt = latestExpiresAt;
		this.activeMessageCount = activeMessageCount;
		this.reviewCount = reviewCount;
		this.averageRating = averageRating;
		this.distanceKm = distanceKm;
	}

	@NonNull public String getId() { return id; }
	@NonNull public String getKind() { return kind; }
	@NonNull public String getName() { return name; }
	@NonNull public LatLon getLocation() { return location; }
	@NonNull public String getSourceType() { return sourceType; }
	@NonNull public String getSourceId() { return sourceId; }
	@NonNull public String getLatestCategory() { return latestCategory; }
	@NonNull public String getLatestBody() { return latestBody; }
	public long getLatestMessageAt() { return latestMessageAt; }
	public long getLatestExpiresAt() { return latestExpiresAt; }
	public int getActiveMessageCount() { return activeMessageCount; }
	public int getReviewCount() { return reviewCount; }
	public double getAverageRating() { return averageRating; }
	public double getDistanceKm() { return distanceKm; }

	public static class Details {
		@NonNull public final RoadCrewPlace place;
		@NonNull public final List<Message> messages;
		@NonNull public final List<Review> reviews;
		public final int ratingCount;
		public final double averageRating;
		public final double securityRating;
		public final double quietRating;
		public final double accessRating;
		public final double facilitiesRating;
		public final int theftReports;

		public Details(@NonNull RoadCrewPlace place, @NonNull List<Message> messages,
				@NonNull List<Review> reviews, int ratingCount, double averageRating,
				double securityRating, double quietRating, double accessRating,
				double facilitiesRating, int theftReports) {
			this.place = place;
			this.messages = Collections.unmodifiableList(messages);
			this.reviews = Collections.unmodifiableList(reviews);
			this.ratingCount = ratingCount;
			this.averageRating = averageRating;
			this.securityRating = securityRating;
			this.quietRating = quietRating;
			this.accessRating = accessRating;
			this.facilitiesRating = facilitiesRating;
			this.theftReports = theftReports;
		}
	}

	public static class Message {
		@NonNull public final String id;
		@NonNull public final String displayName;
		@NonNull public final String category;
		@NonNull public final String body;
		public final long createdAt;
		public final long expiresAt;
		public final boolean verifiedVisit;
		public final int stillValidCount;
		public final int outdatedCount;
		@NonNull public final String localVote;

		public Message(@NonNull String id, @NonNull String displayName, @NonNull String category,
				@NonNull String body, long createdAt, long expiresAt, boolean verifiedVisit,
				int stillValidCount, int outdatedCount, @NonNull String localVote) {
			this.id = id;
			this.displayName = displayName;
			this.category = category;
			this.body = body;
			this.createdAt = createdAt;
			this.expiresAt = expiresAt;
			this.verifiedVisit = verifiedVisit;
			this.stillValidCount = stillValidCount;
			this.outdatedCount = outdatedCount;
			this.localVote = localVote;
		}
	}

	public static class Review {
		@NonNull public final String displayName;
		public final int securityScore;
		public final int quietScore;
		public final int accessScore;
		public final int facilitiesScore;
		public final boolean theftReported;
		@NonNull public final String body;
		public final boolean verifiedVisit;
		public final long updatedAt;

		public Review(@NonNull String displayName, int securityScore, int quietScore,
				int accessScore, int facilitiesScore, boolean theftReported,
				@NonNull String body, boolean verifiedVisit, long updatedAt) {
			this.displayName = displayName;
			this.securityScore = securityScore;
			this.quietScore = quietScore;
			this.accessScore = accessScore;
			this.facilitiesScore = facilitiesScore;
			this.theftReported = theftReported;
			this.body = body;
			this.verifiedVisit = verifiedVisit;
			this.updatedAt = updatedAt;
		}
	}
}
