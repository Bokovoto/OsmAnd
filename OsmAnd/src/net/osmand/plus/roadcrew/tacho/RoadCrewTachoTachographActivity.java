package net.osmand.plus.roadcrew.tacho;

import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.below;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.card;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.color;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.dp;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.shape;
import static net.osmand.plus.roadcrew.tacho.RoadCrewTachoUi.text;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.ViewModelProvider;

import net.osmand.plus.R;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoBleChoice.Candidate;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoPairModel.State;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoPairModel.Step;

import java.util.List;

/**
 * The "Тахограф" section of the card screen (ROADMAP 439; Galin, 03.11.2026): the phone's paired
 * tachographs and which of them are near - a tap picks one for "Свали картата" - and "Нов тахограф",
 * the wizard that pairs a new one in the order the tachograph needs it (RoadCrewTachoPairModel). The
 * picked or newly paired tachograph goes back to the card screen as the result.
 */
public final class RoadCrewTachoTachographActivity extends ComponentActivity {

	/** The tachograph the card screen shows now, to mark it in the list. */
	static final String EXTRA_SELECTED = "roadcrew_selected";
	static final String EXTRA_ADDRESS = "roadcrew_address";
	static final String EXTRA_NAME = "roadcrew_name";
	static final String EXTRA_RSSI = "roadcrew_rssi";
	static final int WIZARD_STEPS = 4;

	private RoadCrewTachoPairModel model;
	private LinearLayout root;
	@Nullable
	private String selected;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		selected = getIntent().getStringExtra(EXTRA_SELECTED);
		root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(color(this, R.color.roadcrew_tacho_ground));
		setContentView(root);
		model = new ViewModelProvider(this).get(RoadCrewTachoPairModel.class);
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				back();
			}
		});
		model.getState().observe(this, this::render);
		if (savedInstanceState == null) {
			model.scanList();
		}
	}

	/** "Назад" in the wizard is one step back; on the list it closes the section. */
	private void back() {
		if (!model.back()) {
			finish();
		}
	}

	private void render(@NonNull State s) {
		if (s.getStep() == Step.PAIRED && s.getDevice() != null) {
			finishWith(s.getDevice());
			return;
		}
		root.removeAllViews();
		boolean list = s.getStep() == Step.LIST;
		LinearLayout bar = RoadCrewTachoUi.topBar(this,
				getString(list ? R.string.roadcrew_tacho_tachograph : R.string.roadcrew_tacho_section_new), null);
		bar.getChildAt(0).setOnClickListener(v -> back());
		root.addView(bar);
		View line = new View(this);
		line.setBackgroundColor(color(this, R.color.roadcrew_tacho_line));
		root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));
		ScrollView scroll = new ScrollView(this);
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(this, 16);
		content.setPadding(pad, pad, pad, pad);
		scroll.addView(content);
		root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
		switch (s.getStep()) {
			case LIST:
				renderList(content, s);
				break;
			case DATA:
				renderData(content);
				break;
			case CONNECT:
				renderConnect(content);
				break;
			case SEARCHING:
			case CHOOSE:
				renderChoose(content, s);
				break;
			default:
				renderCode(content, s);
				break;
		}
	}

	// ---- the list ---------------------------------------------------------------------------------

	private void renderList(@NonNull LinearLayout content, @NonNull State s) {
		content.addView(text(this, getString(R.string.roadcrew_tacho_section_paired), 13, true, secondary()));
		List<Candidate> paired = s.getPaired();
		if (paired.isEmpty()) {
			content.addView(text(this, getString(s.getScanning() ? R.string.roadcrew_tacho_section_scanning
					: R.string.roadcrew_tacho_section_none), 15, false, secondary()), below(this, 8));
		} else {
			LinearLayout rows = card(this);
			rows.setPadding(0, dp(this, 4), 0, dp(this, 4));
			for (int i = 0; i < paired.size(); i++) {
				if (i > 0) {
					View divider = new View(this);
					divider.setBackgroundColor(color(this, R.color.roadcrew_tacho_line));
					rows.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));
				}
				rows.addView(row(paired.get(i), s.getScanning()));
			}
			content.addView(rows, below(this, 8));
			if (s.getScanning()) {
				content.addView(text(this, getString(R.string.roadcrew_tacho_section_scanning), 13, false, secondary()),
						below(this, 8));
			}
		}
		if (s.getError() != null) {
			content.addView(text(this, getString(s.getError().contains("Bluetooth is off")
					? R.string.roadcrew_tacho_ble_off : R.string.roadcrew_tacho_section_scan_failed), 15, true,
					color(this, R.color.roadcrew_tacho_err)), below(this, 8));
		}
		Button add = button(getString(R.string.roadcrew_tacho_section_new), false);
		add.setTextColor(color(this, R.color.roadcrew_tacho_accent));
		add.setBackground(shape(this, color(this, R.color.roadcrew_tacho_surface), R.color.roadcrew_tacho_accent, 14));
		add.setOnClickListener(v -> model.startNew());
		content.addView(add, below(this, 16));
		content.addView(text(this, getString(R.string.roadcrew_tacho_section_note_terms), 13, false, secondary()),
				below(this, 16));
		content.addView(text(this, getString(R.string.roadcrew_tacho_section_note_remove), 13, false, secondary()),
				below(this, 6));
	}

	/** One paired tachograph: its name, whether it is heard now; a tap picks it. */
	@NonNull
	private View row(@NonNull Candidate c, boolean scanning) {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setMinimumHeight(dp(this, 64));
		row.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
		row.setClickable(true);
		row.setFocusable(true);
		LinearLayout texts = new LinearLayout(this);
		texts.setOrientation(LinearLayout.VERTICAL);
		texts.addView(text(this, c.name, 16, true, color(this, R.color.roadcrew_tacho_text)));
		if (RoadCrewTachoBleChoice.heard(c)) {
			texts.addView(text(this, getString(R.string.roadcrew_tacho_ble_nearby), 13, true,
					color(this, R.color.roadcrew_tacho_ok)));
		} else if (!scanning) {
			texts.addView(text(this, getString(R.string.roadcrew_tacho_section_far), 13, false, secondary()));
		}
		row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		ImageView mark = new ImageView(this);
		mark.setImageResource(R.drawable.roadcrew_tacho_ic_check);
		mark.setColorFilter(color(this, R.color.roadcrew_tacho_accent));
		mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		mark.setVisibility(c.address.equalsIgnoreCase(selected) ? View.VISIBLE : View.INVISIBLE);
		row.addView(mark, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
		row.setOnClickListener(v -> finishWith(c));
		return row;
	}

	// ---- the wizard: data, connecting, the tachograph, the code -------------------------------------

	private void renderData(@NonNull LinearLayout content) {
		LinearLayout panel = card(this);
		panel.addView(stepLabel(getString(R.string.roadcrew_tacho_wizard_step, 1, WIZARD_STEPS)));
		panel.addView(title(R.string.roadcrew_tacho_wizard_data_title), below(this, 6));
		panel.addView(body(R.string.roadcrew_tacho_wizard_data_vdo), below(this, 10));
		panel.addView(path(R.string.roadcrew_tacho_wizard_data_path1), below(this, 8));
		panel.addView(path(R.string.roadcrew_tacho_wizard_data_path2), below(this, 8));
		panel.addView(body(R.string.roadcrew_tacho_wizard_data_why), below(this, 10));
		panel.addView(small(R.string.roadcrew_tacho_wizard_data_other), below(this, 10));
		content.addView(panel);
		addButtons(content, R.string.roadcrew_tacho_wizard_continue, v -> model.dataDone());
	}

	private void renderConnect(@NonNull LinearLayout content) {
		LinearLayout panel = card(this);
		panel.addView(stepLabel(getString(R.string.roadcrew_tacho_wizard_step, 2, WIZARD_STEPS)));
		panel.addView(title(R.string.roadcrew_tacho_wizard_connect_title), below(this, 6));
		panel.addView(path(R.string.roadcrew_tacho_wizard_connect_vdo), below(this, 10));
		panel.addView(small(R.string.roadcrew_tacho_wizard_connect_other), below(this, 10));
		panel.addView(body(R.string.roadcrew_tacho_wizard_connect_go), below(this, 10));
		content.addView(panel);
		addButtons(content, R.string.roadcrew_tacho_wizard_continue, v -> model.searchNew());
	}

	private void renderChoose(@NonNull LinearLayout content, @NonNull State s) {
		LinearLayout panel = card(this);
		panel.addView(stepLabel(getString(R.string.roadcrew_tacho_wizard_step, 3, WIZARD_STEPS)));
		panel.addView(title(R.string.roadcrew_tacho_wizard_choose_title), below(this, 6));
		List<Candidate> found = s.getFound();
		if (s.getStep() == Step.SEARCHING) {
			panel.addView(body(R.string.roadcrew_tacho_wizard_choose_searching), below(this, 10));
		} else if (s.getError() != null) {
			panel.addView(text(this, getString(s.getError().contains("Bluetooth is off")
					? R.string.roadcrew_tacho_ble_off : R.string.roadcrew_tacho_section_scan_failed), 15, true,
					color(this, R.color.roadcrew_tacho_err)), below(this, 10));
		} else if (found.isEmpty()) {
			panel.addView(body(R.string.roadcrew_tacho_wizard_choose_none), below(this, 10));
		} else {
			for (int i = 0; i < found.size(); i++) {
				Candidate c = found.get(i);
				// Nearest first: the cab the phone is in is usually the strongest signal.
				Button pick = button(i == 0 ? getString(R.string.roadcrew_tacho_ble_nearest, c.name) : c.name, false);
				pick.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
				pick.setPadding(dp(this, 14), 0, dp(this, 14), 0);
				pick.setOnClickListener(v -> model.pair(c.address));
				panel.addView(pick, below(this, 8));
			}
			panel.addView(small(R.string.roadcrew_tacho_wizard_choose_note), below(this, 10));
		}
		content.addView(panel);
		if (s.getStep() == Step.CHOOSE) {
			Button again = button(getString(R.string.roadcrew_tacho_wizard_search_again), false);
			again.setOnClickListener(v -> model.searchNew());
			content.addView(again, below(this, 16));
		}
		Button cancel = button(getString(R.string.roadcrew_tacho_ble_cancel), false);
		cancel.setOnClickListener(v -> model.cancel());
		content.addView(cancel, below(this, 10));
	}

	private void renderCode(@NonNull LinearLayout content, @NonNull State s) {
		String step = getString(R.string.roadcrew_tacho_wizard_step, 4, WIZARD_STEPS);
		Candidate device = s.getDevice();
		LinearLayout panel = card(this);
		panel.addView(stepLabel(device == null ? step
				: getString(R.string.roadcrew_tacho_wizard_step_device, step, device.name)));
		if (s.getStep() == Step.FAILED) {
			panel.addView(text(this, getString(R.string.roadcrew_tacho_ble_pairing_failed), 18, true,
					color(this, R.color.roadcrew_tacho_err)), below(this, 6));
			panel.addView(body(R.string.roadcrew_tacho_wizard_failed_body), below(this, 10));
			content.addView(panel);
			addButtons(content, R.string.roadcrew_tacho_ble_retry_button, v -> model.again());
			return;
		}
		panel.addView(title(R.string.roadcrew_tacho_wizard_code_title), below(this, 6));
		panel.addView(body(R.string.roadcrew_tacho_wizard_code_body), below(this, 10));
		panel.addView(small(R.string.roadcrew_tacho_wizard_code_after), below(this, 10));
		content.addView(panel);
		Button cancel = button(getString(R.string.roadcrew_tacho_ble_cancel), false);
		cancel.setOnClickListener(v -> model.cancel());
		content.addView(cancel, below(this, 16));
	}

	// ---- the result and the pieces ------------------------------------------------------------------

	private void finishWith(@NonNull Candidate c) {
		Intent result = new Intent();
		result.putExtra(EXTRA_ADDRESS, c.address);
		result.putExtra(EXTRA_NAME, c.name);
		result.putExtra(EXTRA_RSSI, c.rssi);
		setResult(RESULT_OK, result);
		finish();
	}

	private void addButtons(@NonNull LinearLayout content, @StringRes int primary, @NonNull View.OnClickListener go) {
		Button next = button(getString(primary), true);
		next.setOnClickListener(go);
		content.addView(next, below(this, 16));
		Button cancel = button(getString(R.string.roadcrew_tacho_ble_cancel), false);
		cancel.setOnClickListener(v -> model.cancel());
		content.addView(cancel, below(this, 10));
	}

	@NonNull
	private Button button(@NonNull String label, boolean primary) {
		Button b = new Button(this);
		b.setAllCaps(false);
		b.setText(label);
		b.setTextSize(primary ? 17 : 16);
		b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		b.setTextColor(primary ? Color.WHITE : color(this, R.color.roadcrew_tacho_text));
		b.setBackground(primary ? shape(this, color(this, R.color.roadcrew_tacho_accent), 0, 14)
				: shape(this, color(this, R.color.roadcrew_tacho_surface), R.color.roadcrew_tacho_line, 14));
		b.setMinHeight(dp(this, primary ? 56 : 52));
		b.setStateListAnimator(null);
		return b;
	}

	@NonNull
	private TextView stepLabel(@NonNull String label) {
		return text(this, label, 13, true, secondary());
	}

	@NonNull
	private TextView title(@StringRes int id) {
		return text(this, getString(id), 18, true, color(this, R.color.roadcrew_tacho_text));
	}

	@NonNull
	private TextView body(@StringRes int id) {
		TextView t = text(this, getString(id), 15, false, color(this, R.color.roadcrew_tacho_text));
		t.setLineSpacing(0, 1.15f);
		return t;
	}

	@NonNull
	private TextView small(@StringRes int id) {
		TextView t = text(this, getString(id), 13, false, secondary());
		t.setLineSpacing(0, 1.15f);
		return t;
	}

	/** A menu path on the tachograph: bold, on its own grey band, as in the mockup. */
	@NonNull
	private TextView path(@StringRes int id) {
		TextView t = text(this, getString(id), 15, true, color(this, R.color.roadcrew_tacho_text));
		t.setBackground(shape(this, color(this, R.color.roadcrew_tacho_off_bg), 0, 10));
		t.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
		return t;
	}

	private int secondary() {
		return color(this, R.color.roadcrew_tacho_secondary);
	}
}
