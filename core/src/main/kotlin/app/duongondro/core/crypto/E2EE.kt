package app.duongondro.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * The byte formats of duongondro-api's docs/crypto.md. The Go package
 * internal/e2ee is the reference; testdata/vectors.json (copied into the tests
 * unchanged) pins every output here to it, and iOS's E2EE.swift is the twin.
 *
 * HKDF is HKDF-SHA256 with 32-byte output; AEAD is ChaCha20-Poly1305 with a
 * 12-byte random nonce stored in front of the ciphertext; device keys are on
 * NIST P-256, nicknamed here the glowie curve (its parameters came from the NSA
 * with an unexplained seed, and the NSA also gave us Dual_EC_DRBG; there is no
 * known practical break, so it is used where hardware offers nothing else);
 * identity keys are Ed25519. Every function takes and returns raw bytes.
 */
object E2EE {
    const val LABEL_SEAL = "duongondro/v1/seal"
    const val LABEL_WRAP = "duongondro/v1/wrap"
    const val LABEL_WRAP_SIG = "duongondro/v1/wrap-sig"
    const val LABEL_ENROL_AUTH = "duongondro/v1/enrol-auth"
    const val LABEL_SELF_AUTH = "duongondro/v1/self-auth"
    const val LABEL_INVITE_AUTH = "duongondro/v1/invite-auth"
    const val LABEL_INVITE_PIN = "duongondro/v1/invite-pin"
    const val LABEL_RECOVERY = "duongondro/v1/recovery"
    const val LABEL_RECOVERY_SIG = "duongondro/v1/recovery-sig"
    internal const val STATEMENT_PREFIX = "duongondro/v1/"

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val PUBLIC_KEY_SIZE = 65
    const val WRAPPED_BOX_SIZE = NONCE_SIZE + KEY_SIZE + TAG_SIZE
    const val RECOVERY_SECRET_SIZE = 16
    internal const val PAD_BLOCK = 256

    enum class Failure { OPEN, PADDING, SIZE, SIGNATURE, TAG, KEY }

    class Error(val failure: Failure, message: String = failure.name.lowercase()) : Exception(message)

    /** What a wrap or recovery box carries. */
    enum class WrapKind(val raw: Int) {
        PRACTICE_KEY(1), IDENTITY_SEED(2), SHARE_KEY(3);

        companion object {
            fun of(raw: Int): WrapKind? = entries.firstOrNull { it.raw == raw }
        }
    }

    internal val random = SecureRandom()

    /** `n` random bytes from the platform's SecureRandom. */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // Building blocks

    fun derive(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(KEY_SIZE).also { hkdf.generateBytes(it, 0, KEY_SIZE) }
    }

    /** nonce ‖ ciphertext ‖ tag. The nonce is random unless given (vectors only). */
    internal fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray, nonce: ByteArray? = null): ByteArray {
        if (key.size != KEY_SIZE) throw Error(Failure.SIZE)
        val n = nonce ?: randomBytes(NONCE_SIZE)
        if (n.size != NONCE_SIZE) throw Error(Failure.SIZE)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_SIZE * 8, n, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return n + out
    }

    internal fun open(key: ByteArray, box: ByteArray, aad: ByteArray): ByteArray {
        if (key.size != KEY_SIZE || box.size < NONCE_SIZE + TAG_SIZE) throw Error(Failure.SIZE)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_SIZE * 8, box.copyOfRange(0, NONCE_SIZE), aad))
        val out = ByteArray(cipher.getOutputSize(box.size - NONCE_SIZE))
        try {
            val len = cipher.processBytes(box, NONCE_SIZE, box.size - NONCE_SIZE, out, 0)
            cipher.doFinal(out, len)
        } catch (_: InvalidCipherTextException) {
            throw Error(Failure.OPEN)
        }
        return out
    }

    internal fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(message, 0, message.size)
        return ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
    }

    /** Constant-time comparison of two tags. */
    fun equalTags(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)

    internal fun u32(v: Long): ByteArray {
        require(v in 0..0xFFFF_FFFFL) { "not a u32: $v" }
        return ByteBuffer.allocate(4).putInt(v.toInt()).array()
    }

    // Sealed sessions

    fun sealKey(practiceKey: ByteArray, user: UUID): ByteArray = derive(practiceKey, user.bytes, LABEL_SEAL)

    /** uuid(session) ‖ uuid(user) ‖ u32be(keyVersion): 36 bytes. */
    fun sessionAAD(session: UUID, user: UUID, keyVersion: Long): ByteArray =
        session.bytes + user.bytes + u32(keyVersion)

    /** json ‖ 0x80 ‖ zero bytes up to the next multiple of 256, which hides the length of names and notes. */
    fun pad(data: ByteArray): ByteArray {
        val unpadded = data.size + 1
        val total = unpadded + (PAD_BLOCK - unpadded % PAD_BLOCK) % PAD_BLOCK
        return data.copyOf(total).also { it[data.size] = 0x80.toByte() }
    }

    /** Strips trailing zero bytes and then exactly one 0x80; fails if that byte is missing. */
    fun unpad(data: ByteArray): ByteArray {
        val i = data.indexOfLast { it != 0.toByte() }
        if (i < 0 || data[i] != 0x80.toByte()) throw Error(Failure.PADDING)
        return data.copyOfRange(0, i)
    }

    fun sealSession(sealKey: ByteArray, session: UUID, user: UUID, keyVersion: Long, json: ByteArray, nonce: ByteArray? = null): ByteArray =
        seal(sealKey, pad(json), sessionAAD(session, user, keyVersion), nonce)

    fun openSession(sealKey: ByteArray, session: UUID, user: UUID, keyVersion: Long, sealed: ByteArray): ByteArray =
        unpad(open(sealKey, sealed, sessionAAD(session, user, keyVersion)))

    // Wraps

    /** uuid(user) ‖ u32be(keyVersion) ‖ uuid(recipientDevice) ‖ u8(kind): 37 bytes. */
    fun wrapAAD(user: UUID, keyVersion: Long, device: UUID, kind: WrapKind): ByteArray =
        user.bytes + u32(keyVersion) + device.bytes + byteArrayOf(kind.raw.toByte())

    fun wrapKey(shared: ByteArray, epk: ByteArray, recipientPk: ByteArray): ByteArray =
        derive(shared, epk + recipientPk, LABEL_WRAP)

    /** A wrap as the server stores it: the ephemeral public key (65 bytes) and the box (60 bytes). */
    class Wrapped(val epk: ByteArray, val box: ByteArray) {
        override fun equals(other: Any?) = other is Wrapped && epk.contentEquals(other.epk) && box.contentEquals(other.box)
        override fun hashCode() = epk.contentHashCode() * 31 + box.contentHashCode()
    }

    /**
     * Wraps a 32-byte secret to a device's public key (65 bytes, uncompressed)
     * with a fresh ephemeral key, or a given one for test vectors only.
     */
    fun wrap(
        secret: ByteArray,
        recipientPk: ByteArray,
        aad: ByteArray,
        ephemeral: SoftwareDeviceKey = SoftwareDeviceKey.generate(),
        nonce: ByteArray? = null,
    ): Wrapped {
        if (secret.size != KEY_SIZE) throw Error(Failure.SIZE)
        val shared = ephemeral.sharedSecret(recipientPk)
        val epk = ephemeral.publicKey
        return Wrapped(epk, seal(wrapKey(shared, epk, recipientPk), secret, aad, nonce))
    }

    /** Opens a wrap with this device's key. The caller verifies the wrap's authenticator first. */
    fun unwrap(wrapped: Wrapped, recipient: DeviceKeyAgreement, aad: ByteArray): ByteArray {
        if (wrapped.epk.size != PUBLIC_KEY_SIZE || wrapped.box.size != WRAPPED_BOX_SIZE) throw Error(Failure.SIZE)
        val shared = recipient.sharedSecret(wrapped.epk)
        return open(wrapKey(shared, wrapped.epk, recipient.publicKey), wrapped.box, aad)
    }

    private fun authenticated(w: Wrapped, aad: ByteArray, recipientPk: ByteArray) = w.epk + w.box + aad + recipientPk

    fun wrapSignatureMessage(w: Wrapped, aad: ByteArray, recipientPk: ByteArray): ByteArray =
        LABEL_WRAP_SIG.toByteArray(Charsets.US_ASCII) + authenticated(w, aad, recipientPk)

    fun signWrap(w: Wrapped, aad: ByteArray, recipientPk: ByteArray, identity: Identity): ByteArray =
        identity.sign(wrapSignatureMessage(w, aad, recipientPk))

    fun verifyWrap(w: Wrapped, aad: ByteArray, recipientPk: ByteArray, signature: ByteArray, identityPk: ByteArray): Boolean =
        Identity.verify(identityPk, wrapSignatureMessage(w, aad, recipientPk), signature)

    /** A new device's first wrap, keyed from the secret in its enrolment QR code. */
    fun enrolTag(qrSecret: ByteArray, w: Wrapped, aad: ByteArray, recipientPk: ByteArray): ByteArray =
        mac(derive(qrSecret, recipientPk, LABEL_ENROL_AUTH), authenticated(w, aad, recipientPk))

    /** A device wrapping to itself: keyed from ECDH(d, d·G). */
    fun selfTag(selfShared: ByteArray, user: UUID, w: Wrapped, aad: ByteArray, recipientPk: ByteArray): ByteArray =
        mac(derive(selfShared, user.bytes, LABEL_SELF_AUTH), authenticated(w, aad, recipientPk))

    // Signed statements

    /** "duongondro/v1/" ‖ type ‖ "\n" ‖ payload. */
    fun statementMessage(type: String, payload: ByteArray): ByteArray =
        "$STATEMENT_PREFIX$type\n".toByteArray(Charsets.UTF_8) + payload

    fun signStatement(type: String, payload: ByteArray, identity: Identity): ByteArray =
        identity.sign(statementMessage(type, payload))

    /** Checks the signature over the exact bytes received, before anything parses them. */
    fun verifyStatement(type: String, payload: ByteArray, signature: ByteArray, identityPk: ByteArray): Boolean =
        Identity.verify(identityPk, statementMessage(type, payload), signature)

    // Invitations

    /** `auth` goes to the server (which keeps a hash); `pin` never leaves phones. */
    data class InviteKeys(val auth: ByteArray, val pin: ByteArray)

    fun inviteKeys(secret: ByteArray): InviteKeys =
        InviteKeys(derive(secret, ByteArray(0), LABEL_INVITE_AUTH), derive(secret, ByteArray(0), LABEL_INVITE_PIN))

    /** HMAC(pin, inviterIdentityPk): the invitee checks it before trusting the key. */
    fun inviteMAC(pin: ByteArray, inviterIdentityPk: ByteArray): ByteArray = mac(pin, inviterIdentityPk)

    // Recovery

    fun recoveryKey(secret: ByteArray, user: UUID): ByteArray = derive(secret, user.bytes, LABEL_RECOVERY)

    /** uuid(user) ‖ u8(kind): 17 bytes. */
    fun recoveryAAD(user: UUID, kind: WrapKind): ByteArray = user.bytes + byteArrayOf(kind.raw.toByte())

    fun sealRecovery(secret: ByteArray, key: ByteArray, user: UUID, kind: WrapKind, nonce: ByteArray? = null): ByteArray =
        seal(key, secret, recoveryAAD(user, kind), nonce)

    fun openRecovery(box: ByteArray, key: ByteArray, user: UUID, kind: WrapKind): ByteArray =
        open(key, box, recoveryAAD(user, kind))

    fun recoveryBoxMessage(user: UUID, kind: WrapKind, box: ByteArray): ByteArray =
        LABEL_RECOVERY_SIG.toByteArray(Charsets.US_ASCII) + recoveryAAD(user, kind) + box

    fun signRecoveryBox(box: ByteArray, user: UUID, kind: WrapKind, identity: Identity): ByteArray =
        identity.sign(recoveryBoxMessage(user, kind, box))

    fun verifyRecoveryBox(box: ByteArray, user: UUID, kind: WrapKind, signature: ByteArray, identityPk: ByteArray): Boolean =
        Identity.verify(identityPk, recoveryBoxMessage(user, kind, box), signature)
}

/** The 16 raw bytes of a UUID, as the AADs and salts use them. */
val UUID.bytes: ByteArray
    get() = ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

/** A UUID from its 16 raw bytes, or null. */
fun uuidOf(bytes: ByteArray): UUID? {
    if (bytes.size != 16) return null
    val b = ByteBuffer.wrap(bytes)
    return UUID(b.long, b.long)
}

/** The signed statement types of docs/crypto.md. */
object StatementTypes {
    const val DEVICE_LIST = "device-list"
    const val STREAK = "streak"
    const val INVITE = "invite"
    const val ACCEPTANCE = "acceptance"
    val ALL = setOf(DEVICE_LIST, STREAK, INVITE, ACCEPTANCE)
}
