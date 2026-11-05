package net.osmand.plus.roadcrew

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Display-only contract shared with the website. No routing permissions. */
object RoadCrewOfficialData {
    data class Manifest(val version: String, val generatedAt: Long, val validUntil: Long, val tiles: Set<String>)
    data class Point(val lat: Double, val lon: Double)
    data class Feature(val id: String, val detour: Boolean, val name: String, val points: List<Point>,
                       val expiresAt: Long?, val publicationTime: Long, val staleAt: Long,
                       val sourceStale: Boolean, val descriptions: List<String>, val conditions: String) {
        val west = points.minOf { it.lon }
        val east = points.maxOf { it.lon }
        val south = points.minOf { it.lat }
        val north = points.maxOf { it.lat }
        fun active(now: Long) = expiresAt == null || now < expiresAt
        fun stale(now: Long) = sourceStale || now >= staleAt || now < publicationTime
    }

    private fun time(o: JSONObject, key: String): Long {
        val n = o.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() > 0) { "Invalid $key" }
        return n.toLong()
    }
    fun manifest(text: String, now: Long, cached: Boolean = false): Manifest {
        val o = JSONObject(text)
        require(o.getInt("schemaVersion") == 1 && o.getString("kind") == "official")
        val version = o.getString("version")
        require(version.matches(Regex("[a-f0-9]{64}")))
        val generated = time(o, "generatedAt")
        val until = time(o, "validUntil")
        require(generated <= now + 300_000 && until > generated && until - generated <= 3_600_000)
        require(cached || until > now) { "Expired manifest" }
        val a = o.getJSONArray("tiles")
        require(a.length() <= 10000)
        val keys = LinkedHashSet<String>()
        for (i in 0 until a.length()) {
            val key = a.getString(i)
            require(key.matches(Regex("[0-8]/[0-9]+/[0-9]+")))
            val (z, x, y) = key.split('/').map { it.toInt() }
            require(x in 0 until (1 shl z) && y in 0 until (1 shl z) && keys.add(key))
        }
        return Manifest(version, generated, until, keys)
    }

    // Select existing z8 tiles, never construct URLs from untrusted manifest paths.
    fun tilesFor(m: Manifest, west: Double, south: Double, east: Double, north: Double): List<String> {
        require(listOf(west, south, east, north).all { it.isFinite() } && south <= north)
        fun x(lon: Double) = ((lon.coerceIn(-180.0, 180.0) + 180) / 360 * 256).coerceIn(0.0, 255.99999)
        fun y(lat: Double): Double {
            val radians = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
            return ((1 - ln(tan(radians) + 1 / cos(radians)) / Math.PI) / 2 * 256).coerceIn(0.0, 255.99999)
        }
        val left = floor(x(west)).toInt(); val right = floor(x(east)).toInt()
        val top = floor(y(north)).toInt(); val bottom = floor(y(south)).toInt()
        return m.tiles.filter { key ->
            val p = key.split('/').map { it.toInt() }
            p[0] == 8 && p[2] in top..bottom && (if (west <= east) p[1] in left..right else p[1] >= left || p[1] <= right)
        }.sorted()
    }

    fun tile(text: String, version: String): List<Feature> {
        val o = JSONObject(text)
        require(o.getInt("schemaVersion") == 1 && o.getString("kind") == "official"
                && o.getString("type") == "FeatureCollection" && o.getString("version") == version)
        val array = o.getJSONArray("features")
        require(array.length() <= 10000)
        val ids = HashSet<String>(); var pointCount = 0
        return (0 until array.length()).map { i ->
            val f = array.getJSONObject(i); val p = f.getJSONObject("properties"); val official = p.getJSONObject("official")
            val id = p.getString("officialId")
            require(id.startsWith("ria:") && ids.add(id) && f.getString("id") == id)
            val kind = p.getString("kind")
            require(kind == "OFFICIAL_RESTRICTION" || kind == "OFFICIAL_DETOUR")
            require(official.getString("source") == "BG_RIA" && official.get("routingEligible") == false)
            val g = f.getJSONObject("geometry")
            require(g.getString("type") == "LineString")
            val coords = g.getJSONArray("coordinates")
            pointCount += coords.length()
            require(coords.length() >= 2 && pointCount <= 500000)
            val points = (0 until coords.length()).map { j ->
                val a = coords.getJSONArray(j); require(a.length() == 2)
                require(a.get(0) is Number && a.get(1) is Number)
                val lon = a.getDouble(0); val lat = a.getDouble(1)
                require(lon.isFinite() && lat.isFinite() && abs(lon) <= 180 && abs(lat) <= 90)
                Point(lat, lon)
            }
            val publication = time(official, "publicationTime"); val staleAt = time(official, "staleAt")
            require(staleAt >= publication && official.get("stale") is Boolean)
            val descriptions = official.getJSONArray("descriptions")
            Feature(id, kind == "OFFICIAL_DETOUR", p.optString("name"), points,
                if (p.isNull("expiresAt")) null else time(p, "expiresAt"), publication, staleAt,
                official.getBoolean("stale"), (0 until descriptions.length()).map { descriptions.getString(it) },
                official.getJSONArray("conditions").toString())
        }
    }
}
