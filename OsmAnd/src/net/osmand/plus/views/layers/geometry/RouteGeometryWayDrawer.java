package net.osmand.plus.views.layers.geometry;

import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.core.jni.QListVectorLine;
import net.osmand.core.jni.VectorLine;
import net.osmand.core.jni.VectorLinesCollection;
import net.osmand.plus.utils.NativeUtilities;

import java.util.List;

/** Route-only visual extensions that keep a single native VectorLine. */
public class RouteGeometryWayDrawer extends MultiColoringGeometryWayDrawer<RouteGeometryWayContext> {

	@Nullable
	private Integer customOutlineColor;
	private float customOutlineWidth;
	@NonNull
	private final Paint customOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	public RouteGeometryWayDrawer(@NonNull RouteGeometryWayContext context) {
		super(context);
		customOutlinePaint.setStyle(Paint.Style.STROKE);
		customOutlinePaint.setStrokeCap(Paint.Cap.ROUND);
		customOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
	}

	public void setCustomOutline(@Nullable @ColorInt Integer color, float width) {
		customOutlineColor = color;
		customOutlineWidth = color == null ? 0 : width;
		if (color != null) {
			customOutlinePaint.setColor(color);
			customOutlinePaint.setStrokeWidth(width);
		}
	}

	@Override
	protected void drawVectorLine(@NonNull VectorLinesCollection collection,
	                              int lineId, int baseOrder, boolean shouldDrawArrows,
	                              boolean approximationEnabled, @NonNull GeometryWayStyle<?> style,
	                              @NonNull List<DrawPathData31> pathsData) {
		super.drawVectorLine(collection, lineId, baseOrder, shouldDrawArrows,
				approximationEnabled, style, pathsData);
		if (customOutlineColor == null || coloringType.isGradient()) {
			return;
		}

		float scale = getVectorLineScale(getContext().getApp());
		QListVectorLine lines = collection.getLines();
		for (int i = 0; i < lines.size(); i++) {
			VectorLine line = lines.get(i);
			if (line.getLineId() == lineId) {
				line.setOutlineWidth(customOutlineWidth * scale);
				line.setOutlineColor(NativeUtilities.createFColorARGB(customOutlineColor));
				break;
			}
		}
	}

	@Override
	public void drawPath(@NonNull Canvas canvas, @NonNull DrawPathData pathData) {
		if (customOutlineColor != null && !coloringType.isGradient()
				&& pathData.style != null && pathData.style.color != 0) {
			canvas.drawPath(pathData.path, customOutlinePaint);
		}
		super.drawPath(canvas, pathData);
	}
}
