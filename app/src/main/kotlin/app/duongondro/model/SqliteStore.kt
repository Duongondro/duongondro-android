package app.duongondro.model

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.duongondro.core.Catalogue
import app.duongondro.core.Practice
import app.duongondro.core.PracticeGroup
import app.duongondro.core.Session
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.parseCivilDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The local SQLite database: the iOS app's tables and columns, in plain SQL.
 * Two storage details differ and need aligning before sync: times are Unix
 * milliseconds here (DATETIME text on iOS), and preferences are columns here
 * (one JSON row on iOS).
 * Every write goes through here and re-reads the snapshot, so the UI never
 * shows a state the database does not hold. Excluded from cloud backups and
 * device transfer (data_extraction_rules.xml): it holds plaintext counts.
 */
class SqliteStore(context: Context, name: String? = "duongondro.db") : Store {
    private val helper = Helper(context.applicationContext, name)
    private val lock = Mutex()
    private val state = MutableStateFlow(Snapshot())
    override val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    /** Reads the database once; call before showing anything. */
    suspend fun load() = write { }

    override suspend fun save(practice: TrackedPractice) = write { upsert(it, practice) }

    override suspend fun insert(session: Session) = write { db ->
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", session.id.toString())
            put("practice_id", session.practiceId)
            put("amount", session.amount)
            put("started_at", session.startedAt.toEpochMilli())
            put("start_exact", session.startExact)
            put("time_zone", session.zoneId)
            put("chosen_day", session.chosenDay?.toString())
            put("logged_at", session.loggedAt.toEpochMilli())
        })
    }

    override suspend fun choose(day: LocalDate?, forSession: UUID) = write { db ->
        db.execSQL("UPDATE sessions SET chosen_day = ?, dirty = 1 WHERE id = ?", arrayOf(day?.toString(), forSession.toString()))
    }

    override suspend fun save(preferences: Preferences) = write { savePreferences(it, preferences) }

    override suspend fun completeOnboarding(practices: List<TrackedPractice>, seeds: List<StreakSeed>, preferences: Preferences) =
        write { db ->
            practices.forEach { upsert(db, it) }
            seeds.forEach { s ->
                db.insertWithOnConflict("streak_seeds", null, ContentValues().apply {
                    put("practice_id", s.practiceId)
                    put("days", s.days)
                    put("longest", s.longest)
                    put("last_day", s.lastDay.toString())
                    put("time_zone", s.zoneId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            savePreferences(db, preferences)
        }

    /** Deletes every row, then VACUUM rewrites the file so deleted rows do not linger in free pages. */
    override suspend fun eraseAll() {
        write { db ->
            db.execSQL("DELETE FROM sessions")
            db.execSQL("DELETE FROM streak_seeds")
            db.execSQL("DELETE FROM practices")
            db.execSQL("DELETE FROM preferences")
        }
        withContext(Dispatchers.IO) {
            lock.withLock {
                val db = helper.writableDatabase
                db.execSQL("VACUUM")
                // VACUUM in WAL mode writes into the WAL; fold it into the file and
                // truncate it, so no old page survives in either.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }
    }

    /** One transaction, then a fresh snapshot. */
    private suspend fun write(block: (SQLiteDatabase) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            state.value = read(db)
        }
    }

    /**
     * Update, then insert if nothing matched. Not `ON CONFLICT DO UPDATE`, which
     * needs SQLite 3.24 (API 28 and 29 ship 3.22), and not REPLACE, which would
     * delete the row and cascade to its sessions.
     */
    private fun upsert(db: SQLiteDatabase, p: TrackedPractice) {
        val q = p.practice
        val values = ContentValues().apply {
            put("name", q.name)
            put("second_name", q.secondName)
            put("grp", q.group.wire)
            put("target", q.target)
            put("streak_only_allowed", q.streakOnlyAllowed)
            put("streak_only", p.streakOnly)
            put("mala_size", q.malaSize)
            put("is_custom", q.isCustom)
            put("opening_count", p.openingCount)
            put("archived", p.archived)
            put("sort_order", p.sortOrder)
            put("dirty", 1)
        }
        if (db.update("practices", values, "id = ?", arrayOf(q.id)) == 0) {
            values.put("id", q.id)
            db.insertOrThrow("practices", null, values)
        }
    }

    private fun savePreferences(db: SQLiteDatabase, p: Preferences) {
        db.insertWithOnConflict("preferences", null, ContentValues().apply {
            put("id", 1)
            put("onboarded", p.onboarded)
            put("finished_short_refuge", p.finishedShortRefuge)
            put("finished_ngondro", p.finishedNgondro)
            put("mala_size", p.malaSize)
            put("reminder_minutes", p.reminderMinutes)
            put("discreet_notifications", p.discreetNotifications)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun read(db: SQLiteDatabase): Snapshot {
        val practices = db.rawQuery("SELECT * FROM practices ORDER BY sort_order, id", null).all { c ->
            val stored = Practice(
                id = c.str("id")!!, name = c.str("name")!!, secondName = c.str("second_name"),
                group = PracticeGroup.entries.firstOrNull { it.wire == c.str("grp") } ?: PracticeGroup.AnyTime,
                target = c.int("target"), allowStreakOnly = c.int("streak_only_allowed") == 1,
                malaSize = c.int("mala_size"), isCustom = c.int("is_custom") == 1,
            )
            // Whether a built-in may be streak-only follows the catalogue, so a change
            // in an update reaches practices already tracked.
            val current = Catalogue.builtIn.firstOrNull { it.id == stored.id && !stored.isCustom }
            TrackedPractice(
                if (current == null) stored else stored.copy(allowStreakOnly = current.streakOnlyAllowed),
                wantsStreakOnly = c.int("streak_only") == 1, openingCount = c.int("opening_count") ?: 0,
                archived = c.int("archived") == 1, sortOrder = c.int("sort_order") ?: 0,
            )
        }
        val sessions = db.rawQuery("SELECT * FROM sessions ORDER BY started_at, id", null).all { c ->
            Session(
                id = UUID.fromString(c.str("id")), practiceId = c.str("practice_id")!!, amount = c.int("amount") ?: 0,
                startedAt = Instant.ofEpochMilli(c.long("started_at")), startExact = c.int("start_exact") == 1,
                zoneId = c.str("time_zone")!!, chosenDay = c.str("chosen_day")?.let(::parseCivilDate),
                loggedAt = Instant.ofEpochMilli(c.long("logged_at")),
            )
        }
        val seeds = db.rawQuery("SELECT * FROM streak_seeds ORDER BY practice_id", null).all { c ->
            StreakSeed(c.str("practice_id")!!, c.int("days") ?: 0, c.int("longest"),
                parseCivilDate(c.str("last_day")!!) ?: LocalDate.of(1970, 1, 1), c.str("time_zone")!!)
        }
        val prefs = db.rawQuery("SELECT * FROM preferences WHERE id = 1", null).all { c ->
            Preferences(
                onboarded = c.int("onboarded") == 1, finishedShortRefuge = c.int("finished_short_refuge") == 1,
                finishedNgondro = c.int("finished_ngondro") == 1, malaSize = c.int("mala_size") ?: 108,
                reminderMinutes = c.int("reminder_minutes"), discreetNotifications = c.int("discreet_notifications") == 1,
            )
        }.firstOrNull() ?: Preferences()
        return Snapshot(practices, sessions, seeds, prefs)
    }

    /** Table names, for the test that every table is exported and erased. */
    internal fun tables(): List<String> = helper.readableDatabase
        .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null)
        .all { it.getString(0) }

    fun close() = helper.close()

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.enableWriteAheadLogging()
            // Deleted content is overwritten with zeros, not left in free pages.
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        }

        /**
         * Plain SQL, as on iOS. Never edit a version that has shipped: add one in
         * onUpgrade. `dirty` marks rows changed since the server last acknowledged
         * them; every row starts dirty, so a local-mode user who signs up later
         * pushes everything once.
         */
        override fun onCreate(db: SQLiteDatabase) {
            listOf(
                """
                CREATE TABLE practices (
                    id                  TEXT PRIMARY KEY NOT NULL,
                    name                TEXT NOT NULL,
                    second_name         TEXT,
                    grp                 TEXT NOT NULL,
                    target              INTEGER,
                    streak_only_allowed INTEGER NOT NULL,
                    streak_only         INTEGER NOT NULL DEFAULT 0,
                    mala_size           INTEGER,
                    is_custom           INTEGER NOT NULL DEFAULT 0,
                    opening_count       INTEGER NOT NULL DEFAULT 0,
                    archived            INTEGER NOT NULL DEFAULT 0,
                    sort_order          INTEGER NOT NULL DEFAULT 0,
                    dirty               INTEGER NOT NULL DEFAULT 1
                )""",
                """
                CREATE TABLE sessions (
                    id          TEXT PRIMARY KEY NOT NULL,
                    practice_id TEXT NOT NULL REFERENCES practices (id) ON DELETE CASCADE,
                    amount      INTEGER NOT NULL CHECK (amount >= 0),
                    started_at  INTEGER NOT NULL,
                    start_exact INTEGER NOT NULL,
                    time_zone   TEXT NOT NULL,
                    chosen_day  TEXT,
                    logged_at   INTEGER NOT NULL,
                    dirty       INTEGER NOT NULL DEFAULT 1
                )""",
                "CREATE INDEX sessions_practice ON sessions (practice_id, started_at)",
                """
                CREATE TABLE streak_seeds (
                    practice_id TEXT PRIMARY KEY NOT NULL REFERENCES practices (id) ON DELETE CASCADE,
                    days        INTEGER NOT NULL,
                    longest     INTEGER,
                    last_day    TEXT NOT NULL,
                    time_zone   TEXT NOT NULL,
                    dirty       INTEGER NOT NULL DEFAULT 1
                )""",
                """
                CREATE TABLE preferences (
                    id                     INTEGER PRIMARY KEY CHECK (id = 1),
                    onboarded              INTEGER NOT NULL,
                    finished_short_refuge  INTEGER NOT NULL,
                    finished_ngondro       INTEGER NOT NULL,
                    mala_size              INTEGER NOT NULL,
                    reminder_minutes       INTEGER,
                    discreet_notifications INTEGER NOT NULL
                )""",
            ).forEach { db.execSQL(it.trimIndent()) }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

private val PracticeGroup.wire: String
    get() = when (this) {
        PracticeGroup.BeforeNgondro -> "beforeNgondro"
        PracticeGroup.Ngondro -> "ngondro"
        PracticeGroup.AfterNgondro -> "afterNgondro"
        PracticeGroup.AnyTime -> "anyTime"
    }

private val Boolean.bit: Int get() = if (this) 1 else 0

private fun <T> Cursor.all(row: (Cursor) -> T): List<T> = use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
private fun Cursor.int(col: String): Int? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))

