package net.osmand.plus.roadcrew;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.render.MapRenderRepositories;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private volatile boolean cancelled;

	RoadCrewTripMapBackground(@NonNull OsmandApplication app) {
		this.app = app;
	}

	void cancel() {
		cancelled = true;
		executor.shutdownNow();
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

		RotatedTileBox requested = tileBox.copy();
		executor.execute(() -> {
			try {
				MapRenderRepositories renderer = app.getResourceManager().getRenderer();
				// The RoadCrew navigation map is intentionally dark. Reviews use the classic day style.
				renderer.loadMap(requested, app.getResourceManager().getMapTileDownloader(), false);
				Bitmap copy = copy(renderer.getBitmap());
				app.runInUIThread(() -> {
					if (copy != null && !cancelled) { completed.accept(new Result(copy, requested)); }
					else if (copy != null) { copy.recycle(); }
				});
			} catch (RuntimeException e) {
				Log.w("RoadCrewTripMap", "Classic map rendering failed", e);
			} finally {
				executor.shutdown();
			}
		});
	}

	@Nullable
	private Bitmap copy(@Nullable Bitmap bitmap) {
		if (bitmap == null || bitmap.isRecycled()) { return null; }
		Bitmap.Config config = bitmap.getConfig() == null ? Bitmap.Config.ARGB_8888 : bitmap.getConfig();
		return bitmap.copy(config, false);
	}
}
