package net.osmand.plus.roadcrew.tacho;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Kind;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Place;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoActivities.Segment;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoViolations.Severity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * What the two driving-time screens share (ROADMAP 381): the card screen's colours and
 * shapes, the activity colours of the approved mockup, times by the phone's clock, and the
 * two drawings - a day's thin bar in the list, and the day by the hour.
 */
final class RoadCrewTachoUi {

	static final int DRIVING = 0xFF8B5CF6;
	static final int WORK = 0xFFF59E0B;
	static final int AVAILABLE = 0xFFEAB308;
	static final int REST = 0xFF06B6D4;
	static final int UNKNOWN = 0xFFCBD5E1;
	static final int SHIFT = 0xFFFAE8A0;
	static final int PLACE = 0xFFDC2626;

	private RoadCrewTachoUi() {
	}

	static int color(@NonNull Context context, @ColorRes int id) {
		return context.getResources().getColor(id, context.getTheme());
	}

	static int dp(@NonNull Context context, float value) {
		return Math.round(value * context.getResources().getDisplayMetrics().density);
	}

	static float sp(@NonNull Context context, float value) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics());
	}

	static int kindColor(@NonNull Segment s) {
		if (!s.known) {
			return UNKNOWN;
		}
		switch (s.kind) {
			case DRIVING:
				return DRIVING;
			case WORK:
				return WORK;
			case AVAILABLE:
				return AVAILABLE;
			default:
				return REST;
		}
	}

	static int severityColor(@NonNull Severity s) {
		switch (s) {
			case MOST_SERIOUS:
				return 0xFF991B1B;
			case VERY_SERIOUS:
				return 0xFFDC2626;
			case SERIOUS:
				return 0xFFD97706;
			default:
				return 0xFF6B7280;
		}
	}

	/** Minutes as hours:minutes - 6:43, 124:58. */
	static String hm(long minutes) {
		return (minutes / 60) + ":" + String.format(Locale.US, "%02d", minutes % 60);
	}

	static ZonedDateTime local(long epochMinute) {
		return Instant.ofEpochSecond(epochMinute * 60).atZone(ZoneId.systemDefault());
	}

	static String time(long epochMinute) {
		return local(epochMinute).format(DateTimeFormatter.ofPattern("HH:mm"));
	}

	/** "Пн, 13.07 06:44"; another year's with its year (RoadCrewTachoDates). */
	static String dayTime(long epochMinute) {
		return RoadCrewTachoDates.dayTime(local(epochMinute), LocalDate.now(), Locale.getDefault());
	}

	/** "Чт, 13.08"; another year's with its year (RoadCrewTachoDates). */
	static String shortDay(@NonNull LocalDate date) {
		return RoadCrewTachoDates.shortDay(date, LocalDate.now(), Locale.getDefault());
	}

	/** "Чт, 13 август 2026". */
	static String longDay(@NonNull LocalDate date) {
		return capital(date.format(DateTimeFormatter.ofPattern("EE, d MMMM yyyy", Locale.getDefault())));
	}

	static String date(@NonNull LocalDate date) {
		return date.format(DateTimeFormatter.ofPattern("dd.MM"));
	}

	static String capital(@NonNull String s) {
		return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1).replace(".", "");
	}

	static String country(int code) {
		return "bg".equals(Locale.getDefault().getLanguage()) ? RoadCrewTachoNations.bulgarian(code)
				: RoadCrewTachoNations.english(code);
	}

	static byte[] read(@NonNull ContentResolver resolver, @NonNull Uri uri) throws IOException {
		try (InputStream in = resolver.openInputStream(uri)) {
			if (in == null) {
				throw new IOException("the file cannot be opened");
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[16384];
			int n;
			while ((n = in.read(buffer)) > 0) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	// ---- building blocks of the screens ------------------------------------------------------------

	static GradientDrawable shape(@NonNull Context c, int fill, @ColorRes int stroke, float radiusDp) {
		GradientDrawable d = new GradientDrawable();
		d.setColor(fill);
		if (stroke != 0) {
			d.setStroke(dp(c, 1), color(c, stroke));
		}
		d.setCornerRadius(dp(c, radiusDp));
		return d;
	}

	static LinearLayout card(@NonNull Context c) {
		LinearLayout card = new LinearLayout(c);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setBackground(shape(c, color(c, R.color.roadcrew_tacho_surface), R.color.roadcrew_tacho_line, 16));
		int pad = dp(c, 16);
		card.setPadding(pad, pad, pad, pad);
		return card;
	}

	static LinearLayout.LayoutParams below(@NonNull Context c, int topDp) {
		LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		p.topMargin = dp(c, topDp);
		return p;
	}

	static TextView text(@NonNull Context c, @NonNull CharSequence s, float sizeSp, boolean bold, int color) {
		TextView t = new TextView(c);
		t.setText(s);
		t.setTextSize(sizeSp);
		t.setTextColor(color);
		if (bold) {
			t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		}
		return t;
	}

	static TextView pill(@NonNull Context c, @NonNull String s, int background, int textColor) {
		TextView t = text(c, s, 13, true, textColor);
		t.setBackground(shape(c, background, 0, 14));
		t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
		t.setGravity(Gravity.CENTER);
		return t;
	}

	/** The card screen's top bar: back, a title, a line under it. */
	static LinearLayout topBar(@NonNull Activity a, @NonNull String title, @Nullable String subtitle) {
		LinearLayout bar = new LinearLayout(a);
		bar.setOrientation(LinearLayout.HORIZONTAL);
		bar.setGravity(Gravity.CENTER_VERTICAL);
		bar.setBackgroundColor(color(a, R.color.roadcrew_tacho_surface));
		bar.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
		ImageButton back = new ImageButton(a);
		back.setImageResource(R.drawable.roadcrew_tacho_ic_back);
		back.setColorFilter(color(a, R.color.roadcrew_tacho_text));
		back.setBackgroundColor(Color.TRANSPARENT);
		back.setContentDescription(a.getString(R.string.roadcrew_tacho_back));
		back.setOnClickListener(v -> a.finish());
		bar.addView(back, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
		LinearLayout titles = new LinearLayout(a);
		titles.setOrientation(LinearLayout.VERTICAL);
		titles.addView(text(a, title, 19, true, color(a, R.color.roadcrew_tacho_text)));
		if (subtitle != null) {
			titles.addView(text(a, subtitle, 13, false, color(a, R.color.roadcrew_tacho_secondary)));
		}
		LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
		tp.setMarginStart(dp(a, 8));
		bar.addView(titles, tp);
		return bar;
	}

	// ---- the drawings ---------------------------------------------------------------------------------

	/** One day's thin bar in the list: rest low, driving full height, work and availability between. */
	static final class DayBar extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final RectF rect = new RectF();
		private List<Segment> segments = List.of();
		private long dayStart;
		private long dayEnd;

		DayBar(Context c) {
			super(c);
		}

		void set(@NonNull List<Segment> segments, long dayStart, long dayEnd) {
			this.segments = segments;
			this.dayStart = dayStart;
			this.dayEnd = dayEnd;
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			float w = getWidth();
			float h = getHeight();
			paint.setColor(0xFFF0EFEB);
			rect.set(0, 0, w, h);
			canvas.drawRoundRect(rect, h / 6, h / 6, paint);
			float span = Math.max(1, dayEnd - dayStart);
			for (Segment s : segments) {
				float x0 = (s.from - dayStart) * w / span;
				float x1 = Math.max(x0 + 2, (s.to - dayStart) * w / span);
				paint.setColor(kindColor(s));
				float top = !s.known || s.kind == Kind.REST ? h * 0.62f : s.kind == Kind.DRIVING ? 0
						: s.kind == Kind.WORK ? h * 0.3f : h * 0.45f;
				canvas.drawRect(x0, top, x1, h, paint);
			}
		}
	}

	/** The day by the hour: the bars, the shift above them, the rest before and after, the places. */
	static final class DayTimeline extends View {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();
		private List<Segment> segments = List.of();
		private List<Place> places = List.of();
		private long dayStart;
		private long dayEnd;
		private long start = -1;
		private long end = -1;
		private int restBefore;
		private int restAfter;

		DayTimeline(Context c) {
			super(c);
			label.setTextAlign(Paint.Align.CENTER);
		}

		void set(@NonNull List<Segment> segments, @NonNull List<Place> places, long dayStart, long dayEnd, long start,
				long end, int restBefore, int restAfter) {
			this.segments = segments;
			this.places = places;
			this.dayStart = dayStart;
			this.dayEnd = dayEnd;
			this.start = start;
			this.end = end;
			this.restBefore = restBefore;
			this.restAfter = restAfter;
			invalidate();
		}

		private float x(long minute) {
			return (minute - dayStart) * getWidth() / (float) Math.max(1, dayEnd - dayStart);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			Context c = getContext();
			float w = getWidth();
			float axis = sp(c, 12) + dp(c, 6);
			float top = dp(c, 34);
			float bottom = getHeight() - axis;
			float h = bottom - top;
			// hours: a line every two, the phone's clock
			label.setTextSize(sp(c, 11));
			label.setColor(color(c, R.color.roadcrew_tacho_secondary));
			paint.setStrokeWidth(dp(c, 1));
			int hours = (int) Math.round((dayEnd - dayStart) / 60.0);
			for (int hour = 0; hour <= hours; hour += 2) {
				float xx = Math.min(w - 1, hour * w / Math.max(1, hours));
				paint.setColor(0xFFECEBE6);
				canvas.drawLine(xx, top, xx, bottom, paint);
				canvas.drawText(String.format(Locale.US, "%02d", hour), Math.max(dp(c, 8), Math.min(w - dp(c, 8), xx)),
						getHeight() - dp(c, 2), label);
			}
			paint.setStyle(Paint.Style.FILL);
			for (Segment s : segments) {
				paint.setColor(kindColor(s));
				float t = !s.known || s.kind == Kind.REST ? top + h * 0.72f : s.kind == Kind.DRIVING ? top + h * 0.08f
						: s.kind == Kind.WORK ? top + h * 0.42f : top + h * 0.55f;
				canvas.drawRect(x(s.from), t, Math.max(x(s.from) + 2, x(s.to)), bottom, paint);
			}
			paint.setColor(PLACE);
			paint.setStrokeWidth(dp(c, 2));
			for (Place p : places) {
				canvas.drawLine(x(p.minute), top - dp(c, 6), x(p.minute), bottom, paint);
			}
			label.setColor(color(c, R.color.roadcrew_tacho_text));
			label.setTextSize(sp(c, 12));
			label.setFakeBoldText(true);
			if (start >= 0 && end > start) {
				float x0 = Math.max(0, x(start));
				float x1 = Math.min(w, x(end));
				float mid = top - dp(c, 18);
				float tip = dp(c, 8);
				path.reset();
				path.moveTo(x0, mid);
				path.lineTo(x0 + tip, mid - dp(c, 10));
				path.lineTo(x1 - tip, mid - dp(c, 10));
				path.lineTo(x1, mid);
				path.lineTo(x1 - tip, mid + dp(c, 10));
				path.lineTo(x0 + tip, mid + dp(c, 10));
				path.close();
				paint.setColor(SHIFT);
				canvas.drawPath(path, paint);
				canvas.drawText(hm(end - start), (x0 + x1) / 2, mid + sp(c, 4), label);
				float restY = top + h * 0.6f;
				if (restBefore > 0 && x0 > dp(c, 44)) {
					canvas.drawText(hm(restBefore), x0 / 2, restY, label);
				}
				if (restAfter > 0 && w - x1 > dp(c, 44)) {
					canvas.drawText(hm(restAfter), (x1 + w) / 2, restY, label);
				}
			}
			label.setFakeBoldText(false);
		}
	}
}
