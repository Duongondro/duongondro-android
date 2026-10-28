package app.duongondro.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duongondro.account.AccountManager
import app.duongondro.account.AccountStatus
import app.duongondro.push.Push
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.duongondro.core.AfterMidnight
import app.duongondro.core.PendingLog
import app.duongondro.core.Session
import app.duongondro.core.SessionStart
import app.duongondro.core.uuidV7
import app.duongondro.core.Streak
import app.duongondro.core.TrackedPractice
import app.duongondro.core.civilDate
import app.duongondro.core.headline
import app.duongondro.core.of
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A session just made final whose estimated start fell before midnight. */
data class AfterMidnightPrompt(val session: Session, val sheet: AfterMidnight)

/**
 * What the UI shows, from the store, plus the undo window. A session in its
 * window is already in the store, pending (counted on screen, never synced);
 * the window itself, its deadline and Undo, lives here.
 */
class AppModel(
    val store: Store,
    private val clock: () -> Instant = Instant::now,
    context: android.content.Context? = null,
) : ViewModel() {
    /** Every server and key operation; none for previews and tests without a database. */
    val accounts: AccountManager? = (store as? SqliteStore)?.let { s -> context?.let { AccountManager(it, s, viewModelScope, onInvitationUsed = { usedInvite() }) } }

    private val appContext = context?.applicationContext

    val snapshot: StateFlow<Snapshot> = store.snapshot

    private val _pending = MutableStateFlow<PendingLog?>(null)
    /** The open undo window, at most one at a time; its session is in the store, pending. */
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
    private val ordered = Mutex()

    private val _loaded = MutableStateFlow(store !is SqliteStore)
    /** False until the database has been read once, so onboarding never flashes. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _generation = MutableStateFlow(0)
    /** Bumped by "Delete everything", so onboarding starts again from Welcome with no old answers. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val _inviteCode = MutableStateFlow<String?>(null)
    /**
     * An invitation code from an opened link: before onboarding it is kept so the
     * invitation screen is skipped; with an account that has keys, it is offered
     * for accepting.
     */
    val inviteCode: StateFlow<String?> = _inviteCode.asStateFlow()

    private val _magicLink = MutableStateFlow<String?>(null)
    /**
     * The token of a magic link opened on this phone, kept only when this phone
     * asked for a link in the last 15 minutes; only Check your email redeems it.
     */
    val magicLink: StateFlow<String?> = _magicLink.asStateFlow()

    fun openedInvite(code: String) { _inviteCode.value = code }
    /** The opened invitation was accepted or declined by an account that already has its keys. */
    fun usedInvite() { _inviteCode.value = null }
    fun openedMagicLink(token: String) {
        _magicLink.value = token.takeIf { accounts?.expectsLink() == true }
    }
    fun usedMagicLink() { _magicLink.value = null }

    init {
        if (store is SqliteStore) viewModelScope.launch {
            // Shown either way: a failed read must not leave a blank screen.
            try {
                store.load()
                resumeWindows()
                accounts?.load()
            } catch (e: Exception) {
                _storageError.value = e.message ?: e.toString()
            }
            _loaded.value = true
            accounts?.syncNow()
        }
        accounts?.let { a ->
            // Push follows the account: on when it is ready here (and notifications
            // are allowed), never in local mode.
            viewModelScope.launch {
                a.state.map { it.status }.distinctUntilChanged().collect { if (it == AccountStatus.READY) refreshPush() }
            }
            // A friend's push while the app runs: their news is worth fetching.
            viewModelScope.launch { Push.received.collect { a.refreshFriends() } }
        }
    }

    /** Turns push on and sends the token, for an account with keys only; again at every return to the foreground. */
    fun refreshPush() {
        val context = appContext ?: return
        if (accounts?.state?.value?.status == AccountStatus.READY) Push.refresh(context)
        // A left account's token that could not be deleted (offline): tried again, also in local mode.
        else Push.retryDeletion(context)
    }

    /** Back in the foreground: sync, if there is an account. */
    fun resumed() {
        tick()
        if (_loaded.value) viewModelScope.launch { accounts?.syncNow() }
        refreshPush()
    }

    /**
     * Runs the purge in the model's scope: the screen that asked is torn down
     * as soon as the rows are gone, and the purge must still finish.
     */
    fun purge(context: android.content.Context, onFailure: (String) -> Unit) {
        viewModelScope.launch {
            try {
                // The server's copy goes first: if that fails, nothing here is deleted.
                accounts?.deleteOnServer()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AccountManager.SignInToDelete) {
                onFailure(context.getString(app.duongondro.R.string.sign_in_to_delete))
                return@launch
            } catch (e: Exception) {
                onFailure(context.getString(app.duongondro.R.string.account_error))
                return@launch
            }
            try {
                app.duongondro.data.Purge.run(context.applicationContext, this@AppModel)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onFailure(e.message ?: e.toString())
            } finally {
                // The server's copy is gone: the account goes too, even if part of the local purge failed.
                accounts?.forget()
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
        return snapshot.value.sessionsOf(practiceId).any { it.day == today && !it.correction }
    }

    fun malaSize(p: TrackedPractice): Int = p.practice.effectiveMalaSize(snapshot.value.preferences.malaSize)

    // Logging

    /**
     * +mala, another amount or "done today" (0): opens or extends the undo
     * window. The session is written at once, pending, so every number includes
     * it and a crash cannot lose it; it is not synced until the window closes.
     */
    fun add(amount: Int, practiceId: String) {
        val now = clock()
        val p = _pending.value
        val next = if (p != null && p.practiceId == practiceId) p.add(amount, now) else {
            finalisePending()
            PendingLog.open(practiceId, amount, now)
        }
        _pending.value = next
        // Always estimated; the after-midnight sheet corrects a wrong day in one tap.
        val session = next.session(zone, SessionStart.timedLengths(snapshot.value.sessions))
        perform { store.savePending(session, next.deadline) }
        scheduleClose()
    }

    /** Undo: the pending row is deleted outright; it never left the phone. */
    fun undo() {
        closeJob?.cancel()
        val p = _pending.value ?: return
        _pending.value = null
        perform { store.discardPending(p.sessionId) }
    }

    /**
     * Ends the undo window now: it closed, another practice was logged, or the
     * app went to the background. The session becomes final and syncs.
     */
    fun finalisePending() {
        closeJob?.cancel()
        val p = _pending.value ?: return
        _pending.value = null
        perform {
            val session = store.finalise(p.sessionId) ?: return@perform
            AfterMidnight.check(session)?.let { _afterMidnight.value = AfterMidnightPrompt(session, it) }
            accounts?.scheduleSync()
        }
    }

    /** Finalises the pending session and waits for the write, for the export. */
    suspend fun finalisePendingNow() {
        finalisePending()
        writes.lastOrNull()?.join()
    }

    /**
     * On launch: a pending row whose window passed while the app was gone (a
     * crash, or the system ending the process) becomes final now; one still
     * inside its window gets its Undo back for the seconds left.
     */
    private suspend fun resumeWindows() {
        val finalised = store.finaliseDue(clock())
        // One sheet at a time: the latest finalised session that asks for one.
        finalised.sortedByDescending { it.loggedAt }
            .firstNotNullOfOrNull { s -> AfterMidnight.check(s)?.let { AfterMidnightPrompt(s, it) } }
            ?.let { _afterMidnight.value = it }
        val snap = snapshot.value
        val open = snap.pending.entries.mapNotNull { (id, until) -> snap.sessions.firstOrNull { it.id == id }?.let { it to until } }
        // At most one window: any older one closes now.
        val newest = open.maxByOrNull { (s, _) -> s.loggedAt }
        open.filter { it !== newest }.forEach { (s, _) -> store.finalise(s.id) }
        if (newest != null) {
            val (s, until) = newest
            _pending.value = PendingLog(s.practiceId, s.amount, s.loggedAt, until, s.id)
            scheduleClose()
        }
    }

    /** Drops the undo window without finalising it (the purge erases its row with everything else). */
    fun discardInFlight() {
        closeJob?.cancel()
        _pending.value = null
        _afterMidnight.value = null
    }

    fun choose(day: LocalDate, prompt: AfterMidnightPrompt) {
        val startDay = civilDate(prompt.session.startedAt, prompt.session.zone)
        _afterMidnight.value = null
        perform {
            store.choose(if (day == startDay) null else day, prompt.session.id)
            accounts?.scheduleSync()
        }
    }

    fun dismissAfterMidnight() { _afterMidnight.value = null }

    fun dismissStorageError() { _storageError.value = null }

    private fun scheduleClose() {
        closeJob?.cancel()
        val deadline = _pending.value?.deadline ?: return
        closeJob = viewModelScope.launch {
            val wait = Duration.between(clock(), deadline)
            if (!wait.isNegative) delay(wait.toMillis())
            finalisePending()
        }
    }

    /**
     * Corrects a practice's count to [lifetime] (design decision 34): the change is
     * saved as a correction session, final at once, and syncs like any session. It
     * counts toward the total and rounds, never a day or the streak. No lower limit.
     */
    fun correctCount(practiceId: String, lifetime: Int) {
        val s = snapshot.value
        val p = s.practices.firstOrNull { it.id == practiceId } ?: return
        val now = clock()
        // Against the unclamped total, so the shown total ends up exactly at [lifetime].
        val change = lifetime - p.unclampedLifetime(s.sessions)
        if (change == 0) return
        finalisePending()
        val session = Session(id = uuidV7(now), practiceId = practiceId, amount = change, startedAt = now, startExact = true,
            zoneId = zone.id, loggedAt = now, correction = true)
        perform {
            store.insert(session)
            accounts?.scheduleSync()
        }
    }

    // Practices and preferences

    fun save(p: TrackedPractice) = perform { store.save(p) }

    fun updatePreferences(change: (Preferences) -> Preferences) = perform { store.save(change(snapshot.value.preferences)) }

    /** Runs `block` in the write queue, after every write asked for before it (the purge's erase). */
    suspend fun <T> inOrder(block: suspend () -> T): T = withContext(NonCancellable) { ordered.withLock { block() } }

    /**
     * Writes run one at a time, in the order asked: an Undo right after a tap
     * must never reach the database before the tap's own write.
     */
    fun perform(write: suspend () -> Unit) {
        // A write asked for lands even when the scope is cancelled while it
        // waits its turn (the app closing right after a tap).
        val job = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                ordered.withLock {
                    try { write() } catch (e: Exception) { _storageError.value = e.message ?: e.toString() }
                }
            }
        }
        writes.removeAll { it.isCompleted }
        writes += job
    }
}
