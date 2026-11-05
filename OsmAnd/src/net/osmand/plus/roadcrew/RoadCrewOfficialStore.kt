package net.osmand.plus.roadcrew

import android.os.SystemClock
import android.util.AtomicFile
import net.osmand.PlatformUtil
import net.osmand.plus.OsmandApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RoadCrewOfficialStore(private val app: OsmandApplication, private val changed: Runnable) {
    private val log = PlatformUtil.getLog(RoadCrewOfficialStore::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private val disk = AtomicFile(File(app.cacheDir, "roadcrew-official.json"))
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile var features: List<RoadCrewOfficialData.Feature> = emptyList()
        private set
    @Volatile var failed = false
        private set
    @Volatile var manifestUntil = 0L
        private set
    @Volatile private var nextLook = 0L
    private var nextManifest = 0L
    private var manifestText: String? = null
    private var manifest: RoadCrewOfficialData.Manifest? = null
    private val tiles = LinkedHashMap<String, String>()
    private var displayedKeys: List<String>? = null

    init {
        executor.execute {
            try {
                if (disk.baseFile.length() in 1..64L * 1024 * 1024) {
                    val saved = JSONObject(String(disk.readFully(), Charsets.UTF_8))
                    val text = saved.getString("manifest")
                    val m = RoadCrewOfficialData.manifest(text, System.currentTimeMillis(), cached = true)
                    val data = saved.getJSONObject("tiles")
                    val restored = LinkedHashMap<String, RoadCrewOfficialData.Feature>()
                    for (key in data.keys()) {
                        require(key in m.tiles && key.startsWith("8/"))
                        val raw = data.getString(key)
                        RoadCrewOfficialData.tile(raw, m.version).forEach { restored[it.id] = it }
                        tiles[key] = raw
                    }
                    manifestText = text; manifest = m; manifestUntil = m.validUntil
                    features = restored.values.toList()
                    notifyChanged()
                }
            } catch (e: Exception) {
                tiles.clear()
                log.warn("RIA cache ignored: ${e.message}")
            }
        }
    }

    fun refresh(west: Double, south: Double, east: Double, north: Double) {
        val elapsed = SystemClock.elapsedRealtime()
        if (closed || elapsed < nextLook || !busy.compareAndSet(false, true)) return
        nextLook = elapsed + 2000
        executor.execute {
            try { load(west, south, east, north) }
            catch (e: Exception) {
                failed = true
                nextLook = SystemClock.elapsedRealtime() + 60_000
                log.warn("RIA refresh failed; keeping last good geometry: ${e.message}")
                notifyChanged()
            } finally { busy.set(false) }
        }
    }

    private fun load(west: Double, south: Double, east: Double, north: Double) {
        val now = System.currentTimeMillis()
        val due = manifest == null || SystemClock.elapsedRealtime() >= nextManifest
        val text = if (due) fetch("current.json", 512 * 1024) else manifestText!!
        val m = RoadCrewOfficialData.manifest(text, now)
        check(manifest == null || m.generatedAt >= manifest!!.generatedAt) { "Manifest moved backwards" }
        val keys = RoadCrewOfficialData.tilesFor(m, west, south, east, north)
        if (!due && keys == displayedKeys) return
        val reusable = if (manifest?.version == m.version) tiles else emptyMap()
        val loaded = LinkedHashMap<String, String>()
        val combined = LinkedHashMap<String, RoadCrewOfficialData.Feature>()
        // Sequential, bounded HTTP; only our immutable viewport tiles, no credentials.
        for (key in keys) {
            if (closed) return
            val raw = reusable[key] ?: fetch("${m.version}/$key.json", 24 * 1024 * 1024)
            RoadCrewOfficialData.tile(raw, m.version).forEach { combined[it.id] = it }
            loaded[key] = raw
        }
        if (closed) return
        val saved = JSONObject().put("manifest", text).put("tiles", JSONObject(loaded as Map<*, *>)).toString().toByteArray(Charsets.UTF_8)
        if (saved.size <= 64 * 1024 * 1024) {
            var stream: java.io.FileOutputStream? = null
            try { stream = disk.startWrite(); stream.write(saved); disk.finishWrite(stream) }
            catch (e: Exception) { disk.failWrite(stream); log.warn("RIA disk cache: ${e.message}") }
        }
        manifest = m; manifestText = text; manifestUntil = m.validUntil
        tiles.clear(); tiles.putAll(loaded)
        displayedKeys = keys; features = combined.values.toList(); failed = false
        log.info("RIA layer: version=${m.version.take(12)} tiles=${keys.size} parts=${features.size}")
        if (due) nextManifest = SystemClock.elapsedRealtime() + 5 * 60_000
        notifyChanged()
    }

    private fun fetch(path: String, maxBytes: Int): String {
        check(!closed)
        val c = URL(RoadCrewEndpoints.API_BASE_URL + "/map-data/official/" + path).openConnection() as HttpURLConnection
        connection = c
        c.instanceFollowRedirects = false
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        val deadline = SystemClock.elapsedRealtime() + 30_000
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    check(!closed && SystemClock.elapsedRealtime() < deadline) { "Request cancelled or timed out" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= maxBytes) { "Response too large" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
        } finally { c.disconnect(); connection = null }
    }

    private fun notifyChanged() { app.runInUIThread { if (!closed) changed.run() } }
    fun close() { closed = true; connection?.disconnect(); executor.shutdownNow() }
}
