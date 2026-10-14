package app.duongondro.core.sync

import app.duongondro.core.isV7
import app.duongondro.core.uuidV7
import java.time.Instant
import java.util.UUID

/**
 * The store's sync and social rules in memory, as SqliteStore applies them: the
 * newer write wins, to the millisecond; the first key seen is pinned; seqs only grow.
 */
class MemorySyncDatabase : SyncDatabase, SocialDatabase {
    class Row(var record: SyncRecord, var dirty: Boolean, var practiceName: String? = null)

    val rows = linkedMapOf<UUID, Row>()
    val practices = mutableSetOf<String>()
    private var state: SyncState? = null
    override var generation = 0

    fun insert(record: SyncRecord) {
        practices += record.session.practiceId
        rows[record.session.id] = Row(record, dirty = true)
    }

    fun erase() {
        rows.clear(); practices.clear(); state = null; generation++
    }

    private fun check(g: Int?) { if (g != null && g != generation) throw Erased() }

    override suspend fun syncState() = state
    override suspend fun saveSyncState(state: SyncState, generation: Int?) { check(generation); this.state = state }
    override suspend fun dirtySessions() = rows.values.filter { it.dirty }.map { it.record }.sortedBy { it.updatedAt }
    override suspend fun markSynced(id: UUID, updatedAt: Instant, generation: Int) {
        check(generation)
        rows[id]?.takeIf { it.record.updatedAt == updatedAt }?.dirty = false
    }
    override suspend fun markAllDirty() = rows.values.forEach { it.dirty = true }
    override suspend fun rekeyLegacySessionIds() {
        rows.values.filter { !it.record.session.id.isV7 }.forEach { row ->
            rows.remove(row.record.session.id)
            val id = uuidV7(row.record.session.loggedAt)
            row.record = row.record.copy(session = row.record.session.copy(id = id))
            row.dirty = true
            rows[id] = row
        }
    }
    override suspend fun applyRemote(record: SyncRecord, practiceName: String?, generation: Int): Boolean {
        check(generation)
        val local = rows[record.session.id]
        if (local != null && local.record.updatedAt.syncMillis() >= record.updatedAt.syncMillis()) return false
        if (local == null && record.deletedAt != null) return false
        practices += record.session.practiceId
        rows[record.session.id] = Row(record, dirty = false, practiceName)
        return true
    }
    override suspend fun applyRemoteDeletion(id: UUID, updatedAt: Instant, deletedAt: Instant, generation: Int): Boolean {
        check(generation)
        val local = rows[id] ?: return false
        if (local.record.updatedAt.syncMillis() >= updatedAt.syncMillis()) return false
        local.record = local.record.copy(updatedAt = updatedAt, deletedAt = deletedAt)
        local.dirty = false
        return true
    }
    override suspend fun customNames() = emptyMap<String, String>()

    // Social

    val pins = linkedMapOf<UUID, PinnedFriend>()
    val seqs = mutableMapOf<Pair<UUID, String>, Long>()
    val public = mutableMapOf<String, Long>()

    override suspend fun pinnedFriends() = pins.values.sortedWith(compareBy({ it.displayName }, { it.user.toString() }))
    override suspend fun pin(user: UUID, identityPk: ByteArray, displayName: String, at: Instant, generation: Int?): PinnedFriend {
        check(generation)
        val old = pins[user]
        return PinnedFriend(user, old?.identityPk ?: identityPk, displayName, old?.pinnedAt ?: at, listed = true).also { pins[user] = it }
    }
    override suspend fun repin(user: UUID, identityPk: ByteArray, displayName: String, at: Instant, generation: Int?) {
        check(generation)
        pins[user] = PinnedFriend(user, identityPk, displayName.ifEmpty { pins[user]?.displayName.orEmpty() }, at, listed = true)
    }
    override suspend fun keepFriends(listed: Set<UUID>, generation: Int?) {
        check(generation)
        pins.replaceAll { id, p -> PinnedFriend(p.user, p.identityPk, p.displayName, p.pinnedAt, id in listed) }
    }
    override suspend fun forgetFriend(user: UUID) {
        pins.remove(user)
        seqs.keys.removeAll { it.first == user }
    }
    override suspend fun seenSeq(user: UUID, practice: String) = seqs[user to practice] ?: 0L
    override suspend fun noteSeq(user: UUID, practice: String, seq: Long, generation: Int?) {
        check(generation)
        seqs[user to practice] = maxOf(seqs[user to practice] ?: 0L, seq)
    }
    override suspend fun publicStreakSeqs() = public.toMap()
    override suspend fun setPublic(practice: String, isPublic: Boolean) {
        if (isPublic) public.putIfAbsent(practice, 0L) else public.remove(practice)
    }
    override suspend fun isPublic(practice: String) = practice in public
    override suspend fun savePublishedSeq(practice: String, seq: Long, generation: Int?) {
        check(generation)
        public.computeIfPresent(practice) { _, old -> maxOf(old, seq) }
    }
    override suspend fun mergePublished(seqs: Map<String, Long>, generation: Int?) {
        check(generation)
        seqs.filterKeys { it in practices }.forEach { (p, seq) -> public[p] = maxOf(public[p] ?: 0L, seq) }
        public.entries.removeAll { (p, seq) -> seq > 0 && p !in seqs }
    }
    override suspend fun sessionsOf(practice: String) =
        rows.values.map { it.record }.filter { it.deletedAt == null && it.session.practiceId == practice }.map { it.session }
}
