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
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Card;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoRemaining.Result;

import java.time.LocalDate;

/**
 * "Колко ми остава" (Galin, 11.10.2026, ROADMAP 408): from one card file - the newest
 * download, or a .DDD another app downloaded - what each limit of 561/2006 still leaves,
 * counted by RoadCrewTachoRemaining as of the download. Everything from the file on the phone.
 */
public final class RoadCrewTachoRemainingActivity extends Activity {

	private static final String TAG = "RoadCrewTacho";
	private static final int TRACK = 0xFFEDE9FE;

	private Uri uri;
	private long takenAt;
	private LinearLayout content;

	/** {@code takenAt}: the download, epoch millis, or 0 when the file does not say. */
	public static void open(@NonNull Context context, @NonNull Uri uri, long takenAt) {
		Intent intent = new Intent(context, RoadCrewTachoRemainingActivity.class);
		intent.putExtra(RoadCrewTachoAnalysisActivity.EXTRA_URI, uri);
		intent.putExtra(RoadCrewTachoAnalysisActivity.EXTRA_TAKEN_AT, takenAt);
		context.startActivity(intent);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		uri = getIntent().getParcelableExtra(RoadCrewTachoAnalysisActivity.EXTRA_URI);
		takenAt = getIntent().getLongExtra(RoadCrewTachoAnalysisActivity.EXTRA_TAKEN_AT, 0);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(color(this, R.color.roadcrew_tacho_ground));
		String subtitle = takenAt > 0
				? getString(R.string.roadcrew_tacho_remaining_subtitle, RoadCrewTachoUi.dayTime(takenAt / 60000)) : null;
		root.addView(RoadCrewTachoUi.topBar(this, getString(R.string.roadcrew_tacho_remaining_title), subtitle));
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
		content.addView(text(this, getString(R.string.roadcrew_tacho_remaining_loading), 15, false,
				color(this, R.color.roadcrew_tacho_secondary)));
		load();
	}

	private void load() {
		new Thread(() -> {
			try {
				if (uri == null) {
					throw new java.io.IOException("no file");
				}
				Card card = RoadCrewTachoActivities.read(RoadCrewTachoUi.read(getContentResolver(), uri), RoadCrewTachoAnalysisActivity.downloadMinute(takenAt));
				Result result = RoadCrewTachoRemaining.of(card);
				runOnUiThread(() -> {
					if (!isFinishing()) {
						render(result);
					}
				});
			} catch (Exception e) {
				Log.w(TAG, "how much is left failed: " + e.getMessage(), e);
				runOnUiThread(() -> {
					content.removeAllViews();
					content.addView(text(this, getString(R.string.roadcrew_tacho_remaining_failed, String.valueOf(e.getMessage())),
							15, false, color(this, R.color.roadcrew_tacho_err)));
				});
			}
		}, "roadcrew-tacho-remaining").start();
	}

	private void render(@Nullable Result r) {
		content.removeAllViews();
		int textColor = color(this, R.color.roadcrew_tacho_text);
		int secondary = color(this, R.color.roadcrew_tacho_secondary);
		if (r == null) {
			content.addView(text(this, getString(R.string.roadcrew_tacho_analysis_empty), 15, false, secondary));
			return;
		}
		if (r.restingSince >= 0) {
			content.addView(RoadCrewTachoUi.pill(this, getString(R.string.roadcrew_tacho_remaining_resting, when(r.restingSince)),
					RoadCrewTachoUi.REST, 0xFF000000), new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		}

		// Article 7: until the 45-minute break.
		LinearLayout hero = card(this);
		hero.addView(text(this, getString(R.string.roadcrew_tacho_remaining_break), 14, true, secondary));
		hero.addView(text(this, hm(r.untilBreak), 40, true, textColor));
		LinearLayout bar = new LinearLayout(this);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setBackground(RoadCrewTachoUi.shape(this, TRACK, 0, 5));
		View filled = new View(this);
		filled.setBackground(RoadCrewTachoUi.shape(this, RoadCrewTachoUi.DRIVING, 0, 5));
		int driven = Math.min(r.drivenSinceBreak, 270);
		bar.addView(filled, new LinearLayout.LayoutParams(0, dp(this, 10), Math.max(driven, 0)));
		bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(this, 10), Math.max(270 - driven, 0)));
		hero.addView(bar, below(this, 8));
		hero.addView(text(this, getString(R.string.roadcrew_tacho_remaining_break_driven, hm(r.drivenSinceBreak)),
				13, false, secondary), below(this, 8));
		if (r.breakPartTaken) {
			hero.addView(text(this, getString(R.string.roadcrew_tacho_remaining_break_part), 13, false, secondary),
					below(this, 4));
		}
		content.addView(hero, below(this, r.restingSince >= 0 ? 12 : 0));

		// Article 6(1), 6(2), 6(3) and 8: two tiles a row.
		boolean extended = r.drivenToday > 540;
		String todaySub = getString(R.string.roadcrew_tacho_remaining_left_of, extended ? "10:00" : "9:00");
		if (!extended && r.todayLeftExtended >= 0) {
			todaySub += "\n" + getString(R.string.roadcrew_tacho_remaining_today_extended, hm(r.todayLeftExtended));
		}
		row(tile(R.string.roadcrew_tacho_remaining_today, hm(r.todayLeft), todaySub),
				tile(R.string.roadcrew_tacho_remaining_extensions,
						getString(R.string.roadcrew_tacho_remaining_of_two, r.extensionsLeft),
						getString(R.string.roadcrew_tacho_remaining_extensions_sub)));
		row(tile(R.string.roadcrew_tacho_remaining_week, hm(r.weekLeft),
						getString(R.string.roadcrew_tacho_remaining_left_of, "56:00")),
				tile(R.string.roadcrew_tacho_remaining_two_weeks, hm(r.twoWeeksLeft),
						getString(R.string.roadcrew_tacho_remaining_left_of, "90:00")));
		String unknown = getString(R.string.roadcrew_tacho_remaining_unknown);
		row(tile(R.string.roadcrew_tacho_remaining_reduced,
						r.reducedRestsLeft >= 0 ? getString(R.string.roadcrew_tacho_remaining_of_three, r.reducedRestsLeft) : "-",
						r.reducedRestsLeft >= 0 ? getString(R.string.roadcrew_tacho_remaining_reduced_sub) : unknown),
				tile(R.string.roadcrew_tacho_remaining_daily_rest,
						r.dailyRestBy >= 0 ? when(r.dailyRestBy) : "-",
						r.dailyRestBy < 0 ? unknown : r.reducedDailyRestBy >= 0
								? getString(R.string.roadcrew_tacho_remaining_daily_rest_sub, when(r.reducedDailyRestBy))
								: getString(R.string.roadcrew_tacho_remaining_daily_rest_sub_regular)));

		// Article 8(6): the weekly rest.
		LinearLayout weekly = card(this);
		weekly.setOrientation(LinearLayout.HORIZONTAL);
		weekly.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout titles = new LinearLayout(this);
		titles.setOrientation(LinearLayout.VERTICAL);
		titles.addView(text(this, getString(R.string.roadcrew_tacho_remaining_weekly_rest), 13, true, secondary));
		titles.addView(text(this, r.weeklyRestBy >= 0 ? getString(R.string.roadcrew_tacho_remaining_weekly_rest_sub) : unknown,
				12, false, secondary));
		weekly.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		weekly.addView(text(this, r.weeklyRestBy >= 0 ? when(r.weeklyRestBy) : "-", 20, true, textColor));
		content.addView(weekly, below(this, 12));

		content.addView(text(this, getString(R.string.roadcrew_tacho_remaining_note), 13, false, secondary), below(this, 14));
		Button refresh = new Button(this);
		refresh.setText(R.string.roadcrew_tacho_remaining_refresh);
		refresh.setAllCaps(false);
		refresh.setTextColor(color(this, R.color.roadcrew_tacho_accent));
		refresh.setBackground(RoadCrewTachoUi.shape(this, color(this, R.color.roadcrew_tacho_surface),
				R.color.roadcrew_tacho_accent, 14));
		// Back to the card screen: the reader downloads there, as before.
		refresh.setOnClickListener(v -> finish());
		LinearLayout.LayoutParams refreshParams = below(this, 12);
		refreshParams.height = dp(this, 52);
		content.addView(refresh, refreshParams);
	}

	private void row(@NonNull View left, @NonNull View right) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
		l.setMarginEnd(dp(this, 6));
		LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
		r.setMarginStart(dp(this, 6));
		row.addView(left, l);
		row.addView(right, r);
		content.addView(row, below(this, 12));
	}

	@NonNull
	private View tile(int title, @NonNull String value, @NonNull String sub) {
		LinearLayout tile = card(this);
		int secondary = color(this, R.color.roadcrew_tacho_secondary);
		tile.addView(text(this, getString(title), 13, true, secondary));
		tile.addView(text(this, value, 24, true, color(this, R.color.roadcrew_tacho_text)), below(this, 4));
		tile.addView(text(this, sub, 12, false, secondary), below(this, 2));
		return tile;
	}

	/** A time today as 20:45, another day with its day. */
	@NonNull
	private static String when(long epochMinute) {
		return RoadCrewTachoUi.local(epochMinute).toLocalDate().equals(LocalDate.now())
				? RoadCrewTachoUi.time(epochMinute) : RoadCrewTachoUi.dayTime(epochMinute);
	}
}
