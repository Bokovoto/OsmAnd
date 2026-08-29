package net.osmand.plus.roadcrew;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.render.MapRenderRepositories;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;

import java.util.function.Consumer;

/** Renders the installed offline map for a whole-course review. */
final class RoadCrewTripMapBackground {

	static final class Result {
		@NonNull final Bitmap bitmap;
		@NonNull final RotatedTileBox tileBox;

		Result(@NonNull Bitmap bitmap, @NonNull RotatedTileBox tileBox) {
			this.bitmap = bitmap;
			this.tileBox = tileBox;
		}
	}

	private final OsmandApplication app;
	private volatile boolean cancelled;

	RoadCrewTripMapBackground(@NonNull OsmandApplication app) {
		this.app = app;
	}

	void cancel() {
		cancelled = true;
	}

	void load(double top, double left, double bottom, double right, int width, int height,
			@NonNull Consumer<Result> completed) {
		if (cancelled || width <= 0 || height <= 0) { return; }
		float density = app.getResources().getDisplayMetrics().density;
		RotatedTileBox tileBox = new RotatedTileBox.RotatedTileBoxBuilder()
				.setLocation((top + bottom) / 2, (left + right) / 2)
				.setZoom(15)
				.density(density)
				.setMapDensity(density)
				.setPixelDimensions(width, height, 0.5f, 0.5f).build();
		while (tileBox.getZoom() < 17 && tileBox.containsLatLon(top, left)
				&& tileBox.containsLatLon(bottom, right)) {
			tileBox.setZoom(tileBox.getZoom() + 1);
		}
		while (tileBox.getZoom() > 4 && (!tileBox.containsLatLon(top, left)
				|| !tileBox.containsLatLon(bottom, right))) {
			tileBox.setZoom(tileBox.getZoom() - 1);
		}
		if (tileBox.getZoom() > 4) { tileBox.setZoom(tileBox.getZoom() - 1); }

		ResourceManager resources = app.getResourceManager();
		MapRenderRepositories renderer = resources.getRenderer();
		boolean night = app.getDaynightHelper().isNightMode(ThemeUsageContext.APP);
		resources.updateRenderedMapNeeded(tileBox, new DrawSettings(night, true));
		RotatedTileBox requested = tileBox.copy();
		resources.updateRendererMap(requested, interrupted -> app.runInUIThread(() -> {
			if (cancelled || interrupted) { return; }
			Bitmap rendered = renderer.getBitmap();
			Bitmap copy = copy(rendered);
			if (copy != null && !cancelled) { completed.accept(new Result(copy, requested)); }
		}), true);
	}

	@Nullable
	private Bitmap copy(@Nullable Bitmap bitmap) {
		if (bitmap == null || bitmap.isRecycled()) { return null; }
		Bitmap.Config config = bitmap.getConfig() == null ? Bitmap.Config.ARGB_8888 : bitmap.getConfig();
		return bitmap.copy(config, false);
	}
}
