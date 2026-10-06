package app.duongondro.core.sync

import app.duongondro.core.isV7
import app.duongondro.core.uuidV7
import java.time.Instant
import java.util.UUID

/** The store's sync rules in memory, as SqliteStore applies them: the newer write wins, to the millisecond. */
class MemorySyncDatabase : SyncDatabase {
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
}
