package net.osmand.plus.roadcrew;

import androidx.annotation.NonNull;

public enum RoadCrewReportDirection {
	UNKNOWN,
	ONE_DIRECTION,
	BOTH_DIRECTIONS;

	@NonNull
	static RoadCrewReportDirection parse(@NonNull String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException error) {
			return UNKNOWN;
		}
	}
}
