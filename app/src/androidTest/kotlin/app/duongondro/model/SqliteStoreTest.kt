package app.duongondro.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duongondro.core.Catalogue
import app.duongondro.core.Session
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import app.duongondro.core.parseCivilDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SqliteStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-${System.nanoTime()}.db"
    private lateinit var store: SqliteStore
    private val ams = "Europe/Amsterdam"
    private fun pick(id: String) = Catalogue.builtIn.first { it.id == id }

    @Before fun open() { store = SqliteStore(context, name) }

    @After fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    @Test fun roundTrip() = runBlocking {
        val prefs = Preferences(onboarded = true, malaSize = 100, reminderMinutes = 20 * 60)
        val ds = TrackedPractice(pick("dorje-sempa"), openingCount = 1_000, sortOrder = 0)
        val ch = TrackedPractice(pick("chenrezig"), wantsStreakOnly = true, sortOrder = 1)
        val seed = StreakSeed("dorje-sempa", 12, 30, parseCivilDate("2026-10-03")!!, ams)
        store.completeOnboarding(listOf(ch, ds), listOf(seed), prefs)

        val t = Instant.ofEpochMilli(1_790_000_000_123)
        val s = Session(practiceId = "dorje-sempa", amount = 108, startedAt = t, startExact = false, zoneId = ams, loggedAt = t.plusSeconds(3600))
        store.insert(s)
        store.choose(parseCivilDate("2026-10-05"), s.id)

        // A fresh store reads everything back from disk.
        store.close()
        store = SqliteStore(context, name)
        store.load()
        val snap = store.snapshot.value
        assertEquals(listOf("dorje-sempa", "chenrezig"), snap.practices.map { it.id })
        assertEquals(1_000, snap.practices[0].openingCount)
        assertTrue(snap.practices[1].streakOnly)
        assertEquals(listOf(seed), snap.seeds)
        assertEquals(prefs, snap.preferences)
        assertEquals(s.copy(chosenDay = parseCivilDate("2026-10-05")), snap.sessions.single())
        assertEquals(1_108, snap.practices[0].lifetime(snap.sessions))
    }

    @Test fun archiveKeepsHistory() = runBlocking {
        val ds = TrackedPractice(pick("dorje-sempa"))
        store.save(ds)
        store.insert(Session(practiceId = ds.id, amount = 108, startedAt = Instant.now(), startExact = true, zoneId = ams, loggedAt = Instant.now()))
        store.save(ds.copy(archived = true))
        val snap = store.snapshot.value
        assertTrue(snap.activePractices.isEmpty())
        assertEquals(1, snap.sessionsOf(ds.id).size)
    }

    @Test(expected = Exception::class)
    fun sessionNeedsItsPractice(): Unit = runBlocking {
        store.insert(Session(practiceId = "nope", amount = 1, startedAt = Instant.now(), startExact = true, zoneId = ams, loggedAt = Instant.now()))
    }

    /** Adding a table without a place in the export and the purge fails here. */
    @Test fun eraseAllCoversEveryTableAndLeavesNoTrace() = runBlocking {
        assertEquals(listOf("friend_pins", "practices", "preferences", "sessions", "streak_seeds", "sync_state"), store.tables())
        store.completeOnboarding(listOf(TrackedPractice(pick("mandala"))),
            listOf(StreakSeed("mandala", 1, null, parseCivilDate("2026-10-03")!!, ams)), Preferences(onboarded = true))
        store.insert(Session(practiceId = "mandala", amount = 777_001, startedAt = Instant.now(), startExact = true, zoneId = ams, loggedAt = Instant.now()))
        store.eraseAll()
        assertEquals(Snapshot(), store.snapshot.value)
        assertNull(store.snapshot.value.seedOf("mandala"))
        // Scanned while the store is open: closing would checkpoint and hide a WAL left behind.
        val path = context.getDatabasePath(name).path
        for (suffix in listOf("", "-wal")) {
            val f = File(path + suffix)
            if (f.exists()) assertTrue("no trace in $suffix", !String(f.readBytes(), Charsets.ISO_8859_1).contains("mandala"))
        }
    }
}
