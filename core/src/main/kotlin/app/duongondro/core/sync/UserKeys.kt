package app.duongondro.core.sync

import app.duongondro.core.crypto.DeviceKeyAgreement
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.PasskeyBox
import app.duongondro.core.crypto.SoftwareDeviceKey
import app.duongondro.core.crypto.StatementTypes
import app.duongondro.core.uuidV7
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID

/**
 * The pure steps of passkey unlock (docs/crypto.md › Passkey unlock; design:
 * Keys › Passkeys that unlock the counts), as [AccountKeys] does for devices.
 *
 * A user key U, a software key on the glowie curve (it must travel, so never in
 * hardware), sits between passkeys and keys: the practice key and identity seed
 * are wrapped to U like to a device, and U's scalar is sealed once per passkey
 * under that passkey's PRF output. A phone holding the keys publishes U only
 * when the account has none; another phone gets it as a signed kind 4 wrap.
 * Nothing here ever replaces a published U.
 */
object UserKeys {
    /** U as this phone holds it. */
    class Local(val id: UUID, val version: Long, val key: SoftwareDeviceKey) {
        val publicKey: ByteArray get() = key.publicKey

        /** id(16) ‖ u64be(version) ‖ scalar(32), for the secret store. */
        fun serialised(): ByteArray = id.bytes() + ByteBuffer.allocate(8).putLong(version).array() + key.rawPrivate

        companion object {
            fun deserialised(data: ByteArray): Local? = runCatching {
                require(data.size == 56)
                val b = ByteBuffer.wrap(data)
                val id = UUID(b.long, b.long)
                Local(id, b.long, SoftwareDeviceKey.fromRaw(data.copyOfRange(24, 56)))
            }.getOrNull()
        }
    }

    /** A new U at [version] (1 for an account's first); its id is a fresh UUIDv7. */
    fun create(version: Long = 1, now: Instant = Instant.now()): Local = Local(uuidV7(now), version, SoftwareDeviceKey.generate())

    /** U's signed `user-key` statement. */
    fun statement(u: Local, user: UUID, identity: Identity): SignedStatement =
        SignedStatement.sign(StatementTypes.USER_KEY, Statements.userKey(Statements.UserKey(u.id, u.publicKey, user, u.version)), identity)

    /**
     * Checks a published statement against the identity key, then reads it: it
     * must name [user] and carry a point on the curve. Anything else is
     * [AccountKeys.Failure.NOT_AUTHENTIC].
     */
    fun verify(statement: SignedStatement, user: UUID, identityPk: ByteArray): Statements.UserKey {
        if (!statement.verify(StatementTypes.USER_KEY, identityPk)) throw notAuthentic()
        val parsed = Statements.parseUserKey(statement.payload) ?: throw notAuthentic()
        if (parsed.user != user || !app.duongondro.core.crypto.isValidDevicePublicKey(parsed.publicKey)) throw notAuthentic()
        return parsed
    }

    /** A secret wrapped to U as the API takes it: always signed, U's id in the recipient's place. */
    class Wrap(val kind: E2EE.WrapKind, val keyVersion: Long, val ephemeralKey: ByteArray, val box: ByteArray, val signature: ByteArray)

    fun wrapToUserKey(secret: ByteArray, kind: E2EE.WrapKind, u: Statements.UserKey, user: UUID, keyVersion: Long, identity: Identity): Wrap {
        require(kind == E2EE.WrapKind.PRACTICE_KEY || kind == E2EE.WrapKind.IDENTITY_SEED) { "only the practice key and identity seed are wrapped to U" }
        val aad = E2EE.wrapAAD(user, keyVersion, u.id, kind)
        val wrapped = E2EE.wrap(secret, u.publicKey, aad)
        return Wrap(kind, keyVersion, wrapped.epk, wrapped.box, E2EE.signWrap(wrapped, aad, u.publicKey, identity))
    }

    /**
     * The wraps U lacks for this phone's keys: the practice key at [keyVersion]
     * (after a rotation too) and the identity seed at any version. [have] lists
     * the (kind, keyVersion) pairs the server holds for the current U.
     */
    fun missing(have: List<Pair<Int, Long>>, keyVersion: Long): List<E2EE.WrapKind> = buildList {
        if (have.none { it.first == E2EE.WrapKind.PRACTICE_KEY.raw && it.second == keyVersion }) add(E2EE.WrapKind.PRACTICE_KEY)
        if (have.none { it.first == E2EE.WrapKind.IDENTITY_SEED.raw }) add(E2EE.WrapKind.IDENTITY_SEED)
    }

    /** A passkey's box: U's scalar sealed under its PRF output, and the identity's signature over it. */
    class Box(val box: ByteArray, val signature: ByteArray)

    fun sealForPasskey(u: Local, prf: ByteArray, credentialId: ByteArray, user: UUID, identity: Identity): Box {
        val aad = PasskeyBox.aad(user, identity.publicKey, u.id, credentialId)
        val box = PasskeyBox.seal(u.key.rawPrivate, PasskeyBox.key(prf, user), aad)
        return Box(box, PasskeyBox.sign(aad, box, identity))
    }

    /** U to another of the account's phones: a kind 4 wrap at U's version, signed. */
    fun wrapToDevice(u: Local, device: UUID, devicePk: ByteArray, user: UUID, identity: Identity): AccountKeys.Wrap =
        AccountKeys.wrapSigned(u.key.rawPrivate, E2EE.WrapKind.USER_KEY, device, devicePk, user, u.version, identity)

    /**
     * Opens a kind 4 wrap for the published U, checked against this phone's own
     * identity, and checks the scalar against U's public key; null when it is
     * not for this U or does not verify.
     */
    fun receive(wrap: AccountKeys.Wrap, published: Statements.UserKey, user: UUID, device: UUID, deviceKey: DeviceKeyAgreement, identity: Identity): Local? {
        if (wrap.kind != E2EE.WrapKind.USER_KEY || wrap.keyVersion != published.version) return null
        val scalar = AccountKeys.receiveWrap(wrap, user, device, deviceKey, identity) ?: return null
        val key = runCatching { SoftwareDeviceKey.fromRaw(scalar) }.getOrNull() ?: return null
        if (!key.publicKey.contentEquals(published.publicKey)) return null
        return Local(published.id, published.version, key)
    }

    // A new phone

    /** What `GET /api/me/credentials/{id}/unlock` returned. */
    class Unlock(val userKeyId: UUID, val statement: SignedStatement, val box: ByteArray, val boxSignature: ByteArray, val wraps: List<Wrap>)

    class Unlocked(val identity: Identity, val practiceKey: ByteArray, val keyVersion: Long, val userKey: Local)

    /**
     * Brings the keys to a new phone from a passkey's PRF output. The box is
     * opened first, with the identity key the server gave bound into its AAD:
     * only a box sealed by a phone that saw that very key opens under this
     * passkey's output, which the server cannot produce, so it authenticates
     * the identity key; until then nothing in the response is trusted. Then
     * the box's signature, U's statement (naming the same U) and each wrap
     * verify against that key, U's scalar matches its public key, the identity seed wrapped to U matches the published
     * identity key, and the practice key at [keyVersion] opens [sample] when
     * there is one, as [AccountKeys.restore] checks.
     *
     * [AccountKeys.Failure.PASSKEY_CANNOT_UNLOCK] when the box does not open
     * under this output; [AccountKeys.Failure.RECOVERY_OUTDATED] when U holds no
     * wrap of the current practice key (the recovery code is the way then).
     */
    fun unlock(
        prf: ByteArray,
        credentialId: ByteArray,
        user: UUID,
        publishedIdentityPk: ByteArray?,
        keyVersion: Long,
        unlock: Unlock,
        sample: AccountKeys.SealedSample?,
    ): Unlocked {
        if (publishedIdentityPk == null || publishedIdentityPk.size != 32) throw AccountKeys.Error(AccountKeys.Failure.ACCOUNT_HAS_NO_KEYS)
        // First the box: it authenticates the identity key (and U's id); nothing before it is trusted.
        val aad = PasskeyBox.aad(user, publishedIdentityPk, unlock.userKeyId, credentialId)
        val scalar = try {
            PasskeyBox.open(unlock.box, PasskeyBox.key(prf, user), aad)
        } catch (_: E2EE.Error) {
            throw AccountKeys.Error(AccountKeys.Failure.PASSKEY_CANNOT_UNLOCK)
        }
        if (!PasskeyBox.verify(aad, unlock.box, unlock.boxSignature, publishedIdentityPk)) throw notAuthentic()
        val published = verify(unlock.statement, user, publishedIdentityPk)
        if (published.id != unlock.userKeyId) throw notAuthentic()
        val key = runCatching { SoftwareDeviceKey.fromRaw(scalar) }.getOrNull() ?: throw notAuthentic()
        if (!key.publicKey.contentEquals(published.publicKey)) throw notAuthentic()

        fun open(w: Wrap): ByteArray {
            val wrapAad = E2EE.wrapAAD(user, w.keyVersion, published.id, w.kind)
            val wrapped = E2EE.Wrapped(w.ephemeralKey, w.box)
            if (!E2EE.verifyWrap(wrapped, wrapAad, published.publicKey, w.signature, publishedIdentityPk)) throw notAuthentic()
            return E2EE.unwrap(wrapped, key, wrapAad)
        }

        val seedWrap = unlock.wraps.filter { it.kind == E2EE.WrapKind.IDENTITY_SEED }.maxByOrNull { it.keyVersion } ?: throw notAuthentic()
        val identity = runCatching { Identity(open(seedWrap)) }.getOrNull() ?: throw notAuthentic()
        if (!identity.publicKey.contentEquals(publishedIdentityPk)) throw notAuthentic()
        val keyWrap = unlock.wraps.firstOrNull { it.kind == E2EE.WrapKind.PRACTICE_KEY && it.keyVersion == keyVersion }
            ?: throw AccountKeys.Error(AccountKeys.Failure.RECOVERY_OUTDATED)
        val practiceKey = open(keyWrap)
        if (sample != null) {
            val opens = runCatching {
                E2EE.openSession(E2EE.sealKey(practiceKey, user), sample.session, user, keyVersion, sample.sealed)
            }.isSuccess
            if (!opens) throw AccountKeys.Error(AccountKeys.Failure.RECOVERY_OUTDATED)
        }
        return Unlocked(identity, practiceKey, keyVersion, Local(published.id, published.version, key))
    }

    private fun notAuthentic() = AccountKeys.Error(AccountKeys.Failure.NOT_AUTHENTIC)

    private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()
}
