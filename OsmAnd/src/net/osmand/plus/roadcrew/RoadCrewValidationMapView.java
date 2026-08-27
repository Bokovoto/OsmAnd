package net.osmand.plus.roadcrew;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;

import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.resources.BinaryMapReaderResource;
import net.osmand.plus.resources.ResourceManager.BinaryMapReaderResourceType;
import net.osmand.router.RoadCrewObfSegmentLoader;
import net.osmand.router.RoadCrewSegmentIdentity;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Offline road geometry only; an unresolved segment is never drawn as a guessed straight line. */
final class RoadCrewValidationMapView extends View {

	static final class MapData {
		final List<RouteDataObject> roads;
		final RouteDataObject selected;
		final RoadCrewSegmentIdentity.Resolution resolution;
		final String roadName;

		MapData(List<RouteDataObject> roads, RouteDataObject selected,
				RoadCrewSegmentIdentity.Resolution resolution) {
			this.roads = roads;
			this.selected = selected;
			this.resolution = resolution;
			String name = selected.getName();
			String ref = selected.getRef("", false, resolution.getEndPointIndex() > resolution.getStartPointIndex());
			roadName = name == null || name.isEmpty() ? (ref == null ? "" : ref) : name;
		}
	}

	static MapData load(OsmandApplication app, RoadCrewValidationApi.Question question,
			BooleanSupplier cancelled) throws IOException {
		List<BinaryMapIndexReader> readers = new ArrayList<>();
		for (BinaryMapReaderResource resource : app.getResourceManager().getFileReaders()) {
			if (resource.isUseForRouting()) {
				BinaryMapIndexReader reader = resource.getReader(BinaryMapReaderResourceType.ROADCREW_VALIDATION_MAP, false);
				if (reader != null && reader.containsRouteData()) {
					readers.add(reader);
				}
			}
		}
		RoadCrewSegmentIdentity.SegmentKey key = question.key;
		RoadCrewObfSegmentLoader.LoadResult result = RoadCrewObfSegmentLoader.load(
				readers.toArray(new BinaryMapIndexReader[0]),
				(key.getFromLatitude() + key.getToLatitude()) / 2,
				(key.getFromLongitude() + key.getToLongitude()) / 2,
				Math.min(4000, Math.max(600, key.getLengthMeters() + 200)), 6000, cancelled::getAsBoolean);
		if (result.isCancelled() || result.isTruncated()) {
			return null;
		}
		RoadCrewSegmentIdentity.Resolution resolution = RoadCrewSegmentIdentity.resolve(key, result.getRouteObjects());
		if (resolution.getStatus() != RoadCrewSegmentIdentity.Status.EXACT) {
			return null;
		}
		for (RouteDataObject road : result.getRouteObjects()) {
			if (road.getId() == resolution.getRoadId()) {
				return new MapData(result.getRouteObjects(), road, resolution);
			}
		}
		return null;
	}

	private final MapData data;
	private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final double centerLat;
	private final double centerLon;
	private final double longitudeScale;
	private final double spanX;
	private final double spanY;
	private float scale;

	RoadCrewValidationMapView(Context context, MapData data) {
		super(context);
		this.data = data;
		int start = Math.min(data.resolution.getStartPointIndex(), data.resolution.getEndPointIndex());
		int end = Math.max(data.resolution.getStartPointIndex(), data.resolution.getEndPointIndex());
		double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
		for (int i = start; i <= end; i++) {
			double lat = MapUtils.get31LatitudeY(data.selected.getPoint31YTile(i));
			double lon = MapUtils.get31LongitudeX(data.selected.getPoint31XTile(i));
			minLat = Math.min(minLat, lat);
			maxLat = Math.max(maxLat, lat);
			minLon = Math.min(minLon, lon);
			maxLon = Math.max(maxLon, lon);
		}
		centerLat = (minLat + maxLat) / 2;
		centerLon = (minLon + maxLon) / 2;
		longitudeScale = 111320 * Math.cos(Math.toRadians(centerLat));
		spanX = Math.max(180, (maxLon - minLon) * longitudeScale);
		spanY = Math.max(180, (maxLat - minLat) * 111320);
		setContentDescription(context.getString(R.string.roadcrew_validation_map_description));
		setBackgroundColor(0xff131d20);
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		int width = MeasureSpec.getSize(widthSpec);
		setMeasuredDimension(width, Math.min(RoadCrewUi.dp(getContext(), 250), width * 2 / 3));
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		float padding = RoadCrewUi.dp(getContext(), 30);
		scale = (float) Math.min((getWidth() - padding * 2) / spanX, (getHeight() - padding * 2) / spanY);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeJoin(Paint.Join.ROUND);
		paint.setStrokeWidth(RoadCrewUi.dp(getContext(), 2));
		paint.setColor(0xff40545a);
		for (RouteDataObject road : data.roads) {
			canvas.drawPath(path(road, 0, road.getPointsLength() - 1), paint);
		}
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(0xffafc4c7);
		paint.setTextSize(RoadCrewUi.dp(getContext(), 11));
		List<RectF> labels = new ArrayList<>();
		for (RouteDataObject road : data.roads) {
			String name = road.getName();
			if (road == data.selected || name == null || name.isEmpty() || name.length() > 30) { continue; }
			int point = road.getPointsLength() / 2;
			float x = (float) ((MapUtils.get31LongitudeX(road.getPoint31XTile(point)) - centerLon) * longitudeScale * scale) + getWidth() / 2f;
			float y = (float) ((centerLat - MapUtils.get31LatitudeY(road.getPoint31YTile(point))) * 111320 * scale) + getHeight() / 2f;
			RectF bounds = new RectF(x, y - paint.getTextSize(), x + paint.measureText(name), y + 4);
			if (bounds.left < 12 || bounds.top < 30 || bounds.right > getWidth() - 12 || bounds.bottom > getHeight() - 25) { continue; }
			boolean overlap = false;
			for (RectF previous : labels) { if (RectF.intersects(bounds, previous)) { overlap = true; break; } }
			if (!overlap) { canvas.drawText(name, x, y, paint); bounds.inset(-8, -6); labels.add(bounds); }
			if (labels.size() >= 5) { break; }
		}
		Path selected = path(data.selected, data.resolution.getStartPointIndex(), data.resolution.getEndPointIndex());
		paint.setStyle(Paint.Style.STROKE);
		paint.setColor(0x402ad899);
		paint.setStrokeWidth(RoadCrewUi.dp(getContext(), 16));
		canvas.drawPath(selected, paint);
		paint.setColor(0xff29d99a);
		paint.setStrokeWidth(RoadCrewUi.dp(getContext(), 6));
		canvas.drawPath(selected, paint);
		PathMeasure measure = new PathMeasure(selected, false);
		float[] position = new float[2], tangent = new float[2];
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(0xfff1fff8);
		for (float f : new float[] {0, 0.5f, 1}) {
			measure.getPosTan(measure.getLength() * f, position, tangent);
			if (f != 0.5f) {
				canvas.drawCircle(position[0], position[1], RoadCrewUi.dp(getContext(), 5), paint);
			} else {
				canvas.save();
				canvas.translate(position[0], position[1]);
				canvas.rotate((float) Math.toDegrees(Math.atan2(tangent[1], tangent[0])));
				float size = RoadCrewUi.dp(getContext(), 10);
				Path arrow = new Path();
				arrow.moveTo(size, 0);
				arrow.lineTo(-size, -size * 0.65f);
				arrow.lineTo(-size * 0.5f, 0);
				arrow.lineTo(-size, size * 0.65f);
				arrow.close();
				canvas.drawPath(arrow, paint);
				canvas.restore();
			}
		}
		paint.setTextSize(RoadCrewUi.dp(getContext(), 12));
		canvas.drawText("N", RoadCrewUi.dp(getContext(), 12), RoadCrewUi.dp(getContext(), 20), paint);
		String attribution = "\u00a9 OpenStreetMap";
		canvas.drawText(attribution, getWidth() - paint.measureText(attribution) - 8, getHeight() - 8, paint);
	}

	private Path path(RouteDataObject road, int from, int to) {
		Path path = new Path();
		int step = from <= to ? 1 : -1;
		for (int i = from; ; i += step) {
			float x = (float) ((MapUtils.get31LongitudeX(road.getPoint31XTile(i)) - centerLon) * longitudeScale * scale) + getWidth() / 2f;
			float y = (float) ((centerLat - MapUtils.get31LatitudeY(road.getPoint31YTile(i))) * 111320 * scale) + getHeight() / 2f;
			if (i == from) { path.moveTo(x, y); } else { path.lineTo(x, y); }
			if (i == to) { break; }
		}
		return path;
	}
}
