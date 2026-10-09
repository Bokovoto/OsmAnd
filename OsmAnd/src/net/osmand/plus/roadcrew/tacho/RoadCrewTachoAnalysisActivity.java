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
import android.graphics.Color;
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
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Violation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * "Анализ" of one downloaded card file (Galin, 09.10.2026, ROADMAP 381): the violations - the
 * last 28 days or the whole card - the 28 days in numbers and the days, by the phone's clock.
 * Everything from the file on the phone; nothing is sent anywhere.
 */
public final class RoadCrewTachoAnalysisActivity extends Activity {

	private static final String TAG = "RoadCrewTacho";
	static final String EXTRA_URI = "roadcrew_tacho_uri";
	static final String EXTRA_TAKEN_AT = "roadcrew_tacho_taken_at";
	private static final int DAYS_PAGE = 14;

	private Uri uri;
	private long takenAt;
	private LinearLayout content;
	@Nullable private RoadCrewTachoAnalysis.Summary summary;
	@Nullable private Card card;
	private boolean wholeCard;
	private int daysShown = DAYS_PAGE;

	/** From the card screen's file list. {@code takenAt}: the download, epoch millis, or 0. */
	public static void open(@NonNull Context context, @NonNull Uri uri, long takenAt) {
		Intent intent = new Intent(context, RoadCrewTachoAnalysisActivity.class);
		intent.putExtra(EXTRA_URI, uri);
		intent.putExtra(EXTRA_TAKEN_AT, takenAt);
		context.startActivity(intent);
	}

	static long downloadMinute(long takenAt) {
		return takenAt > 0 ? takenAt / 60000 : Long.MAX_VALUE;
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		uri = getIntent().getParcelableExtra(EXTRA_URI);
		takenAt = getIntent().getLongExtra(EXTRA_TAKEN_AT, 0);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(color(this, R.color.roadcrew_tacho_ground));
		String subtitle = takenAt > 0
				? getString(R.string.roadcrew_tacho_analysis_subtitle, RoadCrewTachoUi.dayTime(takenAt / 60000)) : null;
		root.addView(RoadCrewTachoUi.topBar(this, getString(R.string.roadcrew_tacho_analysis_title), subtitle));
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
		load();
	}

	private void load() {
		new Thread(() -> {
			try {
				if (uri == null) {
					throw new java.io.IOException("no file");
				}
				Card c = RoadCrewTachoActivities.read(RoadCrewTachoUi.read(getContentResolver(), uri), downloadMinute(takenAt));
				List<Violation> violations = RoadCrewTachoViolations.check(c);
				RoadCrewTachoAnalysis.Summary s = RoadCrewTachoAnalysis.summarize(c, violations, ZoneId.systemDefault(),
						downloadMinute(takenAt));
				runOnUiThread(() -> {
					if (!isFinishing()) {
						card = c;
						summary = s;
						render();
					}
				});
			} catch (Exception e) {
				Log.w(TAG, "card file analysis failed: " + e.getMessage(), e);
				runOnUiThread(() -> {
					content.removeAllViews();
					content.addView(text(this, getString(R.string.roadcrew_tacho_analysis_failed, String.valueOf(e.getMessage())),
							15, false, color(this, R.color.roadcrew_tacho_err)));
				});
			}
		}, "roadcrew-tacho-analysis").start();
	}

	private void render() {
		RoadCrewTachoAnalysis.Summary s = summary;
		if (s == null || card == null) {
			return;
		}
		content.removeAllViews();
		if (!card.complete) {
			TextView warn = text(this, getString(R.string.roadcrew_tacho_analysis_incomplete), 14, false,
					color(this, R.color.roadcrew_tacho_warn));
			warn.setBackground(RoadCrewTachoUi.shape(this, color(this, R.color.roadcrew_tacho_warn_bg), 0, 12));
			warn.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
			content.addView(warn, below(this, 0));
		}
		content.addView(violations(s), below(this, card.complete ? 0 : 12));
		content.addView(window(s), below(this, 12));
		content.addView(days(s), below(this, 12));
	}

	// ---- violations ------------------------------------------------------------------------------------

	private View violations(@NonNull RoadCrewTachoAnalysis.Summary s) {
		LinearLayout box = card(this);
		List<Violation> list = wholeCard ? s.allViolations : s.violationsInWindow;
		LinearLayout head = new LinearLayout(this);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.addView(text(this, getString(R.string.roadcrew_tacho_violations), 19, true, color(this, R.color.roadcrew_tacho_text)));
		if (!list.isEmpty()) {
			LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT);
			bp.setMarginStart(dp(this, 8));
			head.addView(RoadCrewTachoUi.pill(this, String.valueOf(list.size()), 0xFFDC2626, Color.WHITE), bp);
		}
		head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
		head.addView(chip(R.string.roadcrew_tacho_violations_28, !wholeCard, false));
		LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		cp.setMarginStart(dp(this, 6));
		head.addView(chip(R.string.roadcrew_tacho_violations_all, wholeCard, true), cp);
		box.addView(head);
		for (Violation v : list) {
			box.addView(violation(v), below(this, 12));
		}
		if (list.isEmpty() || (wholeCard && s.violationsInWindow.isEmpty())) {
			TextView ok = text(this, "✓  " + getString(wholeCard && !list.isEmpty() ? R.string.roadcrew_tacho_violations_none_28
					: wholeCard ? R.string.roadcrew_tacho_violations_none : R.string.roadcrew_tacho_violations_none_28), 16, true,
					color(this, R.color.roadcrew_tacho_ok));
			box.addView(ok, below(this, 12));
		}
		box.addView(text(this, getString(R.string.roadcrew_tacho_analysis_note), 12, false,
				color(this, R.color.roadcrew_tacho_secondary)), below(this, 12));
		return box;
	}

	private TextView chip(int label, boolean on, boolean whole) {
		TextView chip = RoadCrewTachoUi.pill(this, getString(label),
				on ? color(this, R.color.roadcrew_tacho_accent) : color(this, R.color.roadcrew_tacho_off_bg),
				on ? Color.WHITE : color(this, R.color.roadcrew_tacho_secondary));
		chip.setOnClickListener(v -> {
			wholeCard = whole;
			render();
		});
		return chip;
	}

	private View violation(@NonNull Violation v) {
		LinearLayout item = new LinearLayout(this);
		item.setOrientation(LinearLayout.VERTICAL);
		item.setBackground(RoadCrewTachoUi.shape(this, color(this, R.color.roadcrew_tacho_err_bg), 0, 14));
		int pad = dp(this, 12);
		item.setPadding(pad, pad, pad, pad);
		LinearLayout top = new LinearLayout(this);
		top.setGravity(Gravity.CENTER_VERTICAL);
		top.addView(RoadCrewTachoUi.pill(this, getString(severity(v.severity)), RoadCrewTachoUi.severityColor(v.severity), Color.WHITE));
		LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		wp.setMarginStart(dp(this, 8));
		top.addView(text(this, RoadCrewTachoUi.dayTime(v.from) + " – " + RoadCrewTachoUi.dayTime(v.to), 13, false,
				color(this, R.color.roadcrew_tacho_secondary)), wp);
		item.addView(top);
		item.addView(text(this, getString(title(v.rule)), 17, true, color(this, R.color.roadcrew_tacho_text)), below(this, 8));
		item.addView(text(this, detail(v), 14, false, color(this, R.color.roadcrew_tacho_text)), below(this, 4));
		item.addView(text(this, getString(R.string.roadcrew_tacho_rule_basis, article(v.rule)), 13, false,
				color(this, R.color.roadcrew_tacho_secondary)), below(this, 4));
		TextView see = text(this, getString(R.string.roadcrew_tacho_see_days), 14, true, color(this, R.color.roadcrew_tacho_accent));
		see.setGravity(Gravity.END);
		see.setPadding(0, dp(this, 6), 0, 0);
		LocalDate day = RoadCrewTachoUi.local(v.from).toLocalDate();
		see.setOnClickListener(x -> RoadCrewTachoDayActivity.open(this, uri, takenAt, day));
		item.addView(see, below(this, 0));
		return item;
	}

	static int severity(@NonNull RoadCrewTachoViolations.Severity s) {
		switch (s) {
			case MOST_SERIOUS:
				return R.string.roadcrew_tacho_severity_most_serious;
			case VERY_SERIOUS:
				return R.string.roadcrew_tacho_severity_very_serious;
			case SERIOUS:
				return R.string.roadcrew_tacho_severity_serious;
			default:
				return R.string.roadcrew_tacho_severity_minor;
		}
	}

	static int title(@NonNull RoadCrewTachoViolations.Rule r) {
		switch (r) {
			case BREAK:
				return R.string.roadcrew_tacho_rule_break;
			case DAILY_DRIVING:
				return R.string.roadcrew_tacho_rule_daily_driving;
			case WEEKLY_DRIVING:
				return R.string.roadcrew_tacho_rule_weekly_driving;
			case FORTNIGHT_DRIVING:
				return R.string.roadcrew_tacho_rule_fortnight_driving;
			case DAILY_REST:
				return R.string.roadcrew_tacho_rule_daily_rest;
			default:
				return R.string.roadcrew_tacho_rule_weekly_rest_late;
		}
	}

	static String article(@NonNull RoadCrewTachoViolations.Rule r) {
		switch (r) {
			case BREAK:
				return "7";
			case DAILY_DRIVING:
				return "6(1)";
			case WEEKLY_DRIVING:
				return "6(2)";
			case FORTNIGHT_DRIVING:
				return "6(3)";
			case DAILY_REST:
				return "8(2)";
			default:
				return "8(6)";
		}
	}

	private String detail(@NonNull Violation v) {
		switch (v.rule) {
			case BREAK:
				return getString(R.string.roadcrew_tacho_rule_break_detail, hm(v.measured));
			case DAILY_DRIVING:
				return getString(R.string.roadcrew_tacho_rule_daily_driving_detail, hm(v.measured), hm(v.limit));
			case WEEKLY_DRIVING:
				return getString(R.string.roadcrew_tacho_rule_weekly_driving_detail, hm(v.measured));
			case FORTNIGHT_DRIVING:
				return getString(R.string.roadcrew_tacho_rule_fortnight_driving_detail, hm(v.measured));
			case DAILY_REST:
				if (v.split) {
					return getString(R.string.roadcrew_tacho_rule_daily_rest_detail_split, hm(v.measured));
				}
				if (v.periods > 1) {
					return getString(R.string.roadcrew_tacho_rule_daily_rest_detail_days, v.periods, hm(v.measured), hm(v.limit));
				}
				return getString(R.string.roadcrew_tacho_rule_daily_rest_detail, hm(v.measured), hm(v.limit));
			default:
				return getString(R.string.roadcrew_tacho_rule_weekly_rest_late_detail, hm(v.measured));
		}
	}

	// ---- the 28 days -----------------------------------------------------------------------------------

	private View window(@NonNull RoadCrewTachoAnalysis.Summary s) {
		LinearLayout box = card(this);
		box.addView(text(this, getString(R.string.roadcrew_tacho_window_title, RoadCrewTachoUi.date(s.windowFrom),
				RoadCrewTachoUi.date(s.downloadDate)), 17, true, color(this, R.color.roadcrew_tacho_text)));
		LinearLayout stats = new LinearLayout(this);
		String[][] cells = {
				{String.valueOf(s.workingDays), getString(R.string.roadcrew_tacho_window_working_days)},
				{hm(s.driving), getString(R.string.roadcrew_tacho_window_driving)},
				{hm(s.work), getString(R.string.roadcrew_tacho_window_work)},
				{String.format(java.util.Locale.getDefault(), "%,d", s.km), getString(R.string.roadcrew_tacho_window_km)},
		};
		for (String[] cell : cells) {
			LinearLayout col = new LinearLayout(this);
			col.setOrientation(LinearLayout.VERTICAL);
			col.addView(text(this, cell[0], 22, true, color(this, R.color.roadcrew_tacho_text)));
			col.addView(text(this, cell[1], 12, false, color(this, R.color.roadcrew_tacho_secondary)));
			stats.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		}
		box.addView(stats, below(this, 12));
		box.addView(text(this, getString(R.string.roadcrew_tacho_window_next_download,
				RoadCrewTachoUi.date(s.nextDownloadDue)), 13, false, color(this, R.color.roadcrew_tacho_secondary)), below(this, 12));
		return box;
	}

	// ---- the days ---------------------------------------------------------------------------------------

	private View days(@NonNull RoadCrewTachoAnalysis.Summary s) {
		LinearLayout box = card(this);
		box.addView(text(this, getString(R.string.roadcrew_tacho_days), 19, true, color(this, R.color.roadcrew_tacho_text)));
		List<Day> worked = s.workingDayList();
		ZoneId zone = ZoneId.systemDefault();
		for (int i = 0; i < worked.size() && i < daysShown; i++) {
			Day d = worked.get(i);
			View line = new View(this);
			line.setBackgroundColor(color(this, R.color.roadcrew_tacho_line));
			box.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));
			LinearLayout row = new LinearLayout(this);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(0, dp(this, 10), 0, dp(this, 10));
			LinearLayout left = new LinearLayout(this);
			left.setOrientation(LinearLayout.VERTICAL);
			left.addView(text(this, RoadCrewTachoUi.shortDay(d.date), 16, true, color(this, R.color.roadcrew_tacho_text)));
			left.addView(text(this, d.start >= 0 ? RoadCrewTachoUi.time(d.start) + " – " + RoadCrewTachoUi.time(d.end) : "",
					13, false, color(this, R.color.roadcrew_tacho_secondary)));
			row.addView(left, new LinearLayout.LayoutParams(dp(this, 96), LinearLayout.LayoutParams.WRAP_CONTENT));
			RoadCrewTachoUi.DayBar bar = new RoadCrewTachoUi.DayBar(this);
			bar.set(d.segments, d.date.atStartOfDay(zone).toEpochSecond() / 60,
					d.date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60);
			LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(this, 26), 1);
			barParams.setMarginStart(dp(this, 8));
			barParams.setMarginEnd(dp(this, 8));
			row.addView(bar, barParams);
			LinearLayout right = new LinearLayout(this);
			right.setOrientation(LinearLayout.VERTICAL);
			right.setGravity(Gravity.END);
			right.addView(text(this, getString(R.string.roadcrew_tacho_day_driving, hm(d.driving)), 15, true, RoadCrewTachoUi.DRIVING));
			right.addView(text(this, getString(R.string.roadcrew_tacho_day_work, hm(d.work)), 13, false,
					color(this, R.color.roadcrew_tacho_secondary)));
			row.addView(right);
			LocalDate date = d.date;
			row.setOnClickListener(v -> RoadCrewTachoDayActivity.open(this, uri, takenAt, date));
			box.addView(row);
		}
		if (worked.size() > daysShown) {
			TextView more = text(this, getString(R.string.roadcrew_tacho_days_older), 15, true, color(this, R.color.roadcrew_tacho_accent));
			more.setGravity(Gravity.CENTER);
			more.setPadding(0, dp(this, 12), 0, dp(this, 4));
			more.setOnClickListener(v -> {
				daysShown += DAYS_PAGE;
				render();
			});
			box.addView(more, below(this, 0));
		}
		return box;
	}
}
