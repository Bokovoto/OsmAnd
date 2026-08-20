package net.osmand.plus.routepreparationmenu.cards;

import android.widget.TextView;

import androidx.annotation.NonNull;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.ApplicationMode;

public class RoadCrewRouteCalculationCard extends MapBaseCard {

	private int progress;
	private final long startedAtMillis;

	public RoadCrewRouteCalculationCard(@NonNull MapActivity mapActivity, int progress, long startedAtMillis) {
		super(mapActivity);
		this.progress = progress;
		this.startedAtMillis = startedAtMillis;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.card_roadcrew_route_calculation;
	}

	@Override
	protected void updateContent() {
		RoutingHelper routingHelper = app.getRoutingHelper();
		ApplicationMode mode = routingHelper.getAppMode();
		boolean truckMode = mode.isDerivedRoutingFrom(ApplicationMode.TRUCK);
		boolean offlineRouting = routingHelper.isOsmandRouting();

		setText(R.id.roadcrew_console_source, offlineRouting
				? getString(R.string.roadcrew_route_console_source_offline)
				: getString(R.string.roadcrew_route_console_source_ready));
		setText(R.id.roadcrew_console_profile, truckMode
				? getString(R.string.roadcrew_route_console_profile_truck)
				: getString(R.string.roadcrew_route_console_profile_car));
		setText(R.id.roadcrew_console_restrictions, truckMode
				? getString(R.string.roadcrew_route_console_restrictions_active)
				: getString(R.string.roadcrew_route_console_restrictions_na));
		setText(R.id.roadcrew_console_route_graph,
				getString(R.string.roadcrew_route_console_route_graph, Math.min(100, Math.max(0, progress))));

		TextView finalResult = view.findViewById(R.id.roadcrew_console_final_result);
		TextView currentTask = view.findViewById(R.id.roadcrew_console_current_task);
		if (progress >= 100) {
			finalResult.setText(R.string.roadcrew_route_console_finalizing);
			currentTask.setText(R.string.roadcrew_route_console_task_finalizing);
		} else {
			finalResult.setText(R.string.roadcrew_route_console_final_waiting);
			currentTask.setText(R.string.roadcrew_route_console_task_calculating);
		}

		RoadCrewRouteActivityView activityView = view.findViewById(R.id.roadcrew_route_activity);
		activityView.setRouteProgress(progress, startedAtMillis);
	}

	public void setProgress(int progress) {
		this.progress = progress;
		update();
	}
}
