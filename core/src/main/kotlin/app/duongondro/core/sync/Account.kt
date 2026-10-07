package app.duongondro.core.sync

import app.duongondro.core.api.Api
import app.duongondro.core.api.RecoveryBoxInput
import app.duongondro.core.api.SignedStatementDto
import app.duongondro.core.api.WrapInput
import app.duongondro.core.crypto.DeviceKey
import app.duongondro.core.crypto.DeviceKeyStore
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.Tier
import java.time.Instant
import java.util.UUID

/**
 * Where secrets live: files sealed by a Keystore key in the app, memory in
 * tests. A read that fails for any reason but "not found" throws: an unreadable
 * store is not "no key", and a new key must never be made over an old one.
 */
interface SecretStore {
    fun read(name: String): ByteArray?
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
}

class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, ByteArray>()
    @Synchronized override fun read(name: String) = values[name]?.copyOf()
    @Synchronized override fun write(name: String, data: ByteArray) { values[name] = data.copyOf() }
    @Synchronized override fun delete(name: String) { values.remove(name) }
}

/** The names secrets are stored under, as on iOS. */
object SecretName {
    const val PENDING_RECOVERY = "recovery-pending"
    const val DEVICE_ID = "device-id"
    const val IDENTITY_SEED = "identity-seed"
    /** The account a set-up in progress belongs to, so another account's leftovers are never reused. */
    const val SET_UP_USER = "set-up-user"
    fun practiceKey(version: Long) = "practice-key-$version"
}

/** The account a phone syncs with: the user, the practice-key version it holds, and the cursor of its last read (`<generation>:<xid8>`). */
data class SyncState(val user: UUID, val keyVersion: Long, val cursor: String? = null)

/**
 * What sync needs of the local database. Every write that names a generation
 * runs only if no erase ("Delete everything", sign-out) happened since that
 * generation was read, and throws [Erased] otherwise, so a sync running across
 * an erase never writes the old account back.
 */
interface SyncDatabase {
    val generation: Int
    suspend fun syncState(): SyncState?
    suspend fun saveSyncState(state: SyncState, generation: Int? = null)
    /** Sessions changed here since the server last acknowledged them, deletions included. */
    suspend fun dirtySessions(): List<SyncRecord>
    /** Marks a session synced, unless it changed again after `updatedAt` was read. */
    suspend fun markSynced(id: UUID, updatedAt: Instant, generation: Int)
    /** Everything is pushed again: after set-up, a restore, or a server that lost history. */
    suspend fun markAllDirty()
    /** Sessions with ids from before sync (not UUIDv7, which the server requires) get v7 ids. */
    suspend fun rekeyLegacySessionIds()
    /** The newer write wins; a practice this phone does not track yet is added. Returns whether anything changed. */
    suspend fun applyRemote(record: SyncRecord, practiceName: String?, generation: Int): Boolean
    suspend fun applyRemoteDeletion(id: UUID, updatedAt: Instant, deletedAt: Instant, generation: Int): Boolean
    /** Custom practices' names, sealed with their sessions so another phone can show them. */
    suspend fun customNames(): Map<String, String>
}

/** A write named a generation that an erase has since ended. */
class Erased : Exception("erased")

/**
 * Phase 3's keys and account on the phone (design: Keys), as iOS's Account:
 * the network and the secret store around [AccountKeys]' pure steps.
 *
 * - First device: make the identity key and the practice key, register this
 *   device, publish the signed device list, wrap both secrets to this device,
 *   and seal both into recovery boxes under a new recovery code, shown once.
 *   Every step can be repeated, so an interrupted set-up resumes.
 * - New phone without the old one: the recovery code opens the recovery boxes,
 *   and the phone enrols itself.
 *
 * Signing in alone never gives keys: a session proves an inbox, not the keys.
 */
class Account(
    val api: Api,
    private val secrets: SecretStore,
    private val deviceKeys: DeviceKeyStore,
    val database: SyncDatabase,
    private val now: () -> Instant = Instant::now,
) {
    /** What refused when this phone's device key fell back to a weaker tier, for the client-error report; null when none did. */
    var deviceKeyFallback: String? = null
        private set

    fun identity(): Identity = Identity(secrets.read(SecretName.IDENTITY_SEED) ?: throw AccountKeys.Error(AccountKeys.Failure.MISSING_SECRET))

    fun practiceKey(version: Long): ByteArray? = secrets.read(SecretName.practiceKey(version))

    /** Keys and a sync state: this phone can sync. */
    suspend fun hasKeys(): Boolean = secrets.read(SecretName.IDENTITY_SEED) != null && database.syncState() != null

    /** Where a signed-in account stands on this phone. */
    enum class Standing {
        /** Keys here and a sync state: nothing to do. */
        READY,
        /** No keys published yet, or an interrupted set-up of this phone's: set up (resumes with the same code). */
        SET_UP,
        /** The account has keys this phone does not hold: restore with the recovery code. */
        RESTORE,
    }

    suspend fun standing(): Standing {
        val me = api.me()
        if (database.syncState()?.user == me.id && secrets.read(SecretName.IDENTITY_SEED) != null) return Standing.READY
        val published = me.identityPublicKey ?: return Standing.SET_UP
        val seed = secrets.read(SecretName.IDENTITY_SEED)
        val ours = seed != null && secrets.read(SecretName.SET_UP_USER)?.decodeToString() == me.id.toString().lowercase() &&
            runCatching { Identity(seed).publicKey.contentEquals(published) }.getOrDefault(false)
        return if (ours) Standing.SET_UP else Standing.RESTORE
    }

    // First device

    /** Sets up a new account's keys on this phone, or finishes an interrupted set-up; returns the recovery code to show once. */
    suspend fun setUpFirstDevice(): String {
        // A finished set-up is not repeated: it would replace the recovery code.
        if (database.syncState() != null) throw AccountKeys.Error(AccountKeys.Failure.ACCOUNT_HAS_KEYS)
        val me = api.me()
        val stored = AccountKeys.StoredSetUp(
            secrets.read(SecretName.SET_UP_USER)?.decodeToString(),
            secrets.read(SecretName.IDENTITY_SEED),
            secrets.read(SecretName.practiceKey(me.keyVersion)),
        )
        val s = AccountKeys.firstDeviceSecrets(me.id, me.identityPublicKey, stored)
        if (s.discardStored) {
            listOf(SecretName.IDENTITY_SEED, SecretName.practiceKey(me.keyVersion), SecretName.PENDING_RECOVERY).forEach(secrets::delete)
            secrets.write(SecretName.SET_UP_USER, s.setUpUser.toByteArray())
        }
        if (stored.identitySeed == null || s.discardStored) secrets.write(SecretName.IDENTITY_SEED, s.identity.seed)
        if (stored.practiceKey == null || s.discardStored) secrets.write(SecretName.practiceKey(me.keyVersion), s.practiceKey)

        val (deviceId, key) = registerThisDevice()
        if (s.publishIdentity) api.setIdentity(s.identity.publicKey)
        val current = api.deviceList()?.toStatement()
        if (!AccountKeys.listsDevice(current, deviceId, s.identity.publicKey)) {
            publishDeviceList(deviceId, key, me.id, s.identity, current, s.identity.publicKey)
        }
        wrapSecrets(deviceId, key, me.id, me.keyVersion, s.identity, s.practiceKey)
        val code = putRecoveryBoxes(me.id, s.identity, s.practiceKey)
        database.saveSyncState(SyncState(me.id, me.keyVersion))
        // Everything logged before the account existed goes up on the first sync.
        database.markAllDirty()
        return code
    }

    // New phone, from the recovery code

    suspend fun restore(recoveryCode: String) {
        val me = api.me()
        val boxes = api.recoveryBoxes().mapNotNull { b ->
            E2EE.WrapKind.of(b.kind)?.let { AccountKeys.RecoveryBox(it, b.box, signature = null) }
        }
        val version = me.keyVersion
        val sample = api.sync(null).logs.firstOrNull { it.keyVersion == version && it.sealed != null }
            ?.let { AccountKeys.SealedSample(it.id, it.sealed!!) }
        val restored = AccountKeys.restore(recoveryCode, me.id, me.identityPublicKey, version, boxes, sample)
        secrets.write(SecretName.SET_UP_USER, me.id.toString().lowercase().toByteArray())
        secrets.write(SecretName.IDENTITY_SEED, restored.identity.seed)
        secrets.write(SecretName.practiceKey(version), restored.practiceKey)

        val (deviceId, key) = registerThisDevice()
        val previous = api.deviceList()?.toStatement()
        val published = me.identityPublicKey!!
        if (!AccountKeys.listsDevice(previous, deviceId, published)) {
            publishDeviceList(deviceId, key, me.id, restored.identity, previous, published)
        }
        wrapSecrets(deviceId, key, me.id, version, restored.identity, restored.practiceKey)
        database.saveSyncState(SyncState(me.id, version))
        database.markAllDirty()
    }

    /** A new recovery code, replacing the old one (the old code stops working). A code left pending by a failed attempt is finished instead. */
    suspend fun newRecoveryCode(): String {
        val state = database.syncState() ?: throw AccountKeys.Error(AccountKeys.Failure.ACCOUNT_HAS_NO_KEYS)
        val key = practiceKey(state.keyVersion) ?: throw AccountKeys.Error(AccountKeys.Failure.MISSING_SECRET)
        return putRecoveryBoxes(state.user, identity(), key)
    }

    // Steps

    private fun registerThisDevice(): Pair<UUID, DeviceKey> {
        val created = deviceKeys.currentOrCreate()
        if (created.fellBack) deviceKeyFallback = created.fallbackReason ?: "fell back"
        val device = api.registerDevice(created.key.publicKey, created.key.tier.raw)
        secrets.write(SecretName.DEVICE_ID, device.id.toString().toByteArray())
        return device.id to created.key
    }

    private fun publishDeviceList(device: UUID, key: DeviceKey, user: UUID, identity: Identity, previous: SignedStatement?, verifyWith: ByteArray) {
        val registered = if (previous == null) emptyList() else api.devices().mapNotNull { d ->
            Tier.of(d.tier)?.let { Statements.ListedDevice(d.id, d.publicKey, it) }
        }
        val statement = AccountKeys.deviceListAdding(
            Statements.ListedDevice(device, key.publicKey, key.tier), user, identity, previous, registered, now(), verifyWith,
        )
        api.putDeviceList(SignedStatementDto(statement.payload, statement.signature))
    }

    private fun wrapSecrets(device: UUID, key: DeviceKey, user: UUID, keyVersion: Long, identity: Identity, practiceKey: ByteArray) {
        AccountKeys.wrapSecrets(device, key.publicKey, user, keyVersion, identity, practiceKey).forEach { w ->
            api.putWrap(device, WrapInput(w.kind.raw, w.keyVersion, w.ephemeralKey, w.box, w.authType.raw, w.authenticator))
        }
    }

    /**
     * Seals both secrets under a recovery code and returns it. The code is kept
     * in the secret store until both boxes are stored, so a failure halfway is
     * retried with the same code rather than leaving one box under each.
     */
    private fun putRecoveryBoxes(user: UUID, identity: Identity, practiceKey: ByteArray): String {
        val recovery = secrets.read(SecretName.PENDING_RECOVERY)
            ?: AccountKeys.newRecoverySecret().also { secrets.write(SecretName.PENDING_RECOVERY, it) }
        AccountKeys.recoveryBoxes(recovery, user, identity, practiceKey).forEach { b ->
            api.putRecoveryBox(b.kind.raw, RecoveryBoxInput(b.box, checkNotNull(b.signature)))
        }
        secrets.delete(SecretName.PENDING_RECOVERY)
        return RecoveryCode.encode(recovery)
    }

    // Key versions

    /**
     * Fetches this device's wraps and keeps any practice-key version it does not
     * hold yet, after checking the wrap's signature against this phone's own
     * identity. Returns the newest version this device now holds.
     */
    suspend fun receiveNewerKeys(user: UUID): Long {
        val deviceId = secrets.read(SecretName.DEVICE_ID)?.decodeToString()?.let(UUID::fromString)
            ?: throw AccountKeys.Error(AccountKeys.Failure.MISSING_SECRET)
        val key = deviceKeys.current() ?: throw AccountKeys.Error(AccountKeys.Failure.MISSING_SECRET)
        val identity = identity()
        val generation = database.generation
        var newest = database.syncState()?.keyVersion ?: 1
        for (w in api.wraps(deviceId)) {
            if (w.kind != E2EE.WrapKind.PRACTICE_KEY.raw) continue
            if (practiceKey(w.keyVersion) != null) {
                newest = maxOf(newest, w.keyVersion)
                continue
            }
            val auth = AccountKeys.AuthType.of(w.authType) ?: continue
            val wrap = AccountKeys.Wrap(E2EE.WrapKind.PRACTICE_KEY, w.keyVersion, w.ephemeralKey, w.box, auth, w.authenticator)
            val secret = AccountKeys.receiveWrap(wrap, user, deviceId, key.agreement, identity) ?: continue
            // Not after "Delete everything": the purge removed the keys for good.
            if (database.generation != generation) throw Erased()
            secrets.write(SecretName.practiceKey(w.keyVersion), secret)
            newest = maxOf(newest, w.keyVersion)
        }
        return newest
    }
}

private fun SignedStatementDto.toStatement() = SignedStatement(payload, signature)
