package app.duongondro.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duongondro.core.Catalogue
import app.duongondro.core.PendingLog
import app.duongondro.core.TrackedPractice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * Duongondro/duongondro-android#5: a session in its undo window is in the
 * database at once, pending; it counts on screen, never syncs, and Undo
 * deletes it. A pending row outlives a crash and becomes final on launch.
 */
@RunWith(AndroidJUnit4::class)
class PendingSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "pending-${System.nanoTime()}.db"
    private lateinit var store: SqliteStore
    private val t0 = Instant.ofEpochMilli(1_790_000_000_000)
    private val zone = ZoneId.of("Europe/Warsaw")

    @Before fun open() = runBlocking {
        store = SqliteStore(context, name)
        store.save(TrackedPractice(Catalogue.builtIn.first { it.id == "dorje-sempa" }))
    }

    @After fun close() {
        store.close()
        context.deleteDatabase(name)
    }

    private fun window(amount: Int = 108, at: Instant = t0) = PendingLog.open("dorje-sempa", amount, at)

    private fun rows(): Int = context.openOrCreateDatabase(name, 0, null).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM sessions", null).use { it.moveToFirst(); it.getInt(0) }
    }

    @Test fun aPendingSessionCountsButNeverSyncsUntilFinal() = runBlocking {
        val w = window()
        store.savePending(w.session(zone, emptyList()), w.deadline)
        val snap = store.snapshot.value
        assertEquals("counted at once", 108, snap.practices.single().lifetime(snap.sessions))
        assertEquals(mapOf(w.sessionId to w.deadline), snap.pending)
        assertTrue("never pushed while pending", store.dirtySessions().isEmpty())
        assertTrue("never in a public streak while pending", store.sessionsOf("dorje-sempa").isEmpty())
        store.markAllDirty()
        assertTrue("not even after a server restore", store.dirtySessions().isEmpty())
        store.clearSyncState()
        assertTrue("nor after signing out", store.dirtySessions().isEmpty())

        val final = store.finalise(w.sessionId)
        assertNotNull(final)
        assertEquals(108, final!!.amount)
        assertTrue(store.snapshot.value.pending.isEmpty())
        assertEquals(listOf(w.sessionId), store.dirtySessions().map { it.session.id })
        assertEquals(listOf(w.sessionId), store.sessionsOf("dorje-sempa").map { it.id })
        assertNull("finalising twice changes nothing", store.finalise(w.sessionId))
    }

    @Test fun extendingTheWindowUpdatesTheSameRow() = runBlocking {
        var w = window()
        store.savePending(w.session(zone, emptyList()), w.deadline)
        w = w.add(108, t0.plusSeconds(3))
        store.savePending(w.session(zone, emptyList()), w.deadline)
        val snap = store.snapshot.value
        assertEquals(1, snap.sessions.size)
        assertEquals(216, snap.sessions.single().amount)
        assertEquals(w.deadline, snap.pending[w.sessionId])
    }

    @Test fun undoDeletesThePendingRowOutright() = runBlocking {
        val w = window()
        store.savePending(w.session(zone, emptyList()), w.deadline)
        store.discardPending(w.sessionId)
        assertTrue(store.snapshot.value.sessions.isEmpty())
        assertTrue(store.snapshot.value.pending.isEmpty())
        assertEquals("no tombstone either", 0, rows())
        assertTrue(store.dirtySessions().isEmpty())
    }

    @Test fun aFinalSessionIsNeitherUndoneNorReopened() = runBlocking {
        val w = window()
        store.savePending(w.session(zone, emptyList()), w.deadline)
        store.finalise(w.sessionId)
        store.discardPending(w.sessionId)
        store.savePending(w.add(108, t0.plusSeconds(1)).session(zone, emptyList()), w.deadline)
        assertEquals(108, store.snapshot.value.sessions.single().amount)
        assertTrue(store.snapshot.value.pending.isEmpty())
    }

    @Test fun aRestartFinalisesWhatItsWindowLeftBehind() = runBlocking {
        val gone = window(108, t0)
        val open = window(216, t0.plusSeconds(10))
        store.savePending(gone.session(zone, emptyList()), gone.deadline)
        store.savePending(open.session(zone, emptyList()), open.deadline)
        // The process dies here.
        store.close()
        store = SqliteStore(context, name)
        store.load()
        assertEquals(2, store.snapshot.value.pending.size)

        val finalised = store.finaliseDue(t0.plusSeconds(12))
        assertEquals(listOf(gone.sessionId), finalised.map { it.id })
        assertEquals("the tap is kept", setOf(108, 216), store.snapshot.value.sessions.map { it.amount }.toSet())
        assertEquals(listOf(gone.sessionId), store.dirtySessions().map { it.session.id })
        assertEquals(setOf(open.sessionId), store.snapshot.value.pending.keys)

        assertEquals("a clock moved back cannot hold it", listOf(open.sessionId), store.finaliseDue(t0.minusSeconds(3600)).map { it.id })
        assertTrue(store.snapshot.value.pending.isEmpty())
    }
}
