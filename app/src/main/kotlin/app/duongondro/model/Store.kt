package app.duongondro.model

import app.duongondro.core.Session
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import app.duongondro.core.PendingLog
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Everything the UI shows, read together. */
data class Snapshot(
    val practices: List<TrackedPractice> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val seeds: List<StreakSeed> = emptyList(),
    val preferences: Preferences = Preferences(),
    /** Practices whose streak friends see. */
    val publicPractices: Set<String> = emptySet(),
    /**
     * Sessions still in their undo window, with when it closes. They are in
     * [sessions], so every number on screen includes them; nothing that leaves
     * the phone (sync, public streaks, the export) does.
     */
    val pending: Map<UUID, Instant> = emptyMap(),
) {
    val activePractices: List<TrackedPractice> get() = practices.filter { !it.archived }
    fun sessionsOf(practiceId: String): List<Session> = sessions.filter { it.practiceId == practiceId }

    /** Final sessions only: what may leave the phone. */
    fun withoutPending(): Snapshot =
        if (pending.isEmpty()) this else copy(sessions = sessions.filter { it.id !in pending }, pending = emptyMap())
    fun seedOf(practiceId: String): StreakSeed? = seeds.firstOrNull { it.practiceId == practiceId }
}

/** Settings kept on the phone. Never synced as plaintext. */
data class Preferences(
    val onboarded: Boolean = false,
    val finishedShortRefuge: Boolean = false,
    val finishedNgondro: Boolean = false,
    /** Whether one mala counts as 100 or 108; each practice can override it. */
    val malaSize: Int = 108,
    /** Minutes after local midnight for the streak-at-risk reminder; null for none. */
    val reminderMinutes: Int? = null,
    val discreetNotifications: Boolean = false,
    /** "Nudge at my usual time": an hour before the time each practice is usually logged. */
    val usualTimeNudge: Boolean = false,
)

/** The local database: every write goes through here, the UI observes `snapshot`. */
interface Store {
    val snapshot: StateFlow<Snapshot>
    suspend fun save(practice: TrackedPractice)
    suspend fun insert(session: Session)

    /**
     * Writes a session in its undo window, or updates it when the window is
     * extended: shown everywhere at once, never synced while pending.
     */
    suspend fun savePending(session: Session, until: Instant)

    /** Undo: deletes a pending session outright. It never left the phone, so it needs no tombstone. */
    suspend fun discardPending(id: UUID)

    /** The window closed: the session becomes final and dirty, so it syncs. Null if it is gone or already final. */
    suspend fun finalise(id: UUID): Session?

    /** On launch: pending sessions whose window is over ([PendingLog.isOver]) become final; returns them. */
    suspend fun finaliseDue(now: Instant): List<Session>
    suspend fun choose(day: LocalDate?, forSession: UUID)
    suspend fun save(preferences: Preferences)
    suspend fun completeOnboarding(practices: List<TrackedPractice>, seeds: List<StreakSeed>, preferences: Preferences)
    suspend fun eraseAll()
}

/** For previews and tests. */
class InMemoryStore(initial: Snapshot = Snapshot()) : Store {
    private val state = MutableStateFlow(initial)
    override val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    override suspend fun save(practice: TrackedPractice) = state.update { s ->
        s.copy(practices = (s.practices.filter { it.id != practice.id } + practice).sortedBy { it.sortOrder })
    }

    override suspend fun insert(session: Session) {
        require(state.value.practices.any { it.id == session.practiceId }) { "unknown practice ${session.practiceId}" }
        state.update { it.copy(sessions = (it.sessions + session).sortedBy { s -> s.startedAt }) }
    }

    override suspend fun savePending(session: Session, until: Instant) {
        require(state.value.practices.any { it.id == session.practiceId }) { "unknown practice ${session.practiceId}" }
        state.update { s ->
            s.copy(sessions = (s.sessions.filter { it.id != session.id } + session).sortedBy { it.startedAt },
                pending = s.pending + (session.id to until))
        }
    }

    override suspend fun discardPending(id: UUID) = state.update { s ->
        if (id !in s.pending) s else s.copy(sessions = s.sessions.filter { it.id != id }, pending = s.pending - id)
    }

    override suspend fun finalise(id: UUID): Session? {
        val s = state.value
        if (id !in s.pending) return null
        state.update { it.copy(pending = it.pending - id) }
        return s.sessions.firstOrNull { it.id == id }
    }

    override suspend fun finaliseDue(now: Instant): List<Session> =
        state.value.pending.filter { (_, until) -> PendingLog.isOver(until, now) }.keys.mapNotNull { finalise(it) }

    override suspend fun choose(day: LocalDate?, forSession: UUID) = state.update { s ->
        s.copy(sessions = s.sessions.map { if (it.id == forSession) it.copy(chosenDay = day) else it })
    }

    override suspend fun save(preferences: Preferences) = state.update { it.copy(preferences = preferences) }

    override suspend fun completeOnboarding(practices: List<TrackedPractice>, seeds: List<StreakSeed>, preferences: Preferences) =
        state.update { it.copy(practices = practices.sortedBy { p -> p.sortOrder }, seeds = seeds, preferences = preferences) }

    override suspend fun eraseAll() { state.value = Snapshot() }
}
