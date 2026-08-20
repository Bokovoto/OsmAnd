package net.osmand.plus.routepreparationmenu.cards;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

public class RoadCrewRouteActivityView extends View {

	private static final int MAX_PROGRESS = 100;
	private static final long ANIMATION_FRAME_DELAY_MS = 90;

	private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint railPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint mutedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	private final Runnable animationTick = new Runnable() {
		@Override
		public void run() {
			if (!animating) {
				return;
			}
			animationPhase = (animationPhase + 0.1f) % 1f;
			invalidate();
			postDelayed(this, ANIMATION_FRAME_DELAY_MS);
		}
	};
	private boolean animating;
	private float animationPhase;
	private int progress;
	private long startedAtMillis = System.currentTimeMillis();

	public RoadCrewRouteActivityView(Context context) {
		this(context, null);
	}

	public RoadCrewRouteActivityView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public RoadCrewRouteActivityView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

		backgroundPaint.setColor(Color.BLACK);
		borderPaint.setColor(Color.rgb(72, 72, 72));
		borderPaint.setStyle(Paint.Style.STROKE);
		borderPaint.setStrokeWidth(dp(1));

		railPaint.setColor(Color.WHITE);
		railPaint.setStrokeWidth(dp(2));
		railPaint.setStrokeCap(Paint.Cap.SQUARE);

		progressPaint.setColor(Color.WHITE);
		progressPaint.setTextSize(dp(13));
		progressPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

		mutedPaint.setColor(Color.rgb(145, 145, 145));
		mutedPaint.setTextSize(dp(13));
		mutedPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
	}

	public void setRouteProgress(int progress, long startedAtMillis) {
		this.progress = Math.max(0, Math.min(MAX_PROGRESS, progress));
		this.startedAtMillis = startedAtMillis > 0 ? startedAtMillis : System.currentTimeMillis();
		invalidate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		startAnimation();
	}

	@Override
	protected void onDetachedFromWindow() {
		stopAnimation();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		float width = getWidth();
		float height = getHeight();
		float radius = dp(8);
		RectF bounds = new RectF(dp(1), dp(1), width - dp(1), height - dp(1));
		canvas.drawRoundRect(bounds, radius, radius, backgroundPaint);
		canvas.drawRoundRect(bounds, radius, radius, borderPaint);

		drawActivityRails(canvas, width, height);
		drawCompileProgress(canvas, width, height);
	}

	private void drawActivityRails(Canvas canvas, float width, float height) {
		float spacing = dp(19);
		float offset = animationPhase * spacing;
		float left = dp(11);
		float right = width - dp(11);
		railPaint.setAlpha(150);
		for (float y = -spacing + offset; y < height; y += spacing) {
			canvas.drawLine(left - dp(3), y + dp(8), left + dp(3), y, railPaint);
			canvas.drawLine(right - dp(3), y, right + dp(3), y + dp(8), railPaint);
		}
	}

	private void drawCompileProgress(Canvas canvas, float width, float height) {
		int cells = Math.max(8, Math.min(18, (int) ((width - dp(80)) / dp(17))));
		int filled = progress >= MAX_PROGRESS
				? cells
				: Math.min(cells, Math.round(cells * progress / (float) MAX_PROGRESS));
		StringBuilder bar = new StringBuilder("[");
		for (int i = 0; i < cells; i++) {
			bar.append(i < filled ? '*' : '.');
			if (i < cells - 1) {
				bar.append(' ');
			}
		}
		bar.append(']');

		float baseline = height - dp(17);
		canvas.drawText(bar.toString(), dp(25), baseline, progressPaint);

		long elapsedSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
		String elapsed = String.format(Locale.US, "T+%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60);
		float elapsedWidth = mutedPaint.measureText(elapsed);
		canvas.drawText(elapsed, width - dp(25) - elapsedWidth, dp(27), mutedPaint);
	}

	private void startAnimation() {
		if (animating) {
			return;
		}
		animating = true;
		post(animationTick);
	}

	private void stopAnimation() {
		animating = false;
		removeCallbacks(animationTick);
	}

	private float dp(float value) {
		return value * getResources().getDisplayMetrics().density;
	}
}
