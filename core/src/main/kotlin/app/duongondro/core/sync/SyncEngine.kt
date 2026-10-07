package app.duongondro.core.sync

import app.duongondro.core.api.ApiError
import app.duongondro.core.api.PracticeLog
import app.duongondro.core.api.PracticeLogInput

/**
 * Push, then pull, as iOS's SyncEngine: every session changed here goes up
 * sealed under the practice key, then everything changed elsewhere since the
 * cursor comes down and is opened here. Last write wins on the client clock,
 * compared to the millisecond.
 */
class SyncEngine(private val account: Account) {
    data class Result(
        var pushed: Int = 0,
        var pulled: Int = 0,
        /** Logs this phone holds no key for; the cursor stays put until they open. */
        var unreadable: Int = 0,
        /** Sessions the server refused, and logs whose sealed time disagreed with the server's (a replay); skipped. */
        var refused: Int = 0,
    )

    suspend fun sync(): Result {
        val db = account.database
        // Every write names this generation, so a sync running across an erase
        // or a sign-out never writes the old account back.
        val generation = db.generation
        var state = db.syncState() ?: throw AccountKeys.Error(AccountKeys.Failure.ACCOUNT_HAS_NO_KEYS)
        val result = Result()

        // Keys first: a version another phone rotated to is needed both to open
        // what it sealed and to seal what goes up.
        val newest = account.receiveNewerKeys(state.user)
        if (newest > state.keyVersion) {
            state = state.copy(keyVersion = newest)
            db.saveSyncState(state, generation)
        }
        val names = db.customNames()

        db.rekeyLegacySessionIds()
        state = push(state, result, names, generation)

        val hadCursor = state.cursor != null
        val page = account.api.sync(state.cursor)
        for (log in page.logs) {
            when (apply(log, state, generation)) {
                Applied.APPLIED -> result.pulled++
                Applied.UNCHANGED -> {}
                Applied.UNREADABLE -> result.unreadable++
                Applied.REFUSED -> result.refused++
            }
        }
        // A log that would not open is asked for again next time, by not moving past it.
        if (result.unreadable == 0) {
            state = state.copy(cursor = page.cursor)
            db.saveSyncState(state, generation)
        }
        // A full answer to a sync that had a cursor means the server lost history
        // (a restore from backup): what it lost may live only here, so send it all.
        if (page.full && hadCursor) {
            db.markAllDirty()
            push(state, result, names, generation)
        }
        return result
    }

    private suspend fun push(initial: SyncState, result: Result, names: Map<String, String>, generation: Int): SyncState {
        val db = account.database
        var state = initial
        for (record in db.dirtySessions()) {
            val stored: PracticeLog
            try {
                stored = try {
                    put(record, state, names)
                } catch (e: ApiError.OldKey) {
                    // Another phone rotated the key since the check above: take
                    // the new one from our wraps and send this session again.
                    val version = account.receiveNewerKeys(state.user)
                    if (version < e.currentKeyVersion) throw AccountKeys.Error(AccountKeys.Failure.MISSING_SECRET)
                    state = state.copy(keyVersion = version)
                    db.saveSyncState(state, generation)
                    put(record, state, names)
                }
            } catch (e: ApiError) {
                if (!isPerRecord(e)) throw e
                // This one session was refused; it stays dirty and the rest goes on.
                result.refused++
                continue
            }
            val storedAt = SyncTime.parse(stored.updatedAt)?.syncMillis()
            if (storedAt == null) {
                // An answer this phone cannot read proves nothing was stored: stays dirty.
                result.refused++
                continue
            }
            if (storedAt > record.updatedAt.syncMillis()) {
                // The server kept a newer write from another phone: take that one.
                if (apply(stored, state, generation) == Applied.APPLIED) result.pulled++
                continue
            }
            db.markSynced(record.session.id, record.updatedAt, generation)
            result.pushed++
        }
        return state
    }

    private fun put(record: SyncRecord, state: SyncState, names: Map<String, String>): PracticeLog {
        val key = account.practiceKey(state.keyVersion) ?: throw AccountKeys.Error(AccountKeys.Failure.MISSING_SECRET)
        val log = SessionSync.seal(record, names[record.session.practiceId], key, state.user, state.keyVersion)
        return account.api.putLog(log.id, PracticeLogInput(log.sealed, log.keyVersion, log.updatedAtText, if (log.deleted) true else null))
    }

    private enum class Applied { APPLIED, UNCHANGED, UNREADABLE, REFUSED }

    private suspend fun apply(log: PracticeLog, state: SyncState, generation: Int): Applied {
        val sealed = log.sealed ?: return Applied.UNCHANGED
        val outer = SyncTime.parse(log.updatedAt) ?: return Applied.REFUSED
        val db = account.database
        return when (val opened = SessionSync.open(log.id, sealed, log.keyVersion, outer, state.user, account::practiceKey)) {
            is SessionSync.Opened.Record ->
                if (db.applyRemote(opened.record, opened.practiceName, generation)) Applied.APPLIED else Applied.UNCHANGED
            is SessionSync.Opened.Deletion ->
                if (db.applyRemoteDeletion(opened.id, opened.updatedAt, opened.deletedAt, generation)) Applied.APPLIED else Applied.UNCHANGED
            SessionSync.Opened.Unreadable -> Applied.UNREADABLE
            SessionSync.Opened.Refused -> Applied.REFUSED
        }
    }

    companion object {
        /** A refusal of one record (it stays dirty), as opposed to one that stops the sync. */
        fun isPerRecord(e: ApiError): Boolean = when (e) {
            is ApiError.Conflict, is ApiError.NotFound, is ApiError.Forbidden -> true
            is ApiError.Status -> e.code in 400..499 && e.code != 429
            is ApiError.Unauthorized, is ApiError.OldKey, is ApiError.TooManyRequests -> false
        }
    }
}
