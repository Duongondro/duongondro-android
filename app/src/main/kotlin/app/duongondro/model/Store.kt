package app.duongondro.model

import app.duongondro.core.Session
import app.duongondro.core.StreakSeed
import app.duongondro.core.TrackedPractice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.UUID

/** Everything the UI shows, read together. */
data class Snapshot(
    val practices: List<TrackedPractice> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val seeds: List<StreakSeed> = emptyList(),
    val preferences: Preferences = Preferences(),
) {
    val activePractices: List<TrackedPractice> get() = practices.filter { !it.archived }
    fun sessionsOf(practiceId: String): List<Session> = sessions.filter { it.practiceId == practiceId }
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
)

/** The local database: every write goes through here, the UI observes `snapshot`. */
interface Store {
    val snapshot: StateFlow<Snapshot>
    suspend fun save(practice: TrackedPractice)
    suspend fun insert(session: Session)
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

    override suspend fun choose(day: LocalDate?, forSession: UUID) = state.update { s ->
        s.copy(sessions = s.sessions.map { if (it.id == forSession) it.copy(chosenDay = day) else it })
    }

    override suspend fun save(preferences: Preferences) = state.update { it.copy(preferences = preferences) }

    override suspend fun completeOnboarding(practices: List<TrackedPractice>, seeds: List<StreakSeed>, preferences: Preferences) =
        state.update { Snapshot(practices.sortedBy { p -> p.sortOrder }, it.sessions, seeds, preferences) }

    override suspend fun eraseAll() { state.value = Snapshot() }
}
