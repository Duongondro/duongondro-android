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
 * Duongondro/duongondro-android#5 through the model: a tap is counted at once,
 * Undo leaves no row, the window's close makes the session final, and a launch
 * after a crash finalises (or reopens) what the window left behind.
 */
@RunWith(AndroidJUnit4::class)
class AppModelPendingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "model-${System.nanoTime()}.db"
    private val stores = mutableListOf<SqliteStore>()

    private fun store() = SqliteStore(context, name).also { stores += it }

    private fun model(store: SqliteStore, clock: () -> Instant = Instant::now): AppModel {
        var m: AppModel? = null
        instrumentation.runOnMainSync { m = AppModel(store, clock) }
        await { m!!.loaded.value }
        return m!!
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Before fun practice(): Unit = runBlocking {
        store().apply { save(TrackedPractice(Catalogue.builtIn.first { it.id == "dorje-sempa" })); close() }
    }

    @After fun delete() {
        stores.forEach { it.close() }
        context.deleteDatabase(name)
    }

    @Test fun aTapCountsAtOnceAndUndoLeavesNoRow() = runBlocking<Unit> {
        val store = store()
        val m = model(store)
        main { m.add(108, "dorje-sempa") }
        await { store.snapshot.value.sessions.isNotEmpty() }
        assertEquals(108, store.snapshot.value.sessions.single().amount)
        assertNotNull(m.pending.value)
        assertTrue(store.dirtySessions().isEmpty())
        main { m.add(108, "dorje-sempa") }
        await { store.snapshot.value.sessions.singleOrNull()?.amount == 216 }
        main { m.undo() }
        await { store.snapshot.value.sessions.isEmpty() }
        assertNull(m.pending.value)
        assertTrue(store.snapshot.value.pending.isEmpty())
        assertTrue(store.dirtySessions().isEmpty())
    }

    @Test fun theWindowClosingMakesTheSessionFinal() = runBlocking<Unit> {
        val store = store()
        val m = model(store)
        main { m.add(108, "dorje-sempa") }
        await { store.snapshot.value.pending.isNotEmpty() }
        await(PendingLog.WINDOW.toMillis() + 5_000) { store.snapshot.value.pending.isEmpty() }
        assertNull(m.pending.value)
        assertEquals(listOf(108), store.dirtySessions().map { it.session.amount })
    }

    @Test fun aLaunchAfterTheWindowFinalisesTheTap() = runBlocking<Unit> {
        val t0 = Instant.ofEpochMilli(System.currentTimeMillis())
        val w = PendingLog.open("dorje-sempa", 108, t0)
        store().apply { savePending(w.session(ZoneId.systemDefault(), emptyList()), w.deadline); close() }  // then the crash
        val store = store()
        val m = model(store) { t0.plusSeconds(30) }
        assertNull(m.pending.value)
        assertTrue(store.snapshot.value.pending.isEmpty())
        assertEquals(listOf(w.sessionId), store.dirtySessions().map { it.session.id })
    }

    @Test fun aLaunchInsideTheWindowGivesUndoBack() = runBlocking<Unit> {
        val t0 = Instant.ofEpochMilli(System.currentTimeMillis())
        val w = PendingLog.open("dorje-sempa", 108, t0)
        store().apply { savePending(w.session(ZoneId.systemDefault(), emptyList()), w.deadline); close() }
        val store = store()
        val m = model(store)
        val back = m.pending.value
        assertNotNull(back)
        assertEquals(w.sessionId, back!!.sessionId)
        assertEquals(w.deadline, back.deadline)
        main { m.undo() }
        await { store.snapshot.value.sessions.isEmpty() }
    }
}
