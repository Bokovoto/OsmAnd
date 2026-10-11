package net.osmand.plus.roadcrew;

import android.content.Context;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import net.osmand.plus.R;

public enum RoadCrewReportType {
	DAI(R.string.roadcrew_report_type_traffic_police, "T", 0xff19a974),
	POLICE(R.string.roadcrew_report_type_police, "P", 0xff2563eb),
	CAMERA(R.string.roadcrew_report_type_camera, "C", 0xff7c3aed),
	WEIGH_STATION(R.string.roadcrew_report_type_weigh_station, "W", 0xff237bff),
	DANGER(R.string.roadcrew_report_type_danger, "!", 0xfff59e0b),
	HELP(R.string.roadcrew_report_type_help, "H", 0xffef4444);

	private final int titleResId;
	private final String shortLabel;
	private final int color;

	RoadCrewReportType(int titleResId, @NonNull String shortLabel, @ColorInt int color) {
		this.titleResId = titleResId;
		this.shortLabel = shortLabel;
		this.color = color;
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
}
