package net.osmand.plus.roadcrew;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

final class RoadCrewUi {

	static final int BACKGROUND = 0xff202226;
	static final int SURFACE = 0xff2d3035;
	static final int SURFACE_LIGHT = 0xff3b3e43;
	static final int TEXT = 0xfff4f7f5;
	static final int SECONDARY_TEXT = 0xffaab2ae;
	static final int PRIMARY = 0xff19a974;
	static final int DANGER = 0xffef4444;

	private RoadCrewUi() {
	}

	@NonNull
	static LinearLayout createPanel(@NonNull Context context, @NonNull String title) {
		LinearLayout content = new LinearLayout(context);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 18));
		content.setBackground(roundRect(BACKGROUND, dp(context, 28)));

		TextView titleView = new TextView(context);
		titleView.setText(title);
		titleView.setTextColor(TEXT);
		titleView.setTextSize(26);
		titleView.setGravity(Gravity.START);
		titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
		content.addView(titleView, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		return content;
	}

	@NonNull
	static TextView addBody(@NonNull Context context, @NonNull LinearLayout content, @NonNull String text) {
		TextView view = new TextView(context);
		view.setText(text);
		view.setTextColor(TEXT);
		view.setTextSize(15);
		view.setLineSpacing(dp(context, 2), 1.0f);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = dp(context, 14);
		content.addView(view, params);
		return view;
	}

	@NonNull
	static TextView addSectionTitle(@NonNull Context context, @NonNull LinearLayout content, @NonNull String text) {
		TextView view = new TextView(context);
		view.setText(text);
		view.setTextColor(TEXT);
		view.setTextSize(17);
		view.setTypeface(view.getTypeface(), Typeface.BOLD);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = dp(context, 16);
		content.addView(view, params);
		return view;
	}

	@NonNull
	static EditText createInput(@NonNull Context context, @NonNull String hint, boolean multiLine) {
		EditText input = new EditText(context);
		input.setTextColor(TEXT);
		input.setHintTextColor(SECONDARY_TEXT);
		input.setTextSize(15);
		input.setHint(hint);
		input.setSingleLine(!multiLine);
		input.setMinLines(multiLine ? 2 : 1);
		input.setMaxLines(multiLine ? 4 : 1);
		input.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
		input.setBackground(roundRect(SURFACE, dp(context, 14), 0xff555b61, dp(context, 1)));
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
				| (multiLine ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
		return input;
	}

	static void addInput(@NonNull Context context, @NonNull LinearLayout content, @NonNull EditText input) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = dp(context, 14);
		content.addView(input, params);
	}

	@NonNull
	static ScrollView addMessageArea(@NonNull Context context, @NonNull LinearLayout content,
			@NonNull TextView messagesView, int heightDp) {
		messagesView.setTextColor(TEXT);
		messagesView.setTextSize(14);
		messagesView.setLineSpacing(dp(context, 2), 1.0f);
		messagesView.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));

		ScrollView scrollView = new ScrollView(context);
		scrollView.setBackground(roundRect(SURFACE, dp(context, 16), 0xff4b5258, dp(context, 1)));
		scrollView.addView(messagesView, new ScrollView.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp));
		params.topMargin = dp(context, 12);
		content.addView(scrollView, params);
		return scrollView;
	}

	@NonNull
	static LinearLayout addButtonRow(@NonNull Context context, @NonNull LinearLayout content) {
		LinearLayout row = new LinearLayout(context);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.END);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.topMargin = dp(context, 16);
		content.addView(row, params);
		return row;
	}

	@NonNull
	static Button addButton(@NonNull Context context, @NonNull LinearLayout row, @NonNull String title,
			boolean primary, @NonNull View.OnClickListener listener) {
		Button button = new Button(context);
		button.setText(title);
		button.setAllCaps(false);
		button.setTextSize(14);
		button.setTextColor(primary ? Color.WHITE : TEXT);
		button.setBackground(roundRect(primary ? PRIMARY : SURFACE_LIGHT, dp(context, 18)));
		button.setPadding(dp(context, 14), 0, dp(context, 14), 0);
		button.setMinHeight(dp(context, 42));
		button.setOnClickListener(listener);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 44));
		params.leftMargin = dp(context, 8);
		row.addView(button, params);
		return button;
	}

	@NonNull
	static Button addFullWidthButton(@NonNull Context context, @NonNull LinearLayout content,
			@NonNull String title, boolean primary, @NonNull View.OnClickListener listener) {
		Button button = new Button(context);
		button.setText(title);
		button.setAllCaps(false);
		button.setTextSize(14);
		button.setTextColor(primary ? Color.WHITE : TEXT);
		button.setBackground(roundRect(primary ? PRIMARY : SURFACE_LIGHT, dp(context, 16)));
		button.setOnClickListener(listener);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46));
		params.topMargin = dp(context, 8);
		content.addView(button, params);
		return button;
	}

	@NonNull
	static AlertDialog createDialog(@NonNull Context context, @NonNull LinearLayout content) {
		ScrollView scrollView = new ScrollView(context);
		scrollView.setFillViewport(false);
		scrollView.addView(content);
		AlertDialog dialog = new AlertDialog.Builder(context)
				.setView(scrollView)
				.create();
		dialog.setOnShowListener(d -> applyWindow(dialog));
		return dialog;
	}

	@NonNull
	static AlertDialog createBottomDialog(@NonNull Context context, @NonNull View content) {
		AlertDialog dialog = new AlertDialog.Builder(context)
				.setView(content)
				.create();
		dialog.setOnShowListener(d -> {
			applyWindow(dialog);
			Window window = dialog.getWindow();
			if (window != null) {
				window.setGravity(Gravity.BOTTOM);
				window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
				window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
				WindowManager.LayoutParams attributes = window.getAttributes();
				attributes.dimAmount = 0.68f;
				window.setAttributes(attributes);
			}
		});
		return dialog;
	}

	static void applyWindow(@NonNull AlertDialog dialog) {
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		}
	}

	@NonNull
	static GradientDrawable roundRect(int color, int radius) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(color);
		drawable.setCornerRadius(radius);
		return drawable;
	}

	@NonNull
	static GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeWidth) {
		GradientDrawable drawable = roundRect(color, radius);
		drawable.setStroke(strokeWidth, strokeColor);
		return drawable;
	}

	@NonNull
	static GradientDrawable oval(int color) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(color);
		drawable.setShape(GradientDrawable.OVAL);
		return drawable;
	}

	static int dp(@NonNull Context context, float value) {
		return (int) (value * context.getResources().getDisplayMetrics().density);
	}
}
