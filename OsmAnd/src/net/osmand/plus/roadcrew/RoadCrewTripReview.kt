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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
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

/** Whole-course vehicle-use review; exact road checks are only queued when explicitly requested. */
internal class RoadCrewTripReview(
    activity: MapActivity, @JvmField val trip: RoadCrewTripJournal.Trip,
    private val safe: BooleanSupplier, confirm: Consumer<Boolean>, focus: Consumer<RoadCrewTripJournal.Row>
) {
    private val rows = trip.rows
    private var selected = 0
    private var refreshing = false
    private var busy = false
    private val content = RoadCrewUi.createPanel(activity, activity.getString(R.string.roadcrew_trip_review_title))
    private val count = RoadCrewUi.addBody(activity, content, "")
    private val map = JourneyMap(activity, rows)
    private val checkbox = CheckBox(activity)
    private val roadCheck = CheckBox(activity)
    private val consent = CheckBox(activity)
    private lateinit var save: Button
    @JvmField val dialog: AlertDialog = RoadCrewUi.createDialog(activity, content)

    init {
        (content.getChildAt(0) as TextView).textSize = 22f
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_trip_review_summary,
            time.format(Date(rows.first().record.observedAtBucketMillis)),
            time.format(Date(rows.last().record.observedAtBucketMillis)),
            java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                .format(rows.sumOf { it.record.segmentKey.lengthMeters } / 1000)))
        RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_trip_review_legend))
        content.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, RoadCrewUi.dp(activity, 240f)))
        val labels = rows.mapIndexed { i, row -> activity.getString(R.string.roadcrew_trip_review_section,
            i + 1, rows.size, time.format(Date(row.record.observedAtBucketMillis)),
            row.name.ifEmpty { activity.getString(R.string.roadcrew_validation_unnamed) }, row.record.segmentKey.lengthMeters.toInt()) }
        val spinner = Spinner(activity)
        spinner.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as TextView).setTextColor(RoadCrewUi.TEXT)
                    it.setSingleLine(false)
                    it.textSize = 14f
                    it.setPadding(RoadCrewUi.dp(activity, 8f), RoadCrewUi.dp(activity, 12f),
                        RoadCrewUi.dp(activity, 8f), RoadCrewUi.dp(activity, 12f))
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        content.addView(spinner, LinearLayout.LayoutParams(-1, -2))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Spinner's initial callback must not zoom the whole course into its first 100 m.
                if (selected != position) {
                    selected = position
                    map.focus(position)
                    focus.accept(rows[position])
                }
                refresh()
            }
        }
        checkbox.setText(R.string.roadcrew_trip_review_included)
        checkbox.setTextColor(RoadCrewUi.TEXT)
        checkbox.minHeight = RoadCrewUi.dp(activity, 48f)
        content.addView(checkbox)
        checkbox.setOnCheckedChangeListener { _, checked ->
            if (!refreshing && !busy && safe.asBoolean) {
                rows[selected].included = checked
                consent.isChecked = false
                refresh()
            }
        }
        roadCheck.setText(R.string.roadcrew_trip_review_request_check)
        roadCheck.setTextColor(RoadCrewUi.TEXT)
        roadCheck.minHeight = RoadCrewUi.dp(activity, 48f)
        content.addView(roadCheck)
        roadCheck.setOnCheckedChangeListener { _, checked ->
            if (!refreshing && !busy && safe.asBoolean) {
                rows[selected].question = checked && rows[selected].included
                consent.isChecked = false
            }
        }
        map.onSection = { index, toggle ->
            if (!busy && safe.asBoolean) {
                selected = index
                if (toggle) rows[index].included = !rows[index].included
                consent.isChecked = false
                spinner.setSelection(index)
                map.select(index)
                focus.accept(rows[index])
                refresh()
            }
        }
        button(activity, R.string.roadcrew_trip_review_overview, false) { map.overview() }
        button(activity, R.string.roadcrew_trip_review_before, false) {
            if (safe.asBoolean) { rows.take(selected).forEach { it.included = false }; consent.isChecked = false; refresh() }
        }
        button(activity, R.string.roadcrew_trip_review_after, false) {
            if (safe.asBoolean) { rows.drop(selected + 1).forEach { it.included = false }; consent.isChecked = false; refresh() }
        }
        consent.setText(R.string.roadcrew_trip_review_confirm)
        consent.setTextColor(RoadCrewUi.TEXT)
        content.addView(consent)
        RoadCrewUi.addBody(activity, content, activity.getString(R.string.roadcrew_trip_review_not_approval))
        save = button(activity, R.string.roadcrew_trip_review_save, true) {
            if (safe.asBoolean && consent.isChecked) confirm.accept(rows.none { it.included })
        }
        consent.setOnCheckedChangeListener { _, checked -> save.isEnabled = !busy && checked }
        button(activity, R.string.roadcrew_trip_review_discard, false) {
            if (safe.asBoolean) {
                rows.forEach { it.included = false }
                consent.isChecked = false
                refresh()
            }
        }
        button(activity, R.string.roadcrew_validation_later, false) { dialog.dismiss() }
        refresh()
        map.post { map.overview() }
    }

    fun selectedIds(): LongArray = rows.filter { it.included }.map { it.seq }.toLongArray()
    fun questionIds(): LongArray = rows.filter { it.included && it.question }.map { it.seq }.toLongArray()

    fun setMapContext(seq: Long, data: RoadCrewValidationMapView.MapData?) { map.setContext(seq, data) }

    fun disableActions() {
        busy = true
        fun disable(view: View) {
            view.isEnabled = false
            if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
        }
        disable(content)
    }

    private fun refresh() {
        refreshing = true
        rows.filter { !it.included }.forEach { it.question = false }
        checkbox.isChecked = rows[selected].included
        roadCheck.isEnabled = !busy && rows[selected].included
        roadCheck.isChecked = rows[selected].question
        val empty = rows.none { it.included }
        consent.setText(if (empty) R.string.roadcrew_trip_review_discard_confirm else R.string.roadcrew_trip_review_confirm)
        if (::save.isInitialized) {
            save.setText(if (empty) R.string.roadcrew_trip_review_discard else R.string.roadcrew_trip_review_save)
            save.isEnabled = !busy && consent.isChecked
        }
        count.text = content.context.getString(R.string.roadcrew_trip_review_counts, rows.count { it.included }, rows.size)
        map.invalidate()
        refreshing = false
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
    var onSection: (Int, Boolean) -> Unit = { _, _ -> }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerLat = rows.map { it.record.segmentKey.fromLatitude }.average()
    private val centerLon = rows.map { it.record.segmentKey.fromLongitude }.average()
    private val longitudeScale = 111320 * cos(Math.toRadians(centerLat))
    private val lines = rows.map { row ->
        val json = JSONArray(row.geometry)
        List(json.length()) { i ->
            val point = json.getJSONArray(i)
            PointF(((point.getDouble(1) - centerLon) * longitudeScale).toFloat(),
                ((centerLat - point.getDouble(0)) * 111320).toFloat())
        }
    }
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var selected = -1
    private var contextRoads: List<Pair<List<PointF>, String>> = emptyList()
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
        setBackgroundColor(0xff131d20.toInt())
        contentDescription = context.getString(R.string.roadcrew_trip_review_map)
    }

    fun overview() { fit(lines.flatten()); selected = -1; contextRoads = emptyList(); invalidate() }
    fun focus(index: Int) { selected = index; contextRoads = emptyList(); fit(lines[index]); invalidate() }
    fun select(index: Int) { selected = index; contextRoads = emptyList(); invalidate() }

    fun setContext(seq: Long, data: RoadCrewValidationMapView.MapData?) {
        if (selected < 0 || rows[selected].seq != seq || data == null) return
        contextRoads = data.roads.map { road ->
            List(road.pointsLength) { i -> PointF(
                ((MapUtils.get31LongitudeX(road.getPoint31XTile(i)) - centerLon) * longitudeScale).toFloat(),
                ((centerLat - MapUtils.get31LatitudeY(road.getPoint31YTile(i))) * 111320).toFloat())
            } to (road.name ?: "")
        }
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { overview() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(1.5f)
        paint.color = 0xff40545a.toInt()
        for ((points, _) in contextRoads) canvas.drawPath(path(points), paint)
        for ((index, line) in lines.withIndex()) {
            val path = path(line)
            if (index == selected) { paint.color = RoadCrewUi.TEXT; paint.strokeWidth = dp(10f); canvas.drawPath(path, paint) }
            paint.color = if (rows[index].included) RoadCrewUi.PRIMARY else RoadCrewUi.DANGER
            paint.strokeWidth = dp(6f)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textSize = dp(12f)
        paint.color = RoadCrewUi.TEXT
        val labels = ArrayList<RectF>()
        for ((points, name) in contextRoads) {
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
        if (lines.isNotEmpty()) {
            marker(canvas, lines.first().first(), context.getString(R.string.roadcrew_trip_review_start))
            marker(canvas, lines.last().last(), context.getString(R.string.roadcrew_trip_review_end))
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
        paint.color = RoadCrewUi.TEXT
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
                if (!moved) { pick(event.x, event.y); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun pick(x: Float, y: Float) {
        val distances = lines.mapIndexed { index, points ->
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
}
