package app.duongondro.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duongondro.core.AfterMidnight
import app.duongondro.core.PendingLog
import app.duongondro.core.Session
import app.duongondro.core.SessionStart
import app.duongondro.core.Streak
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.core.headline
import app.duongondro.core.of
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A session just written whose estimated start fell before midnight. */
data class AfterMidnightPrompt(val session: Session, val sheet: AfterMidnight)

/**
 * What the UI shows, from the store, plus the in-memory state that must never
 * reach it early: the undo window.
 */
class AppModel(val store: Store, private val clock: () -> Instant = Instant::now) : ViewModel() {
    val snapshot: StateFlow<Snapshot> = store.snapshot

    private val _pending = MutableStateFlow<PendingLog?>(null)
    /** The open undo window, at most one at a time. */
    val pending: StateFlow<PendingLog?> = _pending.asStateFlow()

    private val _afterMidnight = MutableStateFlow<AfterMidnightPrompt?>(null)
    val afterMidnight: StateFlow<AfterMidnightPrompt?> = _afterMidnight.asStateFlow()

    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    private val _now = MutableStateFlow(clock())
    /** Bumped on returning to the foreground and at a new day, so "today" re-renders. */
    val now: StateFlow<Instant> = _now.asStateFlow()

    /** Writes in flight; declared before init, which already writes. */
    private val writes = mutableListOf<Job>()
    private var closeJob: Job? = null

    private val _loaded = MutableStateFlow(store !is SqliteStore)
    /** False until the database has been read once, so onboarding never flashes. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _generation = MutableStateFlow(0)
    /** Bumped by "Delete everything", so onboarding starts again from Welcome with no old answers. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    init {
        if (store is SqliteStore) viewModelScope.launch {
            // Shown either way: a failed read must not leave a blank screen.
            try { store.load() } catch (e: Exception) { _storageError.value = e.message ?: e.toString() }
            _loaded.value = true
        }
    }

    /**
     * Runs the purge in the model's scope: the screen that asked is torn down
     * as soon as the rows are gone, and the purge must still finish.
     */
    fun purge(context: android.content.Context, onFailure: (String) -> Unit) {
        viewModelScope.launch {
            try {
                app.duongondro.data.Purge.run(context.applicationContext, this@AppModel)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            } finally {
                _generation.value += 1
            }
        }
    }

    override fun onCleared() {
        (store as? SqliteStore)?.close()
    }

    fun tick() { _now.value = clock() }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    // Derived

    fun streak(practiceId: String, at: Instant = clock()): Streak.Result {
        val s = snapshot.value
        return Streak.of(practiceId, s.sessionsOf(practiceId), s.seedOf(practiceId), at, zone)
    }

    fun headline(at: Instant = clock()): Streak.Result {
        val s = snapshot.value
        return Streak.headline(s.sessions, s.seeds, at, zone)
    }

    fun practisedToday(practiceId: String, at: Instant = clock()): Boolean {
        val today = civilDate(at, zone)
        return snapshot.value.sessionsOf(practiceId).any { it.day == today }
    }

    fun malaSize(p: TrackedPractice): Int = p.practice.effectiveMalaSize(snapshot.value.preferences.malaSize)

    // Logging

    /** +mala, another amount or "done today" (0): opens or extends the undo window. */
    fun add(amount: Int, practiceId: String) {
        val now = clock()
        val p = _pending.value
        if (p != null && p.practiceId == practiceId) {
            _pending.value = p.add(amount, now)
        } else {
            commitPending()
            _pending.value = PendingLog.open(practiceId, amount, now)
        }
        scheduleClose()
    }

    /** Undo: the pending session vanishes without a trace. */
    fun undo() {
        closeJob?.cancel()
        _pending.value = null
    }

    /** Writes the pending session now: the window closed, another practice was logged, or the app went to the background. */
    fun commitPending() {
        closeJob?.cancel()
        val p = _pending.value ?: return
        _pending.value = null
        // Always estimated; the after-midnight sheet corrects a wrong day in one tap.
        val startedAt = SessionStart.estimate(p.openedAt, null, SessionStart.timedLengths(snapshot.value.sessions))
        val session = Session(practiceId = p.practiceId, amount = p.amount, startedAt = startedAt,
            startExact = false, zoneId = zone.id, loggedAt = p.openedAt)
        perform {
            store.insert(session)
            AfterMidnight.check(session)?.let { _afterMidnight.value = AfterMidnightPrompt(session, it) }
        }
    }

    /** Commits the pending session and waits for the write, for the export. */
    suspend fun commitPendingNow() {
        commitPending()
        writes.lastOrNull()?.join()
    }

    /** Drops the undo window without writing anything. */
    fun discardInFlight() {
        closeJob?.cancel()
        _pending.value = null
        _afterMidnight.value = null
    }

    fun choose(day: LocalDate, prompt: AfterMidnightPrompt) {
        val startDay = civilDate(prompt.session.startedAt, prompt.session.zone)
        _afterMidnight.value = null
        perform { store.choose(if (day == startDay) null else day, prompt.session.id) }
    }

    fun dismissAfterMidnight() { _afterMidnight.value = null }

    fun dismissStorageError() { _storageError.value = null }

    private fun scheduleClose() {
        closeJob?.cancel()
        val deadline = _pending.value?.deadline ?: return
        closeJob = viewModelScope.launch {
            val wait = Duration.between(clock(), deadline)
            if (!wait.isNegative) delay(wait.toMillis())
            commitPending()
        }
    }

    // Practices and preferences

    fun save(p: TrackedPractice) = perform { store.save(p) }

    fun updatePreferences(change: (Preferences) -> Preferences) = perform { store.save(change(snapshot.value.preferences)) }

    fun perform(write: suspend () -> Unit) {
        val job = viewModelScope.launch {
            try { write() } catch (e: Exception) { _storageError.value = e.message ?: e.toString() }
        }
        writes.removeAll { it.isCompleted }
        writes += job
    }
}
