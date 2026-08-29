package net.osmand.plus.roadcrew

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import net.osmand.binary.RouteDataObject
import net.osmand.plus.OsmandApplication
import net.osmand.router.RoadCrewObservationOutbox
import net.osmand.router.RoadCrewPassageDetector
import net.osmand.router.RoadCrewSegmentIdentity
import net.osmand.router.RoadCrewTripLifecycle
import net.osmand.util.MapUtils
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Local-only review journal. Nothing here is eligible for upload until explicitly confirmed. */
internal class RoadCrewTripJournal private constructor(private val app: OsmandApplication) {
    private val helper = object : SQLiteOpenHelper(app,
        File(app.noBackupFilesDir, "roadcrew-trip-review.db").absolutePath, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(TRIPS_SQL)
            db.execSQL(SECTIONS_SQL)
            db.execSQL("CREATE INDEX trip_review_pending ON sections(state, seq)")
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
                db.delete("sections", null, null)
                db.delete("trips", null, null)
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
        if (lifecycle.shouldCloseForGap(lastPassageAt, at)) finish(false)
        if (activeTrip == null) {
            val id = UUID.randomUUID().toString()
            db.execSQL("INSERT INTO trips(id, closed, reviewed, snooze_until) VALUES (?, 0, 0, 0)", arrayOf(id))
            activeTrip = id
        }
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
        database().execSQL("UPDATE trips SET closed = 1, auto_review = ?, ended_at = ? WHERE id = ?",
            arrayOf(if (offerReview) 1 else 0, System.currentTimeMillis(), id))
        activeTrip = null
        lastPassageAt = 0
    }

    @Synchronized
    fun review(manual: Boolean): Trip? {
        val db = database()
        prune(db)
        updateSummary(db)
        val now = System.currentTimeMillis()
        val id = db.rawQuery(REVIEW_SQL, arrayOf(if (manual) "1" else "0", startOfDay(now).toString(), now.toString()))
            .use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return null
        return Trip(id, readRows(db, "trip_id = ? AND state = 'STAGED'", arrayOf(id)))
    }

    @Synchronized
    fun markPresented(trip: String) {
        database().execSQL("UPDATE trips SET prompted = 1 WHERE id = ? AND closed = 1", arrayOf(trip))
    }

    @Synchronized
    fun snooze(trip: String) {
        markPresented(trip)
        database().execSQL("UPDATE trips SET snooze_until = ? WHERE id = ?", arrayOf(Long.MAX_VALUE, trip))
    }

    @Synchronized
    fun confirm(trip: String, selectedIds: LongArray, questionIds: LongArray, discardAll: Boolean) {
        check(RoadCrewMapObservationConsent.isEnabled(app)) { "Sharing is disabled" }
        val db = database()
        val rows = readRows(db, "trip_id = ? AND state = 'STAGED'", arrayOf(trip))
        check(rows.isNotEmpty() && activeTrip != trip) { "Trip is not available for review" }
        val requested = selectedIds.toSet()
        require(rows.map { it.seq }.containsAll(requested)) { "Review contains foreign sections" }
        val selected = if (discardAll) emptyList() else rows.filter { it.seq in requested }
        require(discardAll || selected.isNotEmpty()) { "No truck sections selected" }
        // Vehicle confirmation alone never creates a road-suitability answer or question.
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
        transaction(db) { db.delete("sections", null, null); db.delete("trips", null, null) }
        activeTrip = null
        lastPassageAt = 0
        lifecycle.reset()
        updateSummary(db)
    }

    private fun prune(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - 14L * 86400_000
        db.execSQL("DELETE FROM sections WHERE bucket < ?", arrayOf(cutoff))
        db.execSQL("DELETE FROM sections WHERE state = 'TRANSFERRED' AND question = 0")
        db.execSQL("DELETE FROM trips WHERE closed = 1 AND NOT EXISTS(SELECT 1 FROM sections WHERE trip_id = trips.id)")
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
        app.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).edit()
            .putInt("staged", count(db, "SELECT COUNT(*) FROM sections WHERE state = 'STAGED'"))
            .putInt("confirmed", count(db, "SELECT COUNT(*) FROM sections WHERE state = 'CONFIRMED'"))
            .putBoolean("full", full || count(db, "SELECT COUNT(*) FROM sections") >= MAX_SECTIONS).apply()
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
    class Trip(@JvmField val id: String, @JvmField val rows: List<Row>)

    companion object {
        private const val MAX_SECTIONS = 8000
        private const val SUMMARY = "roadcrew_trip_review_summary"
        private var instance: RoadCrewTripJournal? = null
        @JvmStatic @Synchronized fun get(app: OsmandApplication): RoadCrewTripJournal =
            instance ?: RoadCrewTripJournal(app).also { instance = it }
        @JvmStatic fun stagedCount(context: Context): Int = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getInt("staged", 0)
        @JvmStatic fun waitingCount(context: Context): Int = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getInt("confirmed", 0)
        @JvmStatic fun isFull(context: Context): Boolean = context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).getBoolean("full", false)
        @JvmStatic fun revoke(context: Context) {
            context.getSharedPreferences(SUMMARY, Context.MODE_PRIVATE).edit()
                .putString("generation", UUID.randomUUID().toString())
                .putInt("staged", 0).putInt("confirmed", 0).putBoolean("full", false).apply()
        }

        // These exact statements are exercised by the standalone SQLite regression test.
        private val REVIEW_SQL = """SELECT id FROM trips WHERE closed = 1 AND reviewed = 0
            AND (? = '1' OR (auto_review = 1 AND prompted = 0 AND ended_at >= ? AND snooze_until <= ?))
            AND EXISTS(SELECT 1 FROM sections WHERE trip_id = trips.id AND state = 'STAGED')
            ORDER BY ended_at DESC, rowid DESC LIMIT 1
        """
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
