package net.osmand.plus.roadcrew

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import net.osmand.data.RotatedTileBox
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R
import net.osmand.plus.activities.MapActivity
import java.text.DateFormat
import java.util.Date
import java.util.function.Supplier
import kotlin.math.*

class RoadCrewOfficialController(private val app: OsmandApplication,
                                 private val activity: Supplier<MapActivity?>,
                                 private val changed: Runnable) {
    private val store = RoadCrewOfficialStore(app, changed)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val density = app.resources.displayMetrics.density
    private val staleDash = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (activity.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) changed.run()
            handler.postDelayed(this, 30_000)
        }
    }
    init { handler.postDelayed(tick, 30_000) }

    fun draw(canvas: Canvas, box: RotatedTileBox) {
        if (!RoadCrewSettings.showOfficial(app).get() || box.zoom < 5) return
        val bounds = box.latLonBounds
        store.refresh(bounds.left, bounds.bottom, bounds.right, bounds.top)
        val now = System.currentTimeMillis()
        paint.strokeWidth = 4 * density
        for (f in store.features) {
            if (!f.active(now) || f.west > bounds.right || f.east < bounds.left || f.south > bounds.top || f.north < bounds.bottom) continue
            paint.color = if (f.detour) Color.rgb(30, 125, 222) else Color.rgb(205, 55, 48)
            paint.pathEffect = if (f.stale(now) || store.failed || now >= store.manifestUntil) staleDash else null
            path.reset()
            var previousX = Float.NaN; var previousY = Float.NaN
            for ((index, point) in f.points.withIndex()) {
                val x = box.getPixXFromLatLon(point.lat, point.lon); val y = box.getPixYFromLatLon(point.lat, point.lon)
                if (index == 0) path.moveTo(x, y)
                else if (index == f.points.lastIndex || abs(x - previousX) + abs(y - previousY) >= 2) path.lineTo(x, y)
                else continue
                previousX = x; previousY = y
            }
            canvas.drawPath(path, paint)
        }
    }

    fun tap(point: PointF, box: RotatedTileBox): Boolean {
        if (!RoadCrewSettings.showOfficial(app).get() || box.zoom < 5) return false
        val a = activity.get() ?: return false
        val now = System.currentTimeMillis()
        var closest: RoadCrewOfficialData.Feature? = null
        var best = (18 * app.resources.displayMetrics.density).toDouble().pow(2)
        for (f in store.features) {
            if (!f.active(now)) continue
            for (i in 1 until f.points.size) {
                val from = f.points[i - 1]; val to = f.points[i]
                val x = box.getPixXFromLatLon(from.lat, from.lon).toDouble(); val y = box.getPixYFromLatLon(from.lat, from.lon).toDouble()
                val dx = box.getPixXFromLatLon(to.lat, to.lon) - x; val dy = box.getPixYFromLatLon(to.lat, to.lon) - y
                val length = dx * dx + dy * dy
                val t = if (length == 0.0) 0.0 else (((point.x - x) * dx + (point.y - y) * dy) / length).coerceIn(0.0, 1.0)
                val distance = (point.x - x - t * dx).pow(2) + (point.y - y - t * dy).pow(2)
                if (distance < best) { best = distance; closest = f }
            }
        }
        val f = closest ?: return false
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val text = buildString {
            if (f.stale(now) || store.failed || now >= store.manifestUntil) append(a.getString(R.string.roadcrew_official_stale)).append("\n\n")
            append(f.descriptions.joinToString("\n\n"))
            append("\n\n").append(a.getString(R.string.roadcrew_official_source_time, date.format(Date(f.publicationTime))))
            if (f.expiresAt != null && f.expiresAt < 4102444800000L) append("\n").append(a.getString(R.string.roadcrew_official_until, date.format(Date(f.expiresAt))))
        }
        AlertDialog.Builder(a).setTitle(a.getString(if (f.detour) R.string.roadcrew_official_detour else R.string.roadcrew_official_restriction) + " " + f.name)
            .setMessage(text).setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.roadcrew_official_source) { _, _ -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://traffic.api.bg/"))) }
            .show()
        return true
    }
    fun close() { handler.removeCallbacks(tick); store.close() }
}
