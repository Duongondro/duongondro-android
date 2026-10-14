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
import app.duongondro.core.isV7
import app.duongondro.core.sync.Erased
import app.duongondro.core.sync.PinnedFriend
import app.duongondro.core.sync.SocialDatabase
import app.duongondro.core.sync.SyncDatabase
import app.duongondro.core.sync.SyncRecord
import app.duongondro.core.sync.SyncState
import app.duongondro.core.sync.syncMillis
import app.duongondro.core.sync.toSyncTime
import app.duongondro.core.uuidV7
import java.util.concurrent.atomic.AtomicInteger
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
 *
 * Sync (as iOS's AppDatabase): a session's `updated_at` orders writes under
 * last-write-wins, compared to the millisecond; a deletion is a mark
 * (`deleted_at`) so it syncs; `dirty` rows go up on the next push. [generation]
 * is bumped by [eraseAll], and a sync write naming an older one is refused.
 */
class SqliteStore(
    context: Context,
    name: String? = "duongondro.db",
    private val clock: () -> Instant = Instant::now,
) : Store, SyncDatabase, SocialDatabase {
    private val helper = Helper(context.applicationContext, name)
    private val lock = Mutex()
    private val state = MutableStateFlow(Snapshot())
    override val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    /** Reads the database once; call before showing anything. */
    suspend fun load() = write { }

    override suspend fun save(practice: TrackedPractice) = write { upsert(it, practice) }

    override suspend fun insert(session: Session) = write { db ->
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("updated_at", clock().toSyncTime().toEpochMilli())
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
        db.execSQL("UPDATE sessions SET chosen_day = ?, updated_at = ?, dirty = 1 WHERE id = ?",
            arrayOf<Any?>(day?.toString(), nextStamp(db, forSession), forSession.toString()))
    }

    /**
     * A change's time: now, but always after the row's last change, so a local
     * edit made after pulling a newer remote one (or after the clock moved back)
     * still wins under last-write-wins.
     */
    private fun nextStamp(db: SQLiteDatabase, id: UUID): Long {
        val now = clock().syncMillis()
        val last = db.rawQuery("SELECT updated_at FROM sessions WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
        return if (last != null && last >= now) last + 1 else now
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
            generationCounter.incrementAndGet()
            db.execSQL("DELETE FROM sync_state")
            db.execSQL("DELETE FROM friend_pins")
            db.execSQL("DELETE FROM friend_seqs")
            db.execSQL("DELETE FROM public_streaks")
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

    /** One transaction, then a fresh snapshot; refused with [Erased] when [generation] names one an erase has ended. */
    private suspend fun write(generation: Int? = null, block: (SQLiteDatabase) -> Unit): Unit = writeFor(generation, block)

    private suspend fun <T> writeFor(generation: Int?, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            if (generation != null && generation != generationCounter.get()) throw Erased()
            val db = helper.writableDatabase
            db.beginTransaction()
            val result = try {
                block(db).also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
            state.value = read(db)
            result
        }
    }

    private suspend fun <T> query(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block(helper.readableDatabase) }
    }

    // Sync

    private val generationCounter = AtomicInteger(0)
    override val generation: Int get() = generationCounter.get()

    override suspend fun syncState(): SyncState? = query { db ->
        db.rawQuery("SELECT * FROM sync_state WHERE id = 1", null).all { c ->
            SyncState(UUID.fromString(c.str("user_id")), c.long("key_version"), c.str("cursor"))
        }.firstOrNull()
    }

    override suspend fun saveSyncState(state: SyncState, generation: Int?) = write(generation) { db ->
        db.insertWithOnConflict("sync_state", null, ContentValues().apply {
            put("id", 1)
            put("user_id", state.user.toString().lowercase())
            put("key_version", state.keyVersion)
            put("cursor", state.cursor)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // Friends and public streaks (iOS's AppDatabase, phase 4)

    override suspend fun pinnedFriends(): List<PinnedFriend> = query { db ->
        db.rawQuery("SELECT * FROM friend_pins ORDER BY display_name, user_id", null).all(::pinnedFriend)
    }

    private fun pinnedFriend(c: Cursor) = PinnedFriend(
        UUID.fromString(c.str("user_id")), c.getBlob(c.getColumnIndexOrThrow("identity_pk")), c.str("display_name").orEmpty(),
        Instant.ofEpochMilli(c.long("pinned_at")), c.int("listed") == 1,
    )

    /** The first key seen is the one kept; the name follows the server, and the friend is listed again. */
    override suspend fun pin(user: UUID, identityPk: ByteArray, displayName: String, at: Instant, generation: Int?): PinnedFriend =
        writeFor(generation) { db ->
            val id = user.toString().lowercase()
            val updated = db.update("friend_pins", ContentValues().apply {
                put("display_name", displayName)
                put("listed", 1)
            }, "user_id = ?", arrayOf(id))
            if (updated == 0) db.insertOrThrow("friend_pins", null, ContentValues().apply {
                put("user_id", id)
                put("identity_pk", identityPk)
                put("display_name", displayName)
                put("pinned_at", at.toEpochMilli())
                put("listed", 1)
            })
            db.rawQuery("SELECT * FROM friend_pins WHERE user_id = ?", arrayOf(id)).all(::pinnedFriend).single()
        }

    /**
     * Pins a friend's identity key as an invitation proved it (its MAC under the
     * link's pin). The key the invite proved wins over anything pinned before,
     * so a re-invite is how a changed key gets sorted out; an empty name keeps
     * the one known.
     */
    override suspend fun repin(user: UUID, identityPk: ByteArray, displayName: String, at: Instant, generation: Int?) = write(generation) { db ->
        val id = user.toString().lowercase()
        val values = ContentValues().apply {
            put("identity_pk", identityPk)
            if (displayName.isNotEmpty()) put("display_name", displayName)
            put("pinned_at", at.toEpochMilli())
            put("listed", 1)
        }
        if (db.update("friend_pins", values, "user_id = ?", arrayOf(id)) == 0) {
            values.put("user_id", id)
            values.put("display_name", displayName)
            db.insertOrThrow("friend_pins", null, values)
        }
    }

    /** The pinned identity key of a friend, or null. */
    suspend fun pinnedKey(user: UUID): ByteArray? = query { db ->
        db.rawQuery("SELECT identity_pk FROM friend_pins WHERE user_id = ?", arrayOf(user.toString().lowercase())).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    override suspend fun keepFriends(listed: Set<UUID>, generation: Int?) = write(generation) { db ->
        db.rawQuery("SELECT user_id FROM friend_pins", null).all { it.getString(0) }.forEach { id ->
            val on = runCatching { UUID.fromString(id) in listed }.getOrDefault(false)
            db.execSQL("UPDATE friend_pins SET listed = ? WHERE user_id = ?", arrayOf<Any>(on.bit, id))
        }
    }

    override suspend fun forgetFriend(user: UUID) = write { db ->
        val id = user.toString().lowercase()
        db.execSQL("DELETE FROM friend_pins WHERE user_id = ?", arrayOf(id))
        db.execSQL("DELETE FROM friend_seqs WHERE user_id = ?", arrayOf(id))
    }

    override suspend fun seenSeq(user: UUID, practice: String): Long = query { db ->
        db.rawQuery("SELECT seq FROM friend_seqs WHERE user_id = ? AND practice = ?", arrayOf(user.toString().lowercase(), practice)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    override suspend fun noteSeq(user: UUID, practice: String, seq: Long, generation: Int?) = write(generation) { db ->
        val id = user.toString().lowercase()
        db.execSQL("UPDATE friend_seqs SET seq = MAX(seq, ?) WHERE user_id = ? AND practice = ?", arrayOf<Any>(seq, id, practice))
        db.execSQL("INSERT OR IGNORE INTO friend_seqs (user_id, practice, seq) VALUES (?, ?, ?)", arrayOf<Any>(id, practice, seq))
    }

    override suspend fun publicStreakSeqs(): Map<String, Long> = query { db ->
        db.rawQuery("SELECT practice_id, seq FROM public_streaks", null).all { it.getString(0) to it.getLong(1) }.toMap()
    }

    /** Makes a practice's streak public or private here; publishing is Social's. */
    override suspend fun setPublic(practice: String, isPublic: Boolean) = write { db ->
        if (isPublic) db.execSQL("INSERT OR IGNORE INTO public_streaks (practice_id) VALUES (?)", arrayOf(practice))
        else db.execSQL("DELETE FROM public_streaks WHERE practice_id = ?", arrayOf(practice))
    }

    override suspend fun isPublic(practice: String): Boolean = query { db ->
        db.rawQuery("SELECT 1 FROM public_streaks WHERE practice_id = ?", arrayOf(practice)).use { it.moveToFirst() }
    }

    override suspend fun savePublishedSeq(practice: String, seq: Long, generation: Int?) = write(generation) { db ->
        db.execSQL("UPDATE public_streaks SET seq = MAX(seq, ?) WHERE practice_id = ?", arrayOf<Any>(seq, practice))
    }

    /**
     * Practices this phone tracks that the server publishes for this account
     * become public here too; published ones it no longer lists (made private on
     * another phone) become private. Seq 0, never published yet, stays.
     */
    override suspend fun mergePublished(seqs: Map<String, Long>, generation: Int?) = write(generation) { db ->
        db.rawQuery("SELECT practice_id FROM public_streaks WHERE seq > 0", null).all { it.getString(0) }
            .filter { it !in seqs }
            .forEach { db.execSQL("DELETE FROM public_streaks WHERE practice_id = ?", arrayOf(it)) }
        for ((practice, seq) in seqs) {
            val known = db.rawQuery("SELECT 1 FROM practices WHERE id = ?", arrayOf(practice)).use { it.moveToFirst() }
            if (!known) continue
            db.execSQL("UPDATE public_streaks SET seq = MAX(seq, ?) WHERE practice_id = ?", arrayOf<Any>(seq, practice))
            db.execSQL("INSERT OR IGNORE INTO public_streaks (practice_id, seq) VALUES (?, ?)", arrayOf<Any>(practice, seq))
        }
    }

    override suspend fun sessionsOf(practice: String) = snapshot.value.sessionsOf(practice)

    /** Deletes the practice (sessions, seeds, practices) and the sync state, keeping the preferences: another account's data leaving. */
    suspend fun erasePractice() = write { db ->
        generationCounter.incrementAndGet()
        db.execSQL("DELETE FROM sync_state")
        db.execSQL("DELETE FROM friend_pins")
        db.execSQL("DELETE FROM friend_seqs")
        db.execSQL("DELETE FROM public_streaks")
        db.execSQL("DELETE FROM sessions")
        db.execSQL("DELETE FROM streak_seeds")
        db.execSQL("DELETE FROM practices")
    }

    /**
     * Forgets the account this phone synced with (signing out); the sessions stay,
     * dirty, as in local mode. Its friends and public practices go with it: the
     * next account starts with none.
     */
    suspend fun clearSyncState() = write { db ->
        generationCounter.incrementAndGet()
        db.execSQL("DELETE FROM sync_state")
        db.execSQL("DELETE FROM friend_pins")
        db.execSQL("DELETE FROM friend_seqs")
        db.execSQL("DELETE FROM public_streaks")
        db.execSQL("UPDATE sessions SET dirty = 1")
    }

    override suspend fun dirtySessions(): List<SyncRecord> = query { db ->
        db.rawQuery("SELECT * FROM sessions WHERE dirty = 1 ORDER BY updated_at, id", null).all(::syncRecord)
    }

    override suspend fun markSynced(id: UUID, updatedAt: Instant, generation: Int) = write(generation) { db ->
        db.execSQL("UPDATE sessions SET dirty = 0 WHERE id = ? AND updated_at = ?", arrayOf<Any>(id.toString(), updatedAt.syncMillis()))
    }

    override suspend fun markAllDirty() = write { db -> db.execSQL("UPDATE sessions SET dirty = 1") }

    /**
     * Gives every session whose id is not a UUIDv7 (logged before sync, with
     * version 4) a new v7 id from its logging time. Safe because such a session
     * never reached the server, which accepts only v7.
     */
    override suspend fun rekeyLegacySessionIds() = write { db ->
        db.rawQuery("SELECT id, logged_at FROM sessions", null).all { c -> c.str("id")!! to c.long("logged_at") }
            .filter { (id, _) -> !UUID.fromString(id).isV7 }
            .forEach { (id, loggedAt) ->
                db.execSQL("UPDATE sessions SET id = ?, dirty = 1 WHERE id = ?",
                    arrayOf(uuidV7(Instant.ofEpochMilli(loggedAt)).toString(), id))
            }
    }

    override suspend fun applyRemote(record: SyncRecord, practiceName: String?, generation: Int): Boolean = writeFor(generation) { db ->
        val s = record.session
        val id = s.id.toString()
        val local = db.rawQuery("SELECT updated_at FROM sessions WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) 0L else c.getLong(0)) else null
        }
        if (local != null && local >= record.updatedAt.syncMillis()) return@writeFor false
        // A deletion of a session this phone never had changes nothing here.
        if (local == null && record.deletedAt != null) return@writeFor false
        val known = db.rawQuery("SELECT 1 FROM practices WHERE id = ?", arrayOf(s.practiceId)).use { it.moveToFirst() }
        if (!known) {
            val order = db.rawQuery("SELECT COALESCE(MAX(sort_order) + 1, 0) FROM practices", null).use { c -> c.moveToFirst(); c.getInt(0) }
            val practice = Catalogue.builtIn.firstOrNull { it.id == s.practiceId }
                ?: Practice(s.practiceId, practiceName ?: s.practiceId, null, PracticeGroup.AnyTime, null, allowStreakOnly = true, isCustom = true)
            upsert(db, TrackedPractice(practice, wantsStreakOnly = practice.streakOnlyByDefault, sortOrder = order))
        }
        val values = ContentValues().apply {
            put("practice_id", s.practiceId)
            put("amount", s.amount)
            put("started_at", s.startedAt.toEpochMilli())
            put("start_exact", s.startExact)
            put("time_zone", s.zoneId)
            put("chosen_day", s.chosenDay?.toString())
            put("logged_at", s.loggedAt.toEpochMilli())
            put("updated_at", record.updatedAt.syncMillis())
            put("deleted_at", record.deletedAt?.syncMillis())
            put("dirty", 0)
        }
        if (local == null) {
            values.put("id", id)
            db.insertOrThrow("sessions", null, values)
        } else {
            db.update("sessions", values, "id = ?", arrayOf(id))
        }
        true
    }

    override suspend fun applyRemoteDeletion(id: UUID, updatedAt: Instant, deletedAt: Instant, generation: Int): Boolean = writeFor(generation) { db ->
        val local = db.rawQuery("SELECT updated_at FROM sessions WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) (if (c.isNull(0)) 0L else c.getLong(0)) else null
        }
        if (local == null || local >= updatedAt.syncMillis()) return@writeFor false
        db.execSQL("UPDATE sessions SET deleted_at = ?, updated_at = ?, dirty = 0 WHERE id = ?",
            arrayOf<Any>(deletedAt.syncMillis(), updatedAt.syncMillis(), id.toString()))
        true
    }

    override suspend fun customNames(): Map<String, String> =
        snapshot.value.practices.filter { it.practice.isCustom }.associate { it.id to it.practice.name }

    private fun syncRecord(c: Cursor): SyncRecord {
        val session = sessionOf(c)
        val updated = c.getColumnIndexOrThrow("updated_at").let { if (c.isNull(it)) session.loggedAt.toEpochMilli() else c.getLong(it) }
        val deleted = c.getColumnIndexOrThrow("deleted_at").let { if (c.isNull(it)) null else Instant.ofEpochMilli(c.getLong(it)) }
        return SyncRecord(session, Instant.ofEpochMilli(updated), deleted)
    }

    private fun sessionOf(c: Cursor) = Session(
        id = UUID.fromString(c.str("id")), practiceId = c.str("practice_id")!!, amount = c.int("amount") ?: 0,
        startedAt = Instant.ofEpochMilli(c.long("started_at")), startExact = c.int("start_exact") == 1,
        zoneId = c.str("time_zone")!!, chosenDay = c.str("chosen_day")?.let(::parseCivilDate),
        loggedAt = Instant.ofEpochMilli(c.long("logged_at")),
    )

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
            put("usual_time_nudge", p.usualTimeNudge)
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
        val sessions = db.rawQuery("SELECT * FROM sessions WHERE deleted_at IS NULL ORDER BY started_at, id", null).all(::sessionOf)
        val seeds = db.rawQuery("SELECT * FROM streak_seeds ORDER BY practice_id", null).all { c ->
            StreakSeed(c.str("practice_id")!!, c.int("days") ?: 0, c.int("longest"),
                parseCivilDate(c.str("last_day")!!) ?: LocalDate.of(1970, 1, 1), c.str("time_zone")!!)
        }
        val prefs = db.rawQuery("SELECT * FROM preferences WHERE id = 1", null).all { c ->
            Preferences(
                onboarded = c.int("onboarded") == 1, finishedShortRefuge = c.int("finished_short_refuge") == 1,
                finishedNgondro = c.int("finished_ngondro") == 1, malaSize = c.int("mala_size") ?: 108,
                reminderMinutes = c.int("reminder_minutes"), discreetNotifications = c.int("discreet_notifications") == 1,
                usualTimeNudge = c.int("usual_time_nudge") == 1,
            )
        }.firstOrNull() ?: Preferences()
        val public = db.rawQuery("SELECT practice_id FROM public_streaks", null).all { it.getString(0) }.toSet()
        return Snapshot(practices, sessions, seeds, prefs, public)
    }

    /** Table names, for the test that every table is exported and erased. */
    internal fun tables(): List<String> = helper.readableDatabase
        .rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null)
        .all { it.getString(0) }

    fun close() = helper.close()

    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, VERSION) {
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
            onUpgrade(db, 1, VERSION)
        }

        /**
         * Version 2, sync: when each session last changed (Unix milliseconds; the
         * logging time for rows from before), its deletion mark, and the account
         * this phone syncs with.
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) listOf(
                "ALTER TABLE sessions ADD COLUMN updated_at INTEGER",
                "UPDATE sessions SET updated_at = logged_at",
                "ALTER TABLE sessions ADD COLUMN deleted_at INTEGER",
                """
                CREATE TABLE sync_state (
                    id          INTEGER PRIMARY KEY CHECK (id = 1),
                    user_id     TEXT NOT NULL,
                    key_version INTEGER NOT NULL,
                    cursor      TEXT
                )""",
            ).forEach { db.execSQL(it.trimIndent()) }
            // Version 3: friends' identity keys as an invitation proved them (iOS's friends table, pins only).
            if (oldVersion < 3) db.execSQL(
                """
                CREATE TABLE friend_pins (
                    user_id      TEXT PRIMARY KEY NOT NULL,
                    identity_pk  BLOB NOT NULL,
                    display_name TEXT NOT NULL,
                    pinned_at    INTEGER NOT NULL
                )""".trimIndent(),
            )
            // Version 4, public streaks: a pin outlives the server's list (`listed` 0
            // once dropped; only this person's unfriend or block forgets it), the
            // highest streak seq seen per friend and practice (no replays), and the
            // practices whose streak friends see, with the seq last published.
            if (oldVersion < 4) listOf(
                "ALTER TABLE friend_pins ADD COLUMN listed INTEGER NOT NULL DEFAULT 1",
                """
                CREATE TABLE friend_seqs (
                    user_id  TEXT NOT NULL,
                    practice TEXT NOT NULL,
                    seq      INTEGER NOT NULL,
                    PRIMARY KEY (user_id, practice)
                )""",
                """
                CREATE TABLE public_streaks (
                    practice_id TEXT PRIMARY KEY NOT NULL REFERENCES practices (id) ON DELETE CASCADE,
                    seq         INTEGER NOT NULL DEFAULT 0
                )""",
            ).forEach { db.execSQL(it.trimIndent()) }
            // Version 5: "Nudge at my usual time", off until turned on.
            if (oldVersion < 5) db.execSQL("ALTER TABLE preferences ADD COLUMN usual_time_nudge INTEGER NOT NULL DEFAULT 0")
        }

        companion object {
            const val VERSION = 5
        }
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

