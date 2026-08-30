package net.osmand.plus.roadcrew

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import net.osmand.util.MapUtils
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/** Simple whole-course review. A rejected course never enters positive truck evidence. */
internal class RoadCrewTripReview(
    activity: MapActivity, @JvmField val trip: RoadCrewTripJournal.Trip,
    private val safe: BooleanSupplier, confirm: Consumer<Boolean>, focus: Consumer<RoadCrewTripJournal.Row>
) {
    private val rows = trip.rows
    private var busy = false
    private val content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_trip_review_title))
    private val map = JourneyMap(activity, rows)
    @JvmField val dialog: AlertDialog = RoadCrewUi.createDialog(activity, content)

    init {
        (content.getChildAt(0) as TextView).textSize = 22f
        rows.forEach { it.included = true; it.question = false }
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_trip_review_summary,
            time.format(Date(rows.first().record.observedAtBucketMillis)),
            time.format(Date(rows.last().record.observedAtBucketMillis)),
            java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                .format(rows.sumOf { it.record.segmentKey.lengthMeters } / 1000)))
        RoadCrewUi.addSectionTitle(activity, content,
            activity.getString(R.string.roadcrew_trip_review_simple_question))
        content.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, RoadCrewUi.dp(activity, 240f)))
        map.sectionSelectionEnabled = false
        button(activity, R.string.roadcrew_trip_review_save, true) {
            if (safe.asBoolean) {
                rows.forEach { it.included = true; it.question = false }
                confirm.accept(false)
            }
        }
        button(activity, R.string.roadcrew_trip_review_discard, false) {
            if (safe.asBoolean) {
                rows.forEach { it.included = false; it.question = false }
                confirm.accept(true)
            }
        }
        map.post {
            map.overview()
            focus.accept(rows.first())
        }
    }

    fun selectedIds(): LongArray = rows.filter { it.included }.map { it.seq }.toLongArray()
    fun questionIds(): LongArray = longArrayOf()

    fun setMapContext(seq: Long, data: RoadCrewValidationMapView.MapData?) { map.setContext(seq, data) }

    fun disableActions() {
        busy = true
        fun disable(view: View) {
            view.isEnabled = false
            if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
        }
        disable(content)
    }

    private fun button(activity: MapActivity, resource: Int, primary: Boolean, action: () -> Unit): Button =
        RoadCrewUi.addFullWidthButton(activity, content, activity.getString(resource), primary) { if (!busy) action() }.apply {
            setSingleLine(false)
            minHeight = RoadCrewUi.dp(activity, 48f)
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
}

/** Exact OBF road geometry captured with each observation; never joins gaps with invented roads. */
private class JourneyMap(context: Context, private val rows: List<RoadCrewTripJournal.Row>) : View(context) {
    private data class GeoPoint(val latitude: Double, val longitude: Double)
    var onSection: (Int, Boolean) -> Unit = { _, _ -> }
    var sectionSelectionEnabled = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerLat = rows.map { it.record.segmentKey.fromLatitude }.average()
    private val centerLon = rows.map { it.record.segmentKey.fromLongitude }.average()
    private val longitudeScale = 111320 * cos(Math.toRadians(centerLat))
    private val geoLines = rows.map { row ->
        val json = JSONArray(row.geometry)
        List(json.length()) { i ->
            val point = json.getJSONArray(i)
            GeoPoint(point.getDouble(0), point.getDouble(1))
        }
    }
    private val lines = geoLines.map { line -> line.map { point ->
        PointF(((point.longitude - centerLon) * longitudeScale).toFloat(),
            ((centerLat - point.latitude) * 111320).toFloat())
    } }
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var selected = -1
    private var contextRoads: List<Pair<List<GeoPoint>, String>> = emptyList()
    private val contextCache = object : LinkedHashMap<Long, List<Pair<List<GeoPoint>, String>>>(
        MAX_CONTEXT_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, List<Pair<List<GeoPoint>, String>>>?
        ): Boolean = size > MAX_CONTEXT_CACHE_SIZE
    }
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(0.0001f, 10f)
            val ratio = next / zoom
            offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
            offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
            zoom = next
            moved = true
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(0xffecebe4.toInt())
        contentDescription = context.getString(R.string.roadcrew_trip_review_map)
    }

    fun overview() {
        selected = -1
        contextRoads = contextCache.values.flatten()
        fit(lines.flatten())
        invalidate()
    }
    fun focus(index: Int) {
        selected = index
        contextRoads = contextCache[rows[index].seq] ?: emptyList()
        fit(lines[index])
        invalidate()
    }
    fun select(index: Int) { focus(index) }

    fun setContext(seq: Long, data: RoadCrewValidationMapView.MapData?) {
        if (data == null) return
        val roads = data.roads.map { road ->
            List(road.pointsLength) { i -> GeoPoint(
                MapUtils.get31LatitudeY(road.getPoint31YTile(i)),
                MapUtils.get31LongitudeX(road.getPoint31XTile(i)))
            } to (road.name ?: "")
        }
        contextCache[seq] = roads
        if (selected >= 0 && rows[selected].seq == seq) contextRoads = roads
        else if (selected < 0) contextRoads = contextCache.values.flatten()
        invalidate()
    }

    private fun fit(points: List<PointF>) {
        if (width == 0 || points.isEmpty()) return
        val left = points.minOf { it.x }; val right = points.maxOf { it.x }
        val top = points.minOf { it.y }; val bottom = points.maxOf { it.y }
        val pad = dp(32f)
        zoom = min((width - pad * 2) / (right - left).coerceAtLeast(180f),
            (height - pad * 2) / (bottom - top).coerceAtLeast(180f)).coerceAtLeast(0.0001f)
        offsetX = width / 2f - (left + right) * zoom / 2
        offsetY = height / 2f - (top + bottom) * zoom / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        overview()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val displayedLines = lines
        val displayedContext = contextRoads.map { (points, name) ->
            points.map { point -> PointF(((point.longitude - centerLon) * longitudeScale).toFloat(),
                ((centerLat - point.latitude) * 111320).toFloat()) } to name
        }
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(4f)
        paint.color = 0xffaeb3b0.toInt()
        for ((points, _) in displayedContext) canvas.drawPath(path(points), paint)
        paint.strokeWidth = dp(2f)
        paint.color = 0xffffffff.toInt()
        for ((points, _) in displayedContext) canvas.drawPath(path(points), paint)
        for ((index, line) in displayedLines.withIndex()) {
            val path = path(line)
            if (index == selected) { paint.color = 0xff243238.toInt(); paint.strokeWidth = dp(10f); canvas.drawPath(path, paint) }
            paint.color = if (rows[index].included) RoadCrewUi.PRIMARY else RoadCrewUi.DANGER
            paint.strokeWidth = dp(6f)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textSize = dp(12f)
        paint.color = 0xff263238.toInt()
        val labels = ArrayList<RectF>()
        for ((points, name) in displayedContext) {
            if (name.isEmpty() || name.length > 35 || points.isEmpty()) continue
            val p = points[points.size / 2]
            val x = p.x * zoom + offsetX; val y = p.y * zoom + offsetY
            val bounds = RectF(x, y - paint.textSize, x + paint.measureText(name), y + dp(4f))
            if (bounds.left < dp(12f) || bounds.right > width - dp(12f) || bounds.top < dp(30f)
                || bounds.bottom > height - dp(28f) || labels.any { RectF.intersects(it, bounds) }) continue
            canvas.drawText(name, x, y, paint)
            bounds.inset(-dp(8f), -dp(6f))
            labels.add(bounds)
            if (labels.size == 5) break
        }
        canvas.drawText("N", dp(12f), dp(18f), paint)
        val attribution = "© OpenStreetMap"
        canvas.drawText(attribution, width - paint.measureText(attribution) - dp(8f), height - dp(8f), paint)
        if (displayedLines.isNotEmpty()) {
            marker(canvas, displayedLines.first().first(), context.getString(R.string.roadcrew_trip_review_start))
            marker(canvas, displayedLines.last().last(), context.getString(R.string.roadcrew_trip_review_end))
        }
    }

    private fun path(points: List<PointF>): Path = Path().apply {
        points.forEachIndexed { i, p ->
            val x = p.x * zoom + offsetX; val y = p.y * zoom + offsetY
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    private fun marker(canvas: Canvas, p: PointF, label: String) {
        val x = p.x * zoom + offsetX; val y = p.y * zoom + offsetY
        if (x < 0 || y < 0 || x > width || y > height) return
        paint.color = 0xff263238.toInt()
        canvas.drawCircle(x, y, dp(4f), paint)
        canvas.drawText(label, (x + dp(8f)).coerceIn(dp(6f), (width - paint.measureText(label) - dp(6f)).coerceAtLeast(dp(6f))),
            (y - dp(8f)).coerceIn(dp(20f), height - dp(24f)), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y; moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> moved = true
            MotionEvent.ACTION_MOVE -> {
                if (!scaler.isInProgress && event.pointerCount == 1) {
                    if (hypot(event.x - downX, event.y - downY) > dp(8f)) moved = true
                    if (moved) { offsetX += event.x - lastX; offsetY += event.y - lastY; invalidate() }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (!moved) {
                    if (sectionSelectionEnabled) pick(event.x, event.y)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun pick(x: Float, y: Float) {
        val displayedLines = lines
        val distances = displayedLines.mapIndexed { index, points ->
            val distance = points.zipWithNext { a, b ->
                val ax = a.x * zoom + offsetX; val ay = a.y * zoom + offsetY
                val dx = (b.x - a.x) * zoom; val dy = (b.y - a.y) * zoom
                val fraction = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                hypot(x - ax - fraction * dx, y - ay - fraction * dy)
            }.minOrNull() ?: Float.MAX_VALUE
            index to distance
        }.sortedBy { it.second }
        val first = distances.firstOrNull() ?: return
        if (first.second > dp(20f)) return
        val ambiguous = distances.size > 1 && abs(distances[1].second - first.second) < dp(3f)
        if (ambiguous) Toast.makeText(context, R.string.roadcrew_trip_review_ambiguous, Toast.LENGTH_SHORT).show()
        selected = first.first
        onSection(selected, !ambiguous)
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val MAX_CONTEXT_CACHE_SIZE = 3
    }
}
