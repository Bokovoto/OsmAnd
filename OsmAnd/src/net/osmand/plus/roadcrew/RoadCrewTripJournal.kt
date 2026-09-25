package net.osmand.plus.roadcrew

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.osmand.binary.RouteDataObject
import net.osmand.plus.OsmandApplication
import net.osmand.router.RoadCrewDirectObservation
import net.osmand.router.RoadCrewObservationOutbox
import net.osmand.router.RoadCrewPassageDetector
import net.osmand.router.RoadCrewSegmentIdentity
import net.osmand.router.RoadCrewTripLifecycle
import net.osmand.router.RoadCrewWayCanonical
import net.osmand.util.MapUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local-only review journal. Nothing here is eligible for upload until explicitly confirmed. */
internal class RoadCrewTripJournal private constructor(private val app: OsmandApplication) {
    private val helper = object : SQLiteOpenHelper(app,
        File(app.noBackupFilesDir, "roadcrew-trip-review.db").absolutePath, null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(TRIPS_SQL)
            db.execSQL(SECTIONS_SQL)
            db.execSQL("CREATE INDEX trip_review_pending ON sections(state, seq)")
            db.execSQL(DIRECT_SECTIONS_SQL)
            db.execSQL("CREATE INDEX trip_review_direct_pending ON direct_sections(state, seq)")
            db.execSQL(WAY_DESCRIPTORS_SQL)
            db.execSQL("CREATE TABLE consent_generation (value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE trips ADD COLUMN auto_review INTEGER NOT NULL DEFAULT 0 CHECK(auto_review IN (0,1))")
                db.execSQL("ALTER TABLE trips ADD COLUMN prompted INTEGER NOT NULL DEFAULT 0 CHECK(prompted IN (0,1))")
                db.execSQL("ALTER TABLE trips ADD COLUMN ended_at INTEGER NOT NULL DEFAULT 0")
                // Version 1 picked arbitrary start/middle/end questions, not explicit driver requests.
                db.execSQL("UPDATE sections SET question = 0")
            }
            if (oldVersion < 4) {
                // The shape of a way, as this phone's map draws it. The server
                // asks for it once per geometry and gets the length from it;
                // without it every cell waits on a free service that is
                // currently refusing (21.09).
                db.execSQL(WAY_DESCRIPTORS_SQL)
            }
            if (oldVersion < 3) {
                // The directed observations of a course, kept beside the old
                // ones until the old identity is retired. They travel on the
                // same yes: nothing here is uploaded before the driver confirms.
                db.execSQL(DIRECT_SECTIONS_SQL)
                db.execSQL("CREATE INDEX trip_review_direct_pending ON direct_sections(state, seq)")
            }
        }
    }
    private var initialized = false
    private var activeTrip: String? = null
    private var lastPassageAt = 0L
    private val lifecycle = RoadCrewTripLifecycle()

    private fun database(): SQLiteDatabase {
        val db = helper.writableDatabase
        val generation = app.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getString("generation", "initial")!!
        val stored = db.rawQuery("SELECT value FROM consent_generation", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (stored != generation) {
            // A persisted revocation also survives process death before asynchronous cleanup.
            transaction(db) {
                // RCS2 rows and way shapes go too: they are the same drive (ROADMAP 323).
                for (table in REVOKED_TABLES.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                    db.delete(table, null, null)
                }
                db.delete("consent_generation", null, null)
                db.execSQL("INSERT INTO consent_generation(value) VALUES (?)", arrayOf(generation))
            }
            activeTrip = null
            lastPassageAt = 0
            updateSummary(db)
        }
        if (!initialized) {
            // A process restart is an uncertain boundary, never proof of continued truck use.
            db.execSQL("UPDATE trips SET closed = 1 WHERE closed = 0")
            initialized = true
            updateSummary(db)
        }
        return db
    }

    @Synchronized
    fun capture(evidence: RoadCrewPassageDetector.PassageEvidence, at: Long,
                road: RouteDataObject?, binding: RoadCrewSegmentIdentity.SegmentBinding) {
        if (!RoadCrewMapObservationConsent.isEnabled(app) || road == null) return
        val db = database()
        prune(db)
        if (count(db, "SELECT COUNT(*) FROM sections") >= MAX_SECTIONS) {
            updateSummary(db, true)
            return // Never evict an unreviewed trip to make space for more data.
        }
        openTrip(db, at)
        val record = RoadCrewObservationOutbox.Record.capture(evidence, at)
        val key = record.segmentKey
        val geometry = JSONArray()
        val step = if (binding.startPointIndex < binding.endPointIndex) 1 else -1
        var index = binding.startPointIndex
        while (true) {
            geometry.put(JSONArray().put(MapUtils.get31LatitudeY(road.getPoint31YTile(index)))
                .put(MapUtils.get31LongitudeX(road.getPoint31XTile(index))))
            if (index == binding.endPointIndex) break
            index += step
        }
        val values = ContentValues().apply {
            put("trip_id", activeTrip)
            put("observation_key", "${key.canonicalId}:${key.geometryFingerprint}:${record.observedAtBucketMillis}")
            put("bucket", record.observedAtBucketMillis)
            put("record", record.encode())
            put("geometry", geometry.toString())
            put("road_name", road.name?.takeIf { it.isNotEmpty() } ?: road.getRef("", false, step > 0) ?: "")
        }
        if (db.insertWithOnConflict("sections", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            val exists = db.rawQuery("SELECT 1 FROM sections WHERE trip_id = ? AND observation_key = ?",
                arrayOf(activeTrip!!, values.getAsString("observation_key"))).use { it.moveToFirst() }
            check(exists) { "Could not store trip section" }
        }
        lastPassageAt = at
        updateSummary(db)
    }

    /**
     * The directed (rcs2) observations of the course being recorded.
     *
     * They wait for the same yes as everything else here: a course nobody
     * confirmed is never uploaded. Held as the JSON the server accepts, so the
     * phone is not asked to rebuild a wire format it has already produced.
     */
    @Synchronized
    fun captureDirect(id: String, bucket: Long, json: String, at: Long, mayOpenCourse: Boolean): Boolean {
        if (!RoadCrewMapObservationConsent.isEnabled(app)) return false
        // Says whether it was stored. The caller counts the refusals, because a
        // silently dropped observation is what cost a whole day of guessing
        // about why the live path was empty (21.09).
        val db = database()
        prune(db)
        if (count(db, "SELECT COUNT(*) FROM direct_sections") >= MAX_SECTIONS) {
            updateSummary(db, true)
            return false // Never evict an unreviewed trip to make space for more data.
        }
        // RCS2 opens the course itself now that RCS1 is off (ROADMAP 323); the
        // caller says whether this is a truck recording that may start one.
        if (mayOpenCourse) openTrip(db, at)
        val trip = activeTrip ?: return false
        db.execSQL(
            "INSERT OR IGNORE INTO direct_sections(trip_id, observation_id, bucket, json)"
                + " VALUES (?, ?, ?, ?)", arrayOf(trip, id, bucket, json))
        lastPassageAt = at
        updateSummary(db)
        return true
    }

    private fun openTrip(db: SQLiteDatabase, at: Long) {
        if (lifecycle.shouldCloseForGap(lastPassageAt, at)) finish(false)
        if (activeTrip == null) {
            val id = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO trips(id, closed, reviewed, snooze_until) VALUES (?, 0, 0, 0)", arrayOf(id))
            activeTrip = id
        }
    }

    /** A way's shape as this phone's map draws it, kept until the server asks. */
    class WayDescriptor(
        @JvmField val osmWayId: String,
        @JvmField val algorithm: Int,
        @JvmField val fingerprint: String,
        @JvmField val mapVersion: String,
        @JvmField val points: String,
    )

    /**
     * Remembers the shape of a way this phone has just observed.
     *
     * The server verifies the identity against it and takes the way's length
     * from it - and a cell cannot be placed without that length. Until the
     * phone sends these, the server has to ask OpenStreetMap, which is a free
     * service answering 504 today and will not carry ten thousand phones.
     *
     * One row per geometry, not per passage: a thousand drives down one road
     * store it once.
     */
    @Synchronized
    fun rememberWayShape(
        osmWayId: String, algorithm: Int, fingerprint: String,
        mapVersion: String, points: String, now: Long,
    ) {
        if (!RoadCrewMapObservationConsent.isEnabled(app)) return
        if (osmWayId.isEmpty() || fingerprint.isEmpty() || points.isEmpty()) return
        val db = database()
        db.execSQL(
            "INSERT OR IGNORE INTO way_descriptors(osm_way_id, algorithm, fingerprint,"
                + " map_version, points, seen_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(osmWayId, algorithm, fingerprint, mapVersion, points, now))
    }

    /** The shapes the server asked for, as far as this phone still has them. */
    @Synchronized
    fun wayShapes(requested: List<WayDescriptor>): List<WayDescriptor> {
        if (requested.isEmpty()) return emptyList()
        val db = database()
        val found = ArrayList<WayDescriptor>()
        for (request in requested.take(MAX_DESCRIPTORS_PER_REPLY)) {
            db.rawQuery(
                "SELECT map_version, points FROM way_descriptors"
                    + " WHERE osm_way_id = ? AND algorithm = ? AND fingerprint = ?",
                arrayOf(request.osmWayId, request.algorithm.toString(), request.fingerprint)
            ).use { c ->
                if (c.moveToFirst()) {
                    found.add(WayDescriptor(request.osmWayId, request.algorithm,
                        request.fingerprint, c.getString(0), c.getString(1)))
                }
            }
        }
        return found
    }

    /** A confirmed, not yet uploaded directed observation. */
    class DirectRow(@JvmField val seq: Long, @JvmField val json: String)

    /** Confirmed and not yet uploaded, oldest first. */
    @Synchronized
    fun confirmedDirect(limit: Int = 500): List<DirectRow> {
        val db = database()
        val rows = ArrayList<DirectRow>()
        db.rawQuery("SELECT seq, json FROM direct_sections WHERE state = 'CONFIRMED'"
            + " ORDER BY seq LIMIT ?", arrayOf(limit.coerceIn(1, 1000).toString())).use { c ->
            while (c.moveToNext()) rows.add(DirectRow(c.getLong(0), c.getString(1)))
        }
        return rows
    }

    @Synchronized
    fun markDirectTransferred(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        val db = database()
        transaction(db) {
            for (seq in seqs) {
                db.execSQL("UPDATE direct_sections SET state = 'TRANSFERRED'"
                    + " WHERE seq = ? AND state = 'CONFIRMED'", arrayOf(seq))
            }
        }
    }

    @Synchronized
    fun navigationStarted() {
        if (!lifecycle.startNavigation()) return
        finish(false)
    }

    @Synchronized
    fun navigationFinished() {
        if (!lifecycle.endNavigation()) return
        finish(true)
    }

    @Synchronized
    fun collectionPaused() {
        if (!lifecycle.isNavigating) finish(false)
    }

    private fun finish(offerReview: Boolean) {
        val id = activeTrip ?: return
        val db = database()
        db.execSQL("UPDATE trips SET closed = 1, auto_review = ?, ended_at = ? WHERE id = ?",
            arrayOf(if (offerReview) 1 else 0, System.currentTimeMillis(), id))
        activeTrip = null
        lastPassageAt = 0
        updateSummary(db)
    }

    @Synchronized
    fun review(manual: Boolean): Trip? {
        val db = database()
        prune(db)
        updateSummary(db)
        val now = System.currentTimeMillis()
        val id = db.rawQuery(REVIEW_SQL, arrayOf(if (manual) "1" else "0", startOfDay(now).toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return null
        return reviewTrip(db, id)
    }

    @Synchronized
    fun pendingTrips(limit: Int = 20): List<PendingTrip> {
        val db = database()
        prune(db)
        updateSummary(db)
        val result = ArrayList<PendingTrip>()
        db.rawQuery(PENDING_TRIPS_SQL, arrayOf(limit.coerceIn(1, 50).toString())).use { c ->
            while (c.moveToNext()) result.add(PendingTrip(c.getString(0), c.getLong(1), c.getInt(2)))
        }
        return result
    }

    @Synchronized
    fun review(tripId: String): Trip? {
        val db = database()
        val available = db.rawQuery(REVIEW_TRIP_SQL, arrayOf(tripId)).use { it.moveToFirst() }
        return if (available) reviewTrip(db, tripId) else null
    }

    /**
     * The stretch the review draws for one RCS2 row, or null when it cannot be
     * drawn. The review and confirm() both ask this, so the answer is stamped
     * on exactly what the driver was shown (ROADMAP 317).
     */
    private fun drawnStretch(db: SQLiteDatabase, observation: JSONObject): DoubleArray? {
        try {
            val key = observation.getJSONObject("segmentKey")
            val from = key.getDouble("fromMeasureMeters")
            val to = key.getDouble("toMeasureMeters")
            val fingerprint = key.getString("geometryFingerprint")
            val algorithm = key.getInt("geometryFingerprintAlgorithm")
            if (!(to > from) || algorithm != RoadCrewWayCanonical.FINGERPRINT_ALGORITHM) return null
            val points = db.rawQuery("SELECT points FROM way_descriptors"
                + " WHERE osm_way_id = ? AND algorithm = ? AND fingerprint = ?",
                arrayOf(key.getString("osmWayId"), algorithm.toString(), fingerprint))
                .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return null
            val xs = points.getJSONArray("pointsX")
            val ys = points.getJSONArray("pointsY")
            val way = RoadCrewWayCanonical.canonicalise(
                IntArray(xs.length()) { xs.getInt(it) }, IntArray(ys.length()) { ys.getInt(it) })
            // Only on the geometry the measures were taken on; a shape that does
            // not match is not drawn, rather than guessed.
            if (RoadCrewWayCanonical.canonicalFingerprint(way) != fingerprint) return null
            return RoadCrewDirectObservation.stretchLatLon(way, from, to)
        } catch (error: Exception) {
            return null
        }
    }

    private fun reviewTrip(db: SQLiteDatabase, trip: String): Trip {
        val lines = ArrayList<DoubleArray>()
        var meters = 0.0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        db.rawQuery("SELECT json, bucket FROM direct_sections WHERE trip_id = ? AND state = 'STAGED' ORDER BY seq",
            arrayOf(trip)).use { cursor ->
            while (cursor.moveToNext()) {
                first = minOf(first, cursor.getLong(1))
                last = maxOf(last, cursor.getLong(1))
                val observation = try { JSONObject(cursor.getString(0)) } catch (error: Exception) { continue }
                val line = drawnStretch(db, observation) ?: continue
                // The number the driver confirms is what he is shown.
                val key = observation.getJSONObject("segmentKey")
                meters += key.getDouble("toMeasureMeters") - key.getDouble("fromMeasureMeters")
                lines.add(line)
            }
        }
        val rows = readRows(db, "trip_id = ? AND state = 'STAGED'", arrayOf(trip))
        // The time range comes from RCS2; an old course may still carry RCS1 rows.
        for (row in rows) {
            first = minOf(first, row.record.observedAtBucketMillis)
            last = maxOf(last, row.record.observedAtBucketMillis)
        }
        if (first > last) { first = 0; last = 0 }
        return Trip(trip, rows, lines, meters, first, last)
    }

    @Synchronized
    fun confirm(trip: String, selectedIds: LongArray, questionIds: LongArray, discardAll: Boolean,
                suitabilityConfirmed: Boolean) {
        check(RoadCrewMapObservationConsent.isEnabled(app)) { "Sharing is disabled" }
        val db = database()
        val rows = readRows(db, "trip_id = ? AND state = 'STAGED'", arrayOf(trip))
        val stagedDirect = db.rawQuery(
            "SELECT COUNT(*) FROM direct_sections WHERE trip_id = ? AND state = 'STAGED'", arrayOf(trip)
        ).use { it.moveToFirst(); it.getInt(0) }
        // A course is its RCS2 rows now (ROADMAP 323); RCS1 rows exist only on
        // courses recorded before this version.
        check((rows.isNotEmpty() || stagedDirect > 0) && activeTrip != trip) { "Trip is not available for review" }
        val requested = selectedIds.toSet()
        require(rows.map { it.seq }.containsAll(requested)) { "Review contains foreign sections" }
        val selected = if (discardAll) emptyList() else rows.filter { it.seq in requested }
        require(discardAll || rows.isEmpty() || selected.isNotEmpty()) { "No truck sections selected" }
        require(!suitabilityConfirmed || (!discardAll && selected.size == rows.size)) {
            "Suitability confirmation requires the whole displayed course"
        }
        val questions = questionIds.toSet()
        require(rows.map { it.seq }.containsAll(questions)) { "Review contains foreign questions" }
        transaction(db) {
            for (row in rows) {
                if (discardAll || row.seq !in requested) {
                    // Car/excluded geometry is removed, not retained for aggregate processing.
                    db.execSQL("DELETE FROM sections WHERE seq = ?", arrayOf(row.seq))
                } else {
                    db.execSQL("UPDATE sections SET state = 'CONFIRMED', included = 1, question = ? WHERE seq = ?",
                        arrayOf(if (row.seq in questions) 1 else 0, row.seq))
                }
            }
            // The directed observations of this course travel on the same
            // decision. A discarded course leaves nothing behind in either
            // identity; there is no half-kept drive.
            if (discardAll) {
                db.execSQL("DELETE FROM direct_sections WHERE trip_id = ?", arrayOf(trip))
            } else {
                if (suitabilityConfirmed) {
                    val approved = ArrayList<Pair<Long, String>>()
                    db.rawQuery("SELECT seq, json FROM direct_sections WHERE trip_id = ? AND state = 'STAGED'",
                        arrayOf(trip)).use { cursor ->
                        while (cursor.moveToNext()) {
                            val observation = try {
                                JSONObject(cursor.getString(1)).also {
                                    check(it.getJSONObject("segmentKey").getInt("version") == 2) {
                                        "Directed observation is not RCS2"
                                    }
                                }
                            } catch (error: Exception) {
                                throw IllegalStateException("Cannot confirm directed observation", error)
                            }
                            // Rows stored before 314 carry no course. They are still
                            // confirmed for passability; the answer is not attached,
                            // since the server refuses it without a course, and no
                            // course is invented for them. Missing means unknown.
                            if (observation.optString("comparisonGroupId").isBlank()) continue
                            // Only what the review drew: the driver answered for
                            // the road he was shown, not for a stretch he never
                            // saw. With the RCS1 fallback nothing is drawn (317).
                            if (drawnStretch(db, observation) == null) continue
                            val json = observation.put("suitabilityConfirmed", true).toString()
                            approved.add(cursor.getLong(0) to json)
                        }
                    }
                    for ((seq, json) in approved) {
                        db.execSQL("UPDATE direct_sections SET json = ? WHERE seq = ? AND state = 'STAGED'",
                            arrayOf(json, seq))
                    }
                }
                db.execSQL("UPDATE direct_sections SET state = 'CONFIRMED'"
                    + " WHERE trip_id = ? AND state = 'STAGED'", arrayOf(trip))
            }
            db.execSQL("UPDATE trips SET reviewed = 1 WHERE id = ?", arrayOf(trip))
        }
        updateSummary(db)
    }

    @Synchronized
    fun saveDraft(trip: String, included: LongArray, questions: LongArray) {
        if (!RoadCrewMapObservationConsent.isEnabled(app)) return
        val db = database()
        transaction(db) {
            db.execSQL("UPDATE sections SET included = 0 WHERE trip_id = ? AND state = 'STAGED'", arrayOf(trip))
            for (id in included) db.execSQL("UPDATE sections SET included = 1 WHERE seq = ? AND trip_id = ? AND state = 'STAGED'", arrayOf(id, trip))
            db.execSQL("UPDATE sections SET question = 0 WHERE trip_id = ? AND state = 'STAGED'", arrayOf(trip))
            for (id in questions) db.execSQL("UPDATE sections SET question = 1 WHERE seq = ? AND trip_id = ? AND state = 'STAGED' AND included = 1", arrayOf(id, trip))
        }
    }

    @Synchronized
    fun transferConfirmed(outbox: RoadCrewObservationOutbox) {
        if (!RoadCrewMapObservationConsent.isEnabled(app)) return
        val db = database()
        prune(db)
        val rows = readRows(db, "state = 'CONFIRMED'", emptyArray(), 400)
        val accepted = outbox.importConfirmed(rows.map { it.record })
        transaction(db) {
            for (row in rows.take(accepted)) {
                // Destination persists first. A crash before this update is an idempotent replay.
                db.execSQL("UPDATE sections SET state = 'TRANSFERRED' WHERE seq = ? AND state = 'CONFIRMED'", arrayOf(row.seq))
            }
        }
        updateSummary(db)
    }

    @Synchronized
    fun nextQuestion(): Row? {
        val db = database()
        val now = System.currentTimeMillis()
        // Only explicitly requested road checks, after their truck passages reach the backend.
        val id = db.rawQuery(NEXT_QUESTION_SQL,
            arrayOf(startOfDay(now).toString(), (now - 900_000).toString(), now.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: return null
        return readRows(db, "seq = ?", arrayOf(id.toString()), 1).firstOrNull()
    }

    @Synchronized
    fun deferQuestion(seq: Long) {
        database().execSQL("UPDATE sections SET retry_at = ? WHERE seq = ?", arrayOf(System.currentTimeMillis() + 900_000, seq))
    }

    @Synchronized
    fun completeQuestion(seq: Long) {
        database().execSQL("UPDATE sections SET question = 0 WHERE seq = ?", arrayOf(seq))
    }

    @Synchronized
    fun clear() {
        val db = database()
        transaction(db) {
            for (table in REVOKED_TABLES.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                db.delete(table, null, null)
            }
        }
        activeTrip = null
        lastPassageAt = 0
        lifecycle.reset()
        updateSummary(db)
    }

    private fun prune(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - 14L * 86400_000
        for (statement in PRUNE_SQL.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            if ('?' in statement) db.execSQL(statement, arrayOf(cutoff)) else db.execSQL(statement)
        }
    }

    private fun readRows(db: SQLiteDatabase, where: String, args: Array<String>, limit: Int = MAX_SECTIONS): List<Row> {
        val rows = ArrayList<Row>()
        db.rawQuery("SELECT seq, trip_id, record, geometry, road_name, included, question FROM sections WHERE $where ORDER BY seq LIMIT $limit", args).use { c ->
            while (c.moveToNext()) {
                rows.add(Row(c.getLong(0), c.getString(1), RoadCrewObservationOutbox.Record.decode(c.getString(2)),
                    c.getString(3), c.getString(4), c.getInt(5) == 1, c.getInt(6) == 1))
            }
        }
        return rows
    }

    private fun updateSummary(db: SQLiteDatabase, full: Boolean = false) {
        // RCS2 rows are the course now (ROADMAP 323).
        app.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).edit()
            .putInt("staged", count(db, "SELECT COUNT(*) FROM direct_sections WHERE state = 'STAGED'"))
            .putInt("confirmed", count(db, "SELECT COUNT(*) FROM direct_sections WHERE state = 'CONFIRMED'"))
            .putInt("pending_trips", count(db, PENDING_COUNT_SQL))
            .putBoolean("full", full || count(db, "SELECT COUNT(*) FROM direct_sections") >= MAX_SECTIONS).apply()
    }

    private fun count(db: SQLiteDatabase, query: String): Int = db.rawQuery(query, null).use { it.moveToFirst(); it.getInt(0) }

    private fun startOfDay(now: Long): Long = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Sofia")).apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun transaction(db: SQLiteDatabase, work: () -> Unit) {
        db.beginTransaction()
        try { work(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    class Row(@JvmField val seq: Long, @JvmField val tripId: String,
              @JvmField val record: RoadCrewObservationOutbox.Record, @JvmField val geometry: String,
              @JvmField val name: String, @JvmField var included: Boolean, @JvmField var question: Boolean)
    /**
     * [direct] holds the driven stretches (RCS2) as latitude/longitude pairs,
     * [directMeters] their total. They are what the suitability answer is
     * stamped on, so they are what the review shows (ROADMAP 315).
     */
    class Trip(@JvmField val id: String, @JvmField val rows: List<Row>,
               @JvmField val direct: List<DoubleArray> = emptyList(), @JvmField val directMeters: Double = 0.0,
               @JvmField val startedAt: Long = 0, @JvmField val endedAt: Long = 0)
    class PendingTrip(@JvmField val id: String, @JvmField val endedAt: Long, @JvmField val sectionCount: Int)

    companion object {
        private const val MAX_SECTIONS = 8000
        private const val SUMMARY = "roadcrew_trip_review_summary"
        private var instance: RoadCrewTripJournal? = null
        @JvmStatic @Synchronized fun get(app: OsmandApplication): RoadCrewTripJournal =
            instance ?: RoadCrewTripJournal(app).also { instance = it }
        @JvmStatic fun stagedCount(context: Context): Int = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getInt("staged", 0)
        @JvmStatic fun waitingCount(context: Context): Int = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getInt("confirmed", 0)
        @JvmStatic fun pendingTripCount(context: Context): Int = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getInt("pending_trips", 0)
        @JvmStatic fun isFull(context: Context): Boolean = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getBoolean("full", false)
        @JvmStatic fun revoke(context: Context) {
            context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).edit()
                .putString("generation", UUID.randomUUID().toString())
                .putInt("staged", 0).putInt("confirmed", 0).putInt("pending_trips", 0)
                .putBoolean("full", false).apply()
        }

        // These exact statements are exercised by the standalone SQLite regression test.
        // ROADMAP 323: a course is offered, listed and counted by its RCS2 rows;
        // RCS1 no longer records on this phone.
        private val REVIEW_SQL = """SELECT id FROM trips WHERE closed = 1 AND reviewed = 0
            AND (? = '1' OR (auto_review = 1 AND ended_at >= ?))
            AND EXISTS(SELECT 1 FROM direct_sections WHERE trip_id = trips.id AND state = 'STAGED')
            ORDER BY ended_at DESC, rowid DESC LIMIT 1
        """
        private val REVIEW_TRIP_SQL = """SELECT 1 FROM trips WHERE id = ? AND closed = 1 AND reviewed = 0
            AND EXISTS(SELECT 1 FROM direct_sections WHERE trip_id = trips.id AND state = 'STAGED')
        """
        private val PENDING_TRIPS_SQL = """SELECT trips.id, trips.ended_at, COUNT(direct_sections.seq)
            FROM trips JOIN direct_sections ON direct_sections.trip_id = trips.id AND direct_sections.state = 'STAGED'
            WHERE trips.closed = 1 AND trips.reviewed = 0
            GROUP BY trips.id ORDER BY trips.ended_at DESC, trips.rowid DESC LIMIT ?
        """
        private val PENDING_COUNT_SQL = """SELECT COUNT(*) FROM trips WHERE closed = 1 AND reviewed = 0
            AND EXISTS(SELECT 1 FROM direct_sections WHERE trip_id = trips.id AND state = 'STAGED')
        """
        /** Statements in order; `?` is the 14-day cutoff. */
        private val PRUNE_SQL = """
            DELETE FROM sections WHERE bucket < ?;
            DELETE FROM sections WHERE state = 'TRANSFERRED' AND question = 0;
            DELETE FROM direct_sections WHERE state = 'STAGED' AND bucket < ?;
            DELETE FROM direct_sections WHERE state = 'TRANSFERRED';
            DELETE FROM trips WHERE closed = 1
                AND NOT EXISTS(SELECT 1 FROM sections WHERE trip_id = trips.id)
                AND NOT EXISTS(SELECT 1 FROM direct_sections WHERE trip_id = trips.id);
            DELETE FROM direct_sections WHERE NOT EXISTS(SELECT 1 FROM trips WHERE trips.id = direct_sections.trip_id)
        """
        /** Everything a revocation or clear() removes: both identities of the drive. */
        private val REVOKED_TABLES = """sections; trips; direct_sections; way_descriptors"""
        private val NEXT_QUESTION_SQL = """SELECT seq FROM sections
            WHERE state = 'TRANSFERRED' AND question = 1 AND bucket BETWEEN ? AND ? AND retry_at <= ?
            ORDER BY retry_at, seq LIMIT 1
        """
        private val TRIPS_SQL = """CREATE TABLE trips (
            id TEXT PRIMARY KEY, closed INTEGER NOT NULL DEFAULT 0 CHECK(closed IN (0,1)),
            reviewed INTEGER NOT NULL DEFAULT 0 CHECK(reviewed IN (0,1)), snooze_until INTEGER NOT NULL DEFAULT 0,
            auto_review INTEGER NOT NULL DEFAULT 0 CHECK(auto_review IN (0,1)),
            prompted INTEGER NOT NULL DEFAULT 0 CHECK(prompted IN (0,1)), ended_at INTEGER NOT NULL DEFAULT 0)
        """
        /** The server takes at most this many in one reply. */
        const val MAX_DESCRIPTORS_PER_REPLY = 20

        private val WAY_DESCRIPTORS_SQL = """CREATE TABLE way_descriptors (
            osm_way_id TEXT NOT NULL, algorithm INTEGER NOT NULL, fingerprint TEXT NOT NULL,
            map_version TEXT NOT NULL, points TEXT NOT NULL, seen_at INTEGER NOT NULL,
            PRIMARY KEY(osm_way_id, algorithm, fingerprint))
        """
        private val DIRECT_SECTIONS_SQL = """CREATE TABLE direct_sections (
            seq INTEGER PRIMARY KEY AUTOINCREMENT, trip_id TEXT NOT NULL, observation_id TEXT NOT NULL,
            bucket INTEGER NOT NULL, json TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'STAGED' CHECK(state IN ('STAGED','CONFIRMED','TRANSFERRED')),
            UNIQUE(trip_id, observation_id))
        """
        private val SECTIONS_SQL = """CREATE TABLE sections (
            seq INTEGER PRIMARY KEY AUTOINCREMENT, trip_id TEXT NOT NULL, observation_key TEXT NOT NULL,
            bucket INTEGER NOT NULL, record TEXT NOT NULL, geometry TEXT NOT NULL, road_name TEXT NOT NULL,
            included INTEGER NOT NULL DEFAULT 1 CHECK(included IN (0,1)),
            state TEXT NOT NULL DEFAULT 'STAGED' CHECK(state IN ('STAGED','CONFIRMED','TRANSFERRED')),
            question INTEGER NOT NULL DEFAULT 0 CHECK(question IN (0,1)), retry_at INTEGER NOT NULL DEFAULT 0,
            UNIQUE(trip_id, observation_key))
        """
    }
}
