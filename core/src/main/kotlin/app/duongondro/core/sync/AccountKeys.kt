package app.duongondro.core.sync

import app.duongondro.core.crypto.DeviceKeyAgreement
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.StatementTypes
import java.util.UUID

/**
 * The pure steps of phase 3's account keys (design: Keys), as iOS's Account
 * does them, without the network or the Keystore: the app fetches, calls these,
 * and sends what they return. The server holds sealed blobs, wraps and signed
 * statements; every secret stays on the phone.
 *
 * First device: [firstDeviceSecrets], register the device key, set the identity
 * public key if the account has none, publish [deviceListAdding] unless
 * [listsDevice], put [wrapSecrets], then [recoveryBoxes] under a new
 * [newRecoverySecret] shown once as a [RecoveryCode]. Every step can be
 * repeated, so an interrupted set-up resumes.
 *
 * New phone without the old one: [restore] opens the recovery boxes with the
 * code, then the phone registers itself and publishes and wraps as above, with
 * the account's published identity key as `verifyWith`.
 */
object AccountKeys {
    enum class Failure {
        /** The account already has keys this phone does not hold: restore instead. */
        ACCOUNT_HAS_KEYS,
        /** The account has no keys yet: set it up first. */
        ACCOUNT_HAS_NO_KEYS,
        BAD_RECOVERY_CODE,
        /** The recovery boxes hold an older practice key than the account uses (not resealed after a rotation). */
        RECOVERY_OUTDATED,
        /** A box, wrap or list did not verify against the account's identity key. */
        NOT_AUTHENTIC,
        MISSING_SECRET,
    }

    class Error(val failure: Failure) : Exception(failure.name.lowercase())

    /** How a wrap is authenticated (docs/crypto.md, Wraps), as the API names it. */
    enum class AuthType(val raw: String) {
        SIGNATURE("signature"), ENROL("enrol"), SELF("self");

        companion object {
            fun of(raw: String): AuthType? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** A wrap as sent to or received from the server for one device. */
    class Wrap(
        val kind: E2EE.WrapKind,
        val keyVersion: Long,
        val ephemeralKey: ByteArray,
        val box: ByteArray,
        val authType: AuthType,
        val authenticator: ByteArray,
    ) {
        val wrapped: E2EE.Wrapped get() = E2EE.Wrapped(ephemeralKey, box)
    }

    /** A recovery box as the server stores it, one per kind. */
    class RecoveryBox(val kind: E2EE.WrapKind, val box: ByteArray, val signature: ByteArray)

    fun newIdentity(): Identity = Identity.generate()

    fun newPracticeKey(): ByteArray = E2EE.randomBytes(E2EE.KEY_SIZE)

    fun newRecoverySecret(): ByteArray = E2EE.randomBytes(E2EE.RECOVERY_SECRET_SIZE)

    // First device

    /** What this phone holds of a set-up, read from its secret store. */
    class StoredSetUp(val setUpUser: String?, val identitySeed: ByteArray?, val practiceKey: ByteArray?)

    /**
     * The secrets a first-device set-up uses, and what to store before going on.
     *
     * @property discardStored the stored seed, practice key and pending recovery
     *   secret belong to another account's unfinished set-up: delete them, and
     *   write [setUpUser] before the new secrets.
     * @property publishIdentity the account has no identity key yet: set it.
     */
    class FirstDeviceSecrets(
        val identity: Identity,
        val practiceKey: ByteArray,
        val setUpUser: String,
        val discardStored: Boolean,
        val publishIdentity: Boolean,
    )

    /**
     * Resuming is allowed only with the very identity the account published;
     * an account with keys this phone lacks is [Failure.ACCOUNT_HAS_KEYS]. Secrets
     * left by another account's unfinished set-up are never reused (the store
     * outlives the app).
     */
    fun firstDeviceSecrets(user: UUID, publishedIdentityPk: ByteArray?, stored: StoredSetUp): FirstDeviceSecrets {
        val me = user.toString().lowercase()
        if (publishedIdentityPk != null) {
            val seed = stored.identitySeed ?: throw Error(Failure.ACCOUNT_HAS_KEYS)
            val local = runCatching { Identity(seed) }.getOrNull() ?: throw Error(Failure.ACCOUNT_HAS_KEYS)
            val key = stored.practiceKey ?: throw Error(Failure.ACCOUNT_HAS_KEYS)
            if (!local.publicKey.contentEquals(publishedIdentityPk)) throw Error(Failure.ACCOUNT_HAS_KEYS)
            return FirstDeviceSecrets(local, key, me, discardStored = false, publishIdentity = false)
        }
        val discard = stored.setUpUser != me
        val identity = stored.identitySeed?.takeUnless { discard }?.let { Identity(it) } ?: newIdentity()
        val practiceKey = stored.practiceKey?.takeUnless { discard } ?: newPracticeKey()
        return FirstDeviceSecrets(identity, practiceKey, me, discardStored = discard, publishIdentity = true)
    }

    /**
     * True when the published list already names this device. The list is
     * verified first, against this phone's identity or, on restore, the
     * account's published key (as [deviceListAdding] does); one that does not
     * verify or parse is [Failure.NOT_AUTHENTIC].
     */
    fun listsDevice(current: SignedStatement?, device: UUID, verifyWith: ByteArray): Boolean {
        if (current == null) return false
        if (!current.verify(StatementTypes.DEVICE_LIST, verifyWith)) throw Error(Failure.NOT_AUTHENTIC)
        val parsed = Statements.parseDeviceList(current.payload) ?: throw Error(Failure.NOT_AUTHENTIC)
        return parsed.devices.any { it.id == device }
    }

    /**
     * A signed list with this device added. The list it extends must be one this
     * identity signed (or `verifyWith`, the account's published key, on restore),
     * and keeps only devices the server still has registered with the same key
     * and tier, so a removed device is not listed again (and the server would
     * refuse a list naming it). Version 1 when there is no list yet.
     */
    fun deviceListAdding(
        device: Statements.ListedDevice,
        user: UUID,
        identity: Identity,
        previous: SignedStatement?,
        registered: List<Statements.ListedDevice>,
        issuedAt: java.time.Instant,
        verifyWith: ByteArray = identity.publicKey,
    ): SignedStatement {
        var devices = emptyList<Statements.ListedDevice>()
        var version = 1L
        if (previous != null) {
            if (!previous.verify(StatementTypes.DEVICE_LIST, verifyWith)) throw Error(Failure.NOT_AUTHENTIC)
            val parsed = Statements.parseDeviceList(previous.payload) ?: throw Error(Failure.NOT_AUTHENTIC)
            devices = parsed.devices.filter { listed -> listed.id != device.id && registered.any { it == listed } }
            version = parsed.version + 1
        }
        val payload = Statements.deviceList(devices + device, issuedAt, user, version)
        return SignedStatement.sign(StatementTypes.DEVICE_LIST, payload, identity)
    }

    /** The practice key and identity seed, wrapped to one device and signed by the identity. */
    fun wrapSecrets(device: UUID, devicePk: ByteArray, user: UUID, keyVersion: Long, identity: Identity, practiceKey: ByteArray): List<Wrap> =
        listOf(E2EE.WrapKind.PRACTICE_KEY to practiceKey, E2EE.WrapKind.IDENTITY_SEED to identity.seed).map { (kind, secret) ->
            wrapSigned(secret, kind, device, devicePk, user, keyVersion, identity)
        }

    /** One secret wrapped to one device, authenticated by the identity's signature. */
    fun wrapSigned(secret: ByteArray, kind: E2EE.WrapKind, device: UUID, devicePk: ByteArray, user: UUID, keyVersion: Long, identity: Identity): Wrap {
        val aad = E2EE.wrapAAD(user, keyVersion, device, kind)
        val wrapped = E2EE.wrap(secret, devicePk, aad)
        val signature = E2EE.signWrap(wrapped, aad, devicePk, identity)
        return Wrap(kind, keyVersion, wrapped.epk, wrapped.box, AuthType.SIGNATURE, signature)
    }

    /**
     * Opens a wrap addressed to this device after checking its signature against
     * this phone's own identity key; null when it is not signed or does not
     * verify (skipped, as iOS's receiveNewerKeys does). A verified wrap that will
     * not open throws.
     *
     * The identity is the local one, never a key the server published: a server
     * that could choose the verifying key could plant a practice key of its own.
     * Before storing the result, the caller checks that the database generation
     * has not changed since it fetched the wraps (as iOS does), so a sync running
     * across "Delete everything" never writes the purged keys back.
     */
    fun receiveWrap(wrap: Wrap, user: UUID, device: UUID, deviceKey: DeviceKeyAgreement, identity: Identity): ByteArray? {
        val aad = E2EE.wrapAAD(user, wrap.keyVersion, device, wrap.kind)
        if (wrap.authType != AuthType.SIGNATURE) return null
        if (!E2EE.verifyWrap(wrap.wrapped, aad, deviceKey.publicKey, wrap.authenticator, identity.publicKey)) return null
        return E2EE.unwrap(wrap.wrapped, deviceKey, aad)
    }

    // Recovery

    /**
     * Both secrets sealed under the recovery secret and signed by the identity.
     * Keep the recovery secret until both boxes are stored, so a failure halfway
     * is retried with the same code rather than leaving one box under each.
     */
    fun recoveryBoxes(recoverySecret: ByteArray, user: UUID, identity: Identity, practiceKey: ByteArray): List<RecoveryBox> {
        val key = E2EE.recoveryKey(recoverySecret, user)
        return listOf(E2EE.WrapKind.PRACTICE_KEY to practiceKey, E2EE.WrapKind.IDENTITY_SEED to identity.seed).map { (kind, secret) ->
            val box = E2EE.sealRecovery(secret, key, user, kind)
            RecoveryBox(kind, box, E2EE.signRecoveryBox(box, user, kind, identity))
        }
    }

    class Restored(val identity: Identity, val practiceKey: ByteArray, val keyVersion: Long)

    /** A session sealed under the account's current key version, to check the recovered practice key against. */
    class SealedSample(val session: UUID, val sealed: ByteArray)

    /**
     * Opens the recovery boxes with a typed code. The AEAD under the code
     * authenticates them; the seed must also be the account's own (its public
     * half is published), and each box's signature must verify under it. The
     * boxes do not say which key version they hold, so a [sample] sealed under
     * the current version, when there is one, must open with the recovered key:
     * a box left from before a rotation would otherwise be filed as the new key.
     */
    fun restore(
        code: String,
        user: UUID,
        publishedIdentityPk: ByteArray?,
        keyVersion: Long,
        boxes: List<RecoveryBox>,
        sample: SealedSample?,
    ): Restored {
        val recovery = RecoveryCode.decode(code) ?: throw Error(Failure.BAD_RECOVERY_CODE)
        if (publishedIdentityPk == null || publishedIdentityPk.size != 32) throw Error(Failure.ACCOUNT_HAS_NO_KEYS)
        val key = E2EE.recoveryKey(recovery, user)
        fun open(kind: E2EE.WrapKind): ByteArray {
            val box = boxes.firstOrNull { it.kind == kind } ?: throw Error(Failure.NOT_AUTHENTIC)
            val secret = try { E2EE.openRecovery(box.box, key, user, kind) } catch (_: E2EE.Error) { throw Error(Failure.BAD_RECOVERY_CODE) }
            if (!E2EE.verifyRecoveryBox(box.box, user, kind, box.signature, publishedIdentityPk)) throw Error(Failure.NOT_AUTHENTIC)
            return secret
        }
        val seed = open(E2EE.WrapKind.IDENTITY_SEED)
        val identity = runCatching { Identity(seed) }.getOrNull() ?: throw Error(Failure.NOT_AUTHENTIC)
        if (!identity.publicKey.contentEquals(publishedIdentityPk)) throw Error(Failure.NOT_AUTHENTIC)
        val practiceKey = open(E2EE.WrapKind.PRACTICE_KEY)
        if (practiceKey.size != E2EE.KEY_SIZE) throw Error(Failure.NOT_AUTHENTIC)
        if (sample != null) {
            val opens = runCatching {
                E2EE.openSession(E2EE.sealKey(practiceKey, user), sample.session, user, keyVersion, sample.sealed)
            }.isSuccess
            if (!opens) throw Error(Failure.RECOVERY_OUTDATED)
        }
        return Restored(identity, practiceKey, keyVersion)
    }
}
