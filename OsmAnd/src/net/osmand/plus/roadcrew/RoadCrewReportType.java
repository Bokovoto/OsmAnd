package net.osmand.plus.roadcrew;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public enum RoadCrewReportType {
	DAI("Traffic police", "T", 0xff19a974, 60),
	POLICE("Police", "P", 0xff2563eb, 60),
	CAMERA("Camera", "C", 0xff7c3aed, 24 * 60),
	WEIGH_STATION("Weigh station", "W", 0xff237bff, 6 * 60),
	DANGER("Danger", "!", 0xfff59e0b, 2 * 60),
	HELP("Help", "H", 0xffef4444, 4 * 60);

	private final String title;
	private final String shortLabel;
	private final int color;
	private final long defaultLifetimeMillis;

	RoadCrewReportType(@NonNull String title, @NonNull String shortLabel, @ColorInt int color,
			long defaultLifetimeMinutes) {
		this.title = title;
		this.shortLabel = shortLabel;
		this.color = color;
		this.defaultLifetimeMillis = defaultLifetimeMinutes * 60 * 1000;
	}

	@NonNull
	public String getTitle() {
		return title;
	}

	@NonNull
	public String getShortLabel() {
		return shortLabel;
	}

	@ColorInt
	public int getColor() {
		return color;
	}

	public long getDefaultLifetimeMillis() {
		return defaultLifetimeMillis;
	}
}
