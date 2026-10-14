package app.duongondro.model

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * Review found it: a database as 0.2 shipped it (schema version 3, written
 * here statement by statement as that version made it) opens under the
 * current version with every row kept: friends' pins become listed, nothing
 * is public, and the usual-time nudge is off.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "upgrade-${System.nanoTime()}.db"

    @After fun delete() { context.deleteDatabase(name) }

    private val version3 = listOf(
        """CREATE TABLE practices (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, second_name TEXT, grp TEXT NOT NULL,
            target INTEGER, streak_only_allowed INTEGER NOT NULL, streak_only INTEGER NOT NULL DEFAULT 0, mala_size INTEGER,
            is_custom INTEGER NOT NULL DEFAULT 0, opening_count INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0,
            sort_order INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE sessions (id TEXT PRIMARY KEY NOT NULL, practice_id TEXT NOT NULL REFERENCES practices (id) ON DELETE CASCADE,
            amount INTEGER NOT NULL CHECK (amount >= 0), started_at INTEGER NOT NULL, start_exact INTEGER NOT NULL,
            time_zone TEXT NOT NULL, chosen_day TEXT, logged_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 1)""",
        "CREATE INDEX sessions_practice ON sessions (practice_id, started_at)",
        """CREATE TABLE streak_seeds (practice_id TEXT PRIMARY KEY NOT NULL REFERENCES practices (id) ON DELETE CASCADE,
            days INTEGER NOT NULL, longest INTEGER, last_day TEXT NOT NULL, time_zone TEXT NOT NULL, dirty INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE preferences (id INTEGER PRIMARY KEY CHECK (id = 1), onboarded INTEGER NOT NULL, finished_short_refuge INTEGER NOT NULL,
            finished_ngondro INTEGER NOT NULL, mala_size INTEGER NOT NULL, reminder_minutes INTEGER, discreet_notifications INTEGER NOT NULL)""",
        "ALTER TABLE sessions ADD COLUMN updated_at INTEGER",
        "UPDATE sessions SET updated_at = logged_at",
        "ALTER TABLE sessions ADD COLUMN deleted_at INTEGER",
        "CREATE TABLE sync_state (id INTEGER PRIMARY KEY CHECK (id = 1), user_id TEXT NOT NULL, key_version INTEGER NOT NULL, cursor TEXT)",
        """CREATE TABLE friend_pins (user_id TEXT PRIMARY KEY NOT NULL, identity_pk BLOB NOT NULL, display_name TEXT NOT NULL,
            pinned_at INTEGER NOT NULL)""",
    )

    @Test fun aVersion3DatabaseKeepsEveryRow() = runBlocking {
        val friend = UUID.randomUUID()
        val session = UUID.randomUUID()
        val t = 1_790_000_000_000L
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            version3.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO practices (id, name, grp, target, streak_only_allowed) VALUES ('dorje-sempa', 'Dorje Sempa', 'ngondro', 111111, 0)")
            db.execSQL("INSERT INTO sessions (id, practice_id, amount, started_at, start_exact, time_zone, logged_at, updated_at) " +
                "VALUES ('$session', 'dorje-sempa', 108, $t, 1, 'Europe/Warsaw', $t, $t)")
            db.execSQL("INSERT INTO preferences VALUES (1, 1, 1, 0, 108, 1200, 1)")
            db.execSQL("INSERT INTO friend_pins VALUES (?, ?, 'Ania', ?)", arrayOf<Any>(friend.toString(), byteArrayOf(7, 7), t))
            db.version = 3
        }
        val store = SqliteStore(context, name)
        try {
            store.load()
            val snap = store.snapshot.value
            assertEquals(listOf(session), snap.sessions.map { it.id })
            assertEquals(108, snap.sessions.single().amount)
            assertEquals(Instant.ofEpochMilli(t), snap.sessions.single().loggedAt)
            assertEquals(1200, snap.preferences.reminderMinutes)
            assertTrue(snap.preferences.discreetNotifications)
            assertFalse("usual_time_nudge defaults to off", snap.preferences.usualTimeNudge)
            assertTrue(snap.publicPractices.isEmpty())
            val pin = store.pinnedFriends().single()
            assertEquals(friend, pin.user)
            assertEquals("Ania", pin.displayName)
            assertTrue(pin.identityPk.contentEquals(byteArrayOf(7, 7)))
            assertTrue("listed = 1", pin.listed)
            assertEquals(0L, store.seenSeq(friend, "chenrezig"))
        } finally {
            store.close()
        }
    }
}
