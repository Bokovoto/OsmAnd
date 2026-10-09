package net.osmand.plus.roadcrew.tacho;

import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.below;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.card;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.color;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.dp;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.hm;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.text;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Day;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Place;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * One day of a downloaded card file, as Tacho Manager shows it (Galin, 09.10.2026, ROADMAP
 * 381): the shift, the totals, the day by the hour with the rest before and after, the places,
 * and every entry - by the phone's clock.
 */
public final class RoadCrewTachoDayActivity extends Activity {

	private static final String TAG = "RoadCrewTacho";
	private static final String EXTRA_DAY = "roadcrew_tacho_day";

	private LinearLayout content;

	static void open(@NonNull Context context, @Nullable Uri uri, long takenAt, @NonNull LocalDate day) {
		if (uri == null) {
			return;
		}
		Intent intent = new Intent(context, RoadCrewTachoDayActivity.class);
		intent.putExtra(RoadCrewTachoAnalysisActivity.EXTRA_URI, uri);
		intent.putExtra(RoadCrewTachoAnalysisActivity.EXTRA_TAKEN_AT, takenAt);
		intent.putExtra(EXTRA_DAY, day.toEpochDay());
		context.startActivity(intent);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Uri uri = getIntent().getParcelableExtra(RoadCrewTachoAnalysisActivity.EXTRA_URI);
		long takenAt = getIntent().getLongExtra(RoadCrewTachoAnalysisActivity.EXTRA_TAKEN_AT, 0);
		LocalDate date = LocalDate.ofEpochDay(getIntent().getLongExtra(EXTRA_DAY, 0));
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(color(this, R.color.roadcrew_tacho_ground));
		root.addView(RoadCrewTachoUi.topBar(this, RoadCrewTachoUi.longDay(date), getString(R.string.roadcrew_tacho_day_local)));
		View line = new View(this);
		line.setBackgroundColor(color(this, R.color.roadcrew_tacho_line));
		root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));
		ScrollView scroll = new ScrollView(this);
		content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(this, 16);
		content.setPadding(pad, pad, pad, pad);
		scroll.addView(content);
		root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
		setContentView(root);
		content.addView(text(this, getString(R.string.roadcrew_tacho_analysis_loading), 15, false,
				color(this, R.color.roadcrew_tacho_secondary)));
		new Thread(() -> {
			try {
				if (uri == null) {
					throw new java.io.IOException("no file");
				}
				Card card = RoadCrewTachoActivities.read(RoadCrewTachoUi.read(getContentResolver(), uri),
						RoadCrewTachoAnalysisActivity.downloadMinute(takenAt));
				runOnUiThread(() -> {
					if (!isFinishing()) {
						render(card, date);
					}
				});
			} catch (Exception e) {
				Log.w(TAG, "card file day failed: " + e.getMessage(), e);
				runOnUiThread(() -> {
					content.removeAllViews();
					content.addView(text(this, getString(R.string.roadcrew_tacho_analysis_failed, String.valueOf(e.getMessage())),
							15, false, color(this, R.color.roadcrew_tacho_err)));
				});
			}
		}, "roadcrew-tacho-day").start();
	}

	private void render(@NonNull Card card, @NonNull LocalDate date) {
		ZoneId zone = ZoneId.systemDefault();
		Day day = null;
		for (Day d : RoadCrewTachoActivities.days(card, zone)) {
			if (d.date.equals(date)) {
				day = d;
				break;
			}
		}
		content.removeAllViews();
		long a = date.atStartOfDay(zone).toEpochSecond() / 60;
		long b = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60;
		List<Segment> segments = day == null ? new ArrayList<>() : day.segments;
		List<Place> places = new ArrayList<>();
		for (Place p : card.places) {
			if (p.minute >= a && p.minute < b) {
				places.add(p);
			}
		}

		// The shift and the totals.
		LinearLayout head = card(this);
		LinearLayout top = new LinearLayout(this);
		top.setGravity(Gravity.CENTER_VERTICAL);
		boolean worked = day != null && day.start >= 0;
		top.addView(text(this, worked ? RoadCrewTachoUi.time(day.start) + " – " + RoadCrewTachoUi.time(day.end)
				: getString(R.string.roadcrew_tacho_day_rest_all), 26, true, color(this, R.color.roadcrew_tacho_text)),
				new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		if (worked) {
			top.addView(text(this, getString(R.string.roadcrew_tacho_day_shift, hm(day.end - day.start)), 15, true,
					color(this, R.color.roadcrew_tacho_secondary)));
		}
		head.addView(top);
		if (day != null) {
			LinearLayout grid = new LinearLayout(this);
			grid.setOrientation(LinearLayout.VERTICAL);
			int[][] totals = {
					{RoadCrewTachoUi.DRIVING, R.string.roadcrew_tacho_kind_driving, day.driving},
					{RoadCrewTachoUi.WORK, R.string.roadcrew_tacho_kind_work, day.work},
					{RoadCrewTachoUi.AVAILABLE, R.string.roadcrew_tacho_kind_available, day.available},
					{RoadCrewTachoUi.REST, R.string.roadcrew_tacho_kind_rest, day.rest},
					{RoadCrewTachoUi.UNKNOWN, R.string.roadcrew_tacho_kind_unknown, day.unknown},
			};
			LinearLayout pair = null;
			int shown = 0;
			for (int[] t : totals) {
				if (t[1] == R.string.roadcrew_tacho_kind_unknown && t[2] == 0) {
					continue;
				}
				if (shown % 2 == 0) {
					pair = new LinearLayout(this);
					grid.addView(pair, below(this, 6));
				}
				pair.addView(total(t[0], getString(t[1]), t[2], t[1] == R.string.roadcrew_tacho_kind_driving),
						new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
				shown++;
			}
			head.addView(grid, below(this, 8));
		}
		content.addView(head);

		// The day by the hour.
		LinearLayout hours = card(this);
		hours.addView(text(this, getString(R.string.roadcrew_tacho_day_hours), 17, true, color(this, R.color.roadcrew_tacho_text)));
		RoadCrewTachoUi.DayTimeline timeline = new RoadCrewTachoUi.DayTimeline(this);
		long start = worked ? day.start : -1;
		long end = worked ? day.end : -1;
		timeline.set(segments, places, a, b, start, end,
				worked ? RoadCrewTachoActivities.restBefore(card, start) : 0,
				worked ? RoadCrewTachoActivities.restAfter(card, end) : 0);
		hours.addView(timeline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 190)));
		StringBuilder where = new StringBuilder();
		for (Place p : places) {
			if (where.length() > 0) {
				where.append('\n');
			}
			where.append(getString(p.begin ? R.string.roadcrew_tacho_place_begin : R.string.roadcrew_tacho_place_end))
					.append(' ').append(RoadCrewTachoUi.time(p.minute)).append(" · ").append(RoadCrewTachoUi.country(p.country))
					.append(" · ").append(String.format(java.util.Locale.getDefault(), "%,d km", p.odometerKm));
		}
		if (where.length() > 0) {
			hours.addView(text(this, where, 13, false, color(this, R.color.roadcrew_tacho_secondary)), below(this, 8));
		}
		content.addView(hours, below(this, 12));

		// Every entry, the places among them.
		LinearLayout entries = card(this);
		entries.addView(text(this, getString(R.string.roadcrew_tacho_day_entries), 17, true, color(this, R.color.roadcrew_tacho_text)));
		int s = 0;
		int p = 0;
		while (s < segments.size() || p < places.size()) {
			boolean placeFirst = p < places.size() && (s >= segments.size() || places.get(p).minute <= segments.get(s).from);
			if (placeFirst) {
				Place place = places.get(p++);
				entries.addView(entry(RoadCrewTachoUi.PLACE,
						getString(place.begin ? R.string.roadcrew_tacho_place_begin : R.string.roadcrew_tacho_place_end) + " · "
								+ RoadCrewTachoUi.country(place.country), RoadCrewTachoUi.time(place.minute),
						String.format(java.util.Locale.getDefault(), "%,d km", place.odometerKm), false), below(this, 8));
			} else {
				Segment seg = segments.get(s++);
				entries.addView(entry(RoadCrewTachoUi.kindColor(seg), getString(kindName(seg)),
						RoadCrewTachoUi.time(seg.from) + " – " + (seg.to >= b ? "24:00" : RoadCrewTachoUi.time(seg.to)),
						hm(seg.to - seg.from), seg.known && seg.kind == Kind.DRIVING), below(this, 8));
			}
		}
		content.addView(entries, below(this, 12));
	}

	private static int kindName(@NonNull Segment s) {
		if (!s.known) {
			return R.string.roadcrew_tacho_kind_unknown;
		}
		switch (s.kind) {
			case DRIVING:
				return R.string.roadcrew_tacho_kind_driving;
			case WORK:
				return R.string.roadcrew_tacho_kind_work;
			case AVAILABLE:
				return R.string.roadcrew_tacho_kind_available;
			default:
				return R.string.roadcrew_tacho_kind_rest;
		}
	}

	private View dot(int colour) {
		View dot = new View(this);
		GradientDrawable d = new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(colour);
		dot.setBackground(d);
		return dot;
	}

	private View total(int colour, @NonNull String name, int minutes, boolean bold) {
		LinearLayout row = new LinearLayout(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(dot(colour), new LinearLayout.LayoutParams(dp(this, 12), dp(this, 12)));
		LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		tp.setMarginStart(dp(this, 8));
		row.addView(text(this, name + "  " + hm(minutes), 15, bold, color(this, R.color.roadcrew_tacho_text)), tp);
		return row;
	}

	private View entry(int colour, @NonNull String name, @NonNull String when, @NonNull String length, boolean bold) {
		LinearLayout row = new LinearLayout(this);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.addView(dot(colour), new LinearLayout.LayoutParams(dp(this, 12), dp(this, 12)));
		LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
		np.setMarginStart(dp(this, 10));
		row.addView(text(this, name, 15, bold, color(this, R.color.roadcrew_tacho_text)), np);
		TextView whenView = text(this, when, 14, false, color(this, R.color.roadcrew_tacho_text));
		row.addView(whenView, new LinearLayout.LayoutParams(dp(this, 112), LinearLayout.LayoutParams.WRAP_CONTENT));
		TextView lengthView = text(this, length, 14, true, color(this, R.color.roadcrew_tacho_text));
		lengthView.setGravity(Gravity.END);
		row.addView(lengthView, new LinearLayout.LayoutParams(dp(this, 72), LinearLayout.LayoutParams.WRAP_CONTENT));
		return row;
	}
}
