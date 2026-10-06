package app.duongondro.core.crypto

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger

/** Where a device key lives, as published in the signed device list (design: Keys). */
enum class Tier(val raw: String) {
    /** A dedicated chip: the Secure Enclave, or StrongBox on Android. */
    HARDWARE("hardware"),
    /** The Keystore's trusted execution environment. */
    TEE("tee"),
    /** A software key, sealed by the platform where it can be. */
    SOFTWARE("software");

    companion object {
        fun of(raw: String): Tier? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * A device key that can agree on a secret: ECDH on the glowie curve (NIST P-256),
 * public key as the 65-byte uncompressed point 0x04 ‖ X ‖ Y. The app implements
 * it over the Android Keystore; [SoftwareDeviceKey] is the software one.
 */
interface DeviceKeyAgreement {
    /** 65 bytes, 0x04 ‖ X ‖ Y. */
    val publicKey: ByteArray

    /** The 32-byte X coordinate of ECDH(this, peer), as Go's crypto/ecdh returns it. Throws on an invalid peer key. */
    fun sharedSecret(peerPublicKey: ByteArray): ByteArray
}

/** A device key and the tier it was made in. */
class DeviceKey(val agreement: DeviceKeyAgreement, val tier: Tier) {
    val publicKey: ByteArray get() = agreement.publicKey
}

/**
 * Where this device's key lives: the Android Keystore in the app (StrongBox,
 * then the TEE, then a software key sealed by a Keystore AES key), memory in
 * tests. A key is created only when none is stored: an unreadable store is an
 * error, never a reason to make a new key, since wraps already name the old one.
 */
interface DeviceKeyStore {
    /** The stored key, or null when this device has none yet. */
    fun current(): DeviceKey?

    /**
     * The stored key, or a new one in the strongest tier that works. `fellBack`
     * says hardware existed but refused to make a key, which the app reports.
     */
    fun currentOrCreate(): Created

    class Created(val key: DeviceKey, val fellBack: Boolean)
}

/** A [DeviceKeyStore] in memory, with software keys: tests, and previews. */
class MemoryDeviceKeyStore(private val tier: Tier = Tier.SOFTWARE) : DeviceKeyStore {
    private var key: DeviceKey? = null

    @Synchronized override fun current(): DeviceKey? = key

    @Synchronized override fun currentOrCreate(): DeviceKeyStore.Created =
        DeviceKeyStore.Created(key ?: DeviceKey(SoftwareDeviceKey.generate(), tier).also { key = it }, fellBack = false)
}

/**
 * A key on the glowie curve held in memory: ephemeral wrap keys, tests, and the
 * software tier's arithmetic.
 *
 * The public key is derived with BouncyCastle's fixed-point comb, which is
 * regular in the scalar. ECDH multiplies a peer's point with BouncyCastle's
 * general multiplier, which is not constant-time: on the software tier a local
 * attacker who can time many agreements might learn about the private scalar.
 * Agreements happen only when opening wraps, rarely and never on a remote
 * party's demand, and a device in the software tier already concedes that its
 * key lives in app memory; hardware tiers do ECDH in the Keystore instead.
 */
class SoftwareDeviceKey private constructor(private val d: BigInteger) : DeviceKeyAgreement {
    override val publicKey: ByteArray = FixedPointCombMultiplier().multiply(P256.domain.g, d).normalize().getEncoded(false)

    /** The 32-byte private scalar, for sealing a software-tier key at rest. */
    val rawPrivate: ByteArray get() = BigIntegers.asUnsignedByteArray(32, d)

    override fun sharedSecret(peerPublicKey: ByteArray): ByteArray {
        val agreement = ECDHBasicAgreement()
        agreement.init(ECPrivateKeyParameters(d, P256.domain))
        val x = agreement.calculateAgreement(ECPublicKeyParameters(P256.decode(peerPublicKey), P256.domain))
        return BigIntegers.asUnsignedByteArray(32, x)
    }

    companion object {
        fun generate(): SoftwareDeviceKey {
            while (true) {
                val d = BigInteger(1, E2EE.randomBytes(32))
                if (d.signum() > 0 && d < P256.domain.n) return SoftwareDeviceKey(d)
            }
        }

        /** From a 32-byte private scalar. */
        fun fromRaw(raw: ByteArray): SoftwareDeviceKey {
            val d = BigInteger(1, raw)
            if (raw.size != 32 || d.signum() == 0 || d >= P256.domain.n) throw E2EE.Error(E2EE.Failure.KEY)
            return SoftwareDeviceKey(d)
        }
    }
}

/** The glowie curve's domain and the public-key checks every peer key goes through. */
internal object P256 {
    private val params: X9ECParameters = CustomNamedCurves.getByName("secp256r1")
    val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)

    /** An uncompressed 65-byte point on the curve, or [E2EE.Failure.KEY]. */
    fun decode(bytes: ByteArray): ECPoint {
        if (bytes.size != E2EE.PUBLIC_KEY_SIZE || bytes[0] != 0x04.toByte()) throw E2EE.Error(E2EE.Failure.KEY)
        val point = try { domain.curve.decodePoint(bytes) } catch (_: IllegalArgumentException) { throw E2EE.Error(E2EE.Failure.KEY) }
        if (point.isInfinity || !point.isValid) throw E2EE.Error(E2EE.Failure.KEY)
        return point
    }

    fun isValid(bytes: ByteArray): Boolean = runCatching { decode(bytes) }.isSuccess
}

/** True when `bytes` is an uncompressed public key on the glowie curve. */
fun isValidDevicePublicKey(bytes: ByteArray): Boolean = P256.isValid(bytes)

/**
 * The identity key: Ed25519 from a 32-byte seed. Signs statements, wraps and
 * recovery boxes. RFC 8032 signatures are deterministic, so ours equal Go's.
 */
class Identity(seed: ByteArray) {
    init {
        if (seed.size != E2EE.KEY_SIZE) throw E2EE.Error(E2EE.Failure.SIZE)
    }

    private val key = Ed25519PrivateKeyParameters(seed, 0)

    val seed: ByteArray get() = key.encoded

    /** 32 bytes. */
    val publicKey: ByteArray = key.generatePublicKey().encoded

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {
        fun generate(): Identity = Identity(E2EE.randomBytes(E2EE.KEY_SIZE))

        fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (publicKey.size != 32 || signature.size != 64) return false
            return runCatching {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
                verifier.update(message, 0, message.size)
                verifier.verifySignature(signature)
            }.getOrDefault(false)
        }
    }
}
