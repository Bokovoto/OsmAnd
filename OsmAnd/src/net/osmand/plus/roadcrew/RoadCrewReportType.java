package net.osmand.plus.roadcrew;

import android.content.Context;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import net.osmand.plus.R;

public enum RoadCrewReportType {
	DAI(R.string.roadcrew_report_type_traffic_police, "T", 0xff19a974, 60),
	POLICE(R.string.roadcrew_report_type_police, "P", 0xff2563eb, 60),
	CAMERA(R.string.roadcrew_report_type_camera, "C", 0xff7c3aed, 24 * 60),
	WEIGH_STATION(R.string.roadcrew_report_type_weigh_station, "W", 0xff237bff, 6 * 60),
	DANGER(R.string.roadcrew_report_type_danger, "!", 0xfff59e0b, 2 * 60),
	HELP(R.string.roadcrew_report_type_help, "H", 0xffef4444, 4 * 60);

	private final int titleResId;
	private final String shortLabel;
	private final int color;
	private final long defaultLifetimeMillis;

	RoadCrewReportType(int titleResId, @NonNull String shortLabel, @ColorInt int color,
			long defaultLifetimeMinutes) {
		this.titleResId = titleResId;
		this.shortLabel = shortLabel;
		this.color = color;
		this.defaultLifetimeMillis = defaultLifetimeMinutes * 60 * 1000;
	}

	@NonNull
	public String getTitle(@NonNull Context context) {
		return context.getString(titleResId);
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
