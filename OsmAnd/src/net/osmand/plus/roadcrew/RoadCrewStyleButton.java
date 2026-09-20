package net.osmand.plus.roadcrew;

import static net.osmand.plus.quickaction.ButtonAppearanceParams.BIG_SIZE_DP;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.OPAQUE_ALPHA;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.ROUND_RADIUS_DP;
import static net.osmand.shared.grid.ButtonPositionSize.POS_RIGHT;
import static net.osmand.shared.grid.ButtonPositionSize.POS_BOTTOM;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.plus.quickaction.ButtonAppearanceParams;
import net.osmand.plus.views.controls.maphudbuttons.MapButton;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;
import net.osmand.shared.grid.ButtonPositionSize;

/**
 * RoadCrew's two style switches as ordinary OsmAnd map buttons, stacked above
 * the green report button on the right edge.
 * <p>
 * Galin, 20.09, two things. First: the map-colour switch has to be in the same
 * place whichever look is on - "в който и да е режим да е все на едно място, за да не
 * я търси после" - so it left the neon header and lives here, visible in
 * both looks. The panel switch is the neon header's own, so it appears here
 * only in classic, where that header is gone.
 * <p>
 * Second: as a floating column with fixed margins they landed on top of the
 * green report button in landscape. OsmAnd already arranges its map buttons on
 * a grid that keeps them apart in every orientation, so they join that grid
 * rather than guess at margins, and get the same disc and size as every other
 * map button - which also answers the earlier complaint that plain white
 * glyphs vanished against the light map.
 */
public class RoadCrewStyleButton extends MapButton {

	private final boolean mapColours;
	private final ButtonPositionSize defaultPositionSize;

	public RoadCrewStyleButton(@NonNull Context context) {
		this(context, null);
	}

	public RoadCrewStyleButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public RoadCrewStyleButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		// The layout tells the two apart; everything else here is shared.
		mapColours = getId() == R.id.roadcrew_map_colours_button;
		defaultPositionSize = createDefaultPositionSize();
		setContentDescription(context.getString(mapColours
				? R.string.roadcrew_map_colours_button
				: R.string.roadcrew_panels_button));
		// Visibility is decided by updateVisibility() below, which needs this on:
		// with no MapButtonState the framework's own check can never be true.
		setAlwaysVisible(true);
		setOnClickListener(v -> {
			if (mapActivity == null) {
				return;
			}
			if (mapColours) {
				RoadCrewNeonHud.toggleMapColours(mapActivity);
			} else {
				RoadCrewNeonHud.toggleVisualStyle(mapActivity);
			}
		});
	}

	@NonNull
	@Override
	public String getButtonId() {
		return mapColours ? "roadcrew_map_colours_button" : "roadcrew_panels_button";
	}

	@Nullable
	@Override
	public MapButtonState getButtonState() {
		return null;
	}

	@NonNull
	@Override
	public ButtonAppearanceParams createDefaultAppearanceParams() {
		return new ButtonAppearanceParams(
				mapColours ? "ic_action_map_style" : "ic_action_appearance",
				BIG_SIZE_DP, OPAQUE_ALPHA, ROUND_RADIUS_DP);
	}

	@Nullable
	@Override
	public ButtonPositionSize getDefaultPositionSize() {
		return defaultPositionSize;
	}

	@Override
	public boolean updateVisibility() {
		return updateVisibility(shouldShow());
	}

	@Override
	protected boolean shouldShow() {
		if (routeDialogOpened || mapActivity == null) {
			return false;
		}
		// The colour switch stays put in both looks; the panel switch would be a
		// duplicate of the one in neon's header, so it shows only in classic.
		return mapColours || !RoadCrewVisualStyle.isNeonBeta(mapActivity);
	}

	@NonNull
	private ButtonPositionSize createDefaultPositionSize() {
		ButtonPositionSize position = new ButtonPositionSize(getButtonId());
		position.setPositionHorizontal(POS_RIGHT);
		position.setPositionVertical(POS_BOTTOM);
		position.setMoveVertical();
		position.setMarginX(0);
		// Everything on this edge starts at the anchor and the grid stacks it
		// upwards in registration order (MapControlsLayer), which is what puts
		// the colour switch under the report button and the panel switch above.
		position.setMarginY(0);
		int size = BIG_SIZE_DP / 8 + 1;
		position.setSize(size, size);
		return position;
	}
}
