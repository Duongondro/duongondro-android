package app.duongondro.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/**
 * Every output of docs/crypto.md, reproduced from the inputs in
 * duongondro-api/testdata/vectors.json (copied here unchanged; refresh it from
 * there, never edit it). Ed25519 is deterministic here as in Go, so signatures
 * are reproduced byte for byte, not only verified.
 *
 * Each value read is recorded; the test fails if any value in the file was not
 * checked, so a section or field added to the vectors cannot pass unnoticed.
 */
class VectorTest {
    /** A view of the vectors that remembers which leaves were read. */
    private class Reader(val root: JsonObject) {
        val read = mutableSetOf<String>()

        fun obj(path: String): JsonObject = at(path) as JsonObject
        fun arr(path: String): JsonArray = at(path) as JsonArray
        fun str(path: String): String = leaf(path).content
        fun bytes(path: String): ByteArray = hex(str(path))
        fun long(path: String): Long = leaf(path).long

        private fun leaf(path: String): JsonPrimitive = at(path).jsonPrimitive.also { read += path }

        private fun at(path: String): JsonElement =
            path.split('.').fold(root as JsonElement) { e, k ->
                when (e) {
                    is JsonObject -> e[k] ?: error("no $k in $path")
                    is JsonArray -> e[k.toInt()]
                    else -> error("$path goes through a leaf")
                }
            }

        fun allLeaves(e: JsonElement = root, prefix: String = ""): List<String> = when (e) {
            is JsonObject -> e.flatMap { (k, v) -> allLeaves(v, if (prefix.isEmpty()) k else "$prefix.$k") }
            is JsonArray -> e.flatMapIndexed { i, v -> allLeaves(v, "$prefix.$i") }
            else -> listOf(prefix)
        }
    }

    private val v = Reader(Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText()) as JsonObject)

    private fun uuid(path: String): UUID = uuidOf(v.bytes(path))!!

    @Test
    fun everyVector() {
        val handled = mapOf<String, () -> Unit>(
            "note" to { v.str("note") },
            "inputs" to ::inputs,
            "derived" to ::derived,
            "session" to ::session,
            "wraps" to ::wraps,
            "statements" to ::statements,
            "invite" to ::invite,
            "recovery" to ::recovery,
        )
        for (section in v.root.keys) {
            val check = handled[section] ?: run { fail("unhandled vector section '$section'"); return }
            check()
        }
        val unchecked = v.allLeaves() - v.read
        assertTrue("vector values never checked: $unchecked", unchecked.isEmpty())
    }

    private val user get() = uuid("inputs.user")
    private val keyVersion get() = v.long("inputs.keyVersion")

    private fun inputs() {
        val identity = Identity(v.bytes("inputs.identitySeed"))
        assertArrayEquals(v.bytes("inputs.identityPublic"), identity.publicKey)
        assertArrayEquals(v.bytes("inputs.recipientPublic"), SoftwareDeviceKey.fromRaw(v.bytes("inputs.recipientPrivate")).publicKey)
        assertArrayEquals(v.bytes("inputs.ephemeralPublic"), SoftwareDeviceKey.fromRaw(v.bytes("inputs.ephemeralPrivate")).publicKey)
        assertEquals(1L, keyVersion)
        // The rest are used by the sections below.
        for (k in listOf("inviteSecret", "nonce", "practiceKey", "qrSecret", "recipientDevice", "recoverySecret", "session", "user")) {
            v.str("inputs.$k")
        }
    }

    private fun derived() {
        for (k in v.obj("derived").keys) {
            when (k) {
                "sealKey" -> assertArrayEquals(v.bytes("derived.sealKey"), E2EE.sealKey(v.bytes("inputs.practiceKey"), user))
                else -> fail("unhandled derived key '$k'")
            }
        }
    }

    private fun session() {
        val sealKey = E2EE.sealKey(v.bytes("inputs.practiceKey"), user)
        val session = uuid("inputs.session")
        val aad = E2EE.sessionAAD(session, user, keyVersion)
        assertArrayEquals(v.bytes("session.aad"), aad)
        val json = v.str("session.json").toByteArray()
        assertArrayEquals(v.bytes("session.padded"), E2EE.pad(json))
        val sealed = E2EE.sealSession(sealKey, session, user, keyVersion, json, v.bytes("inputs.nonce"))
        assertEquals(v.str("session.sealed"), sealed.hex())
        assertArrayEquals(json, E2EE.openSession(sealKey, session, user, keyVersion, sealed))
        // The AAD binds the blob to its session, user and key version.
        assertOpenFails { E2EE.openSession(sealKey, user, user, keyVersion, sealed) }
        assertOpenFails { E2EE.openSession(sealKey, session, user, 2, sealed) }
        // The tombstone was sealed under another nonce: it opens to its json.
        val tombstone = E2EE.openSession(sealKey, session, user, keyVersion, v.bytes("session.tombstoneSealed"))
        assertEquals(v.str("session.tombstoneJson"), tombstone.decodeToString())
    }

    private fun wraps() {
        val recipient = SoftwareDeviceKey.fromRaw(v.bytes("inputs.recipientPrivate"))
        val ephemeral = SoftwareDeviceKey.fromRaw(v.bytes("inputs.ephemeralPrivate"))
        val identity = Identity(v.bytes("inputs.identitySeed"))
        val rpk = recipient.publicKey
        val device = uuid("inputs.recipientDevice")
        val wraps = v.arr("wraps")
        assertTrue(wraps.isNotEmpty())
        for (i in wraps.indices) {
            val p = "wraps.$i"
            val name = v.str("$p.name")
            val kind = E2EE.WrapKind.of(v.long("$p.kind").toInt()) ?: run { fail("unhandled wrap kind in $name"); return }
            val secret = when (kind) {
                E2EE.WrapKind.PRACTICE_KEY -> v.bytes("inputs.practiceKey")
                E2EE.WrapKind.IDENTITY_SEED -> v.bytes("inputs.identitySeed")
                E2EE.WrapKind.SHARE_KEY -> { fail("no input for a share-key wrap in $name"); return }
            }
            val aad = E2EE.wrapAAD(user, keyVersion, device, kind)
            assertEquals(name, v.str("$p.aad"), aad.hex())
            assertEquals(name, v.str("$p.shared"), ephemeral.sharedSecret(rpk).hex())
            assertEquals(name, v.str("$p.shared"), recipient.sharedSecret(ephemeral.publicKey).hex())
            assertEquals(name, v.str("$p.wrapKey"), E2EE.wrapKey(v.bytes("$p.shared"), v.bytes("$p.epk"), rpk).hex())

            val wrapped = E2EE.wrap(secret, rpk, aad, ephemeral, v.bytes("inputs.nonce"))
            assertEquals(name, v.str("$p.epk"), wrapped.epk.hex())
            assertEquals(name, v.str("$p.box"), wrapped.box.hex())
            assertArrayEquals(name, secret, E2EE.unwrap(wrapped, recipient, aad))

            // Authenticators, all reproduced exactly.
            assertEquals(name, v.str("$p.signature"), E2EE.signWrap(wrapped, aad, rpk, identity).hex())
            assertTrue(name, E2EE.verifyWrap(wrapped, aad, rpk, v.bytes("$p.signature"), identity.publicKey))
            assertFalse(name, E2EE.verifyWrap(wrapped, v.bytes("session.aad"), rpk, v.bytes("$p.signature"), identity.publicKey))
            assertEquals(name, v.str("$p.enrolTag"), E2EE.enrolTag(v.bytes("inputs.qrSecret"), wrapped, aad, rpk).hex())
            assertEquals(name, v.str("$p.selfShared"), recipient.sharedSecret(rpk).hex())
            assertEquals(name, v.str("$p.selfTag"), E2EE.selfTag(v.bytes("$p.selfShared"), user, wrapped, aad, rpk).hex())

            // A wrap opened under another kind's AAD fails.
            assertOpenFails { E2EE.unwrap(wrapped, recipient, E2EE.wrapAAD(user, keyVersion, device, E2EE.WrapKind.SHARE_KEY)) }
        }
    }

    private fun statements() {
        val identity = Identity(v.bytes("inputs.identitySeed"))
        val statements = v.arr("statements")
        assertTrue(statements.isNotEmpty())
        for (i in statements.indices) {
            val p = "statements.$i"
            val type = v.str("$p.type")
            assertTrue("unhandled statement type '$type'", type in StatementTypes.ALL)
            val payload = v.str("$p.payload").toByteArray()
            assertEquals(type, v.str("$p.message"), E2EE.statementMessage(type, payload).hex())
            assertEquals(type, v.str("$p.signature"), E2EE.signStatement(type, payload, identity).hex())
            assertTrue(type, E2EE.verifyStatement(type, payload, v.bytes("$p.signature"), identity.publicKey))
            assertFalse(type, E2EE.verifyStatement(type + "x", payload, v.bytes("$p.signature"), identity.publicKey))
        }
    }

    private fun invite() {
        val keys = E2EE.inviteKeys(v.bytes("inputs.inviteSecret"))
        assertEquals(v.str("invite.auth"), keys.auth.hex())
        assertEquals(v.str("invite.pin"), keys.pin.hex())
        assertEquals(v.str("invite.mac"), E2EE.inviteMAC(keys.pin, v.bytes("inputs.identityPublic")).hex())
    }

    private fun recovery() {
        val key = E2EE.recoveryKey(v.bytes("inputs.recoverySecret"), user)
        assertEquals(v.str("recovery.recoveryKey"), key.hex())
        val kind = E2EE.WrapKind.PRACTICE_KEY
        assertEquals(v.str("recovery.aad"), E2EE.recoveryAAD(user, kind).hex())
        val box = E2EE.sealRecovery(v.bytes("inputs.practiceKey"), key, user, kind, v.bytes("inputs.nonce"))
        assertEquals(v.str("recovery.practiceKeyBox"), box.hex())
        assertArrayEquals(v.bytes("inputs.practiceKey"), E2EE.openRecovery(box, key, user, kind))
        val identity = Identity(v.bytes("inputs.identitySeed"))
        assertEquals(v.str("recovery.practiceKeyBoxSignature"), E2EE.signRecoveryBox(box, user, kind, identity).hex())
        val sig = v.bytes("recovery.practiceKeyBoxSignature")
        assertTrue(E2EE.verifyRecoveryBox(box, user, kind, sig, identity.publicKey))
        assertFalse(E2EE.verifyRecoveryBox(box, user, E2EE.WrapKind.IDENTITY_SEED, sig, identity.publicKey))
        assertOpenFails { E2EE.openRecovery(box, key, user, E2EE.WrapKind.IDENTITY_SEED) }
    }

    @Test
    fun paddingRoundTripsAndRejectsGarbage() {
        for (n in listOf(0, 1, 254, 255, 256, 600)) {
            val data = ByteArray(n) { 0x41 }
            val padded = E2EE.pad(data)
            assertEquals(0, padded.size % 256)
            assertTrue(padded.size > n)
            assertArrayEquals(data, E2EE.unpad(padded))
        }
        assertFailure(E2EE.Failure.PADDING) { E2EE.unpad(ByteArray(256)) }
        assertFailure(E2EE.Failure.PADDING) { E2EE.unpad(byteArrayOf(0x41, 0x81.toByte(), 0)) }
    }

    @Test
    fun tagComparisonIsExact() {
        assertTrue(E2EE.equalTags(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(E2EE.equalTags(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(E2EE.equalTags(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun badPublicKeysAreRefused() {
        val key = SoftwareDeviceKey.generate()
        val good = SoftwareDeviceKey.generate().publicKey
        assertTrue(isValidDevicePublicKey(good))
        val offCurve = good.copyOf().also { it[64] = (it[64] + 1).toByte() }
        val compressed = good.copyOf(33).also { it[0] = 2 }
        for (bad in listOf(offCurve, compressed, ByteArray(65), ByteArray(0))) {
            assertFalse(isValidDevicePublicKey(bad))
            assertFailure(E2EE.Failure.KEY) { key.sharedSecret(bad) }
        }
    }

    @Test
    fun freshWrapsRoundTrip() {
        val recipient = SoftwareDeviceKey.generate()
        val secret = E2EE.randomBytes(32)
        val aad = E2EE.wrapAAD(UUID.randomUUID(), 3, UUID.randomUUID(), E2EE.WrapKind.SHARE_KEY)
        val a = E2EE.wrap(secret, recipient.publicKey, aad)
        val b = E2EE.wrap(secret, recipient.publicKey, aad)
        assertFalse("fresh ephemeral key and nonce each time", a.epk.contentEquals(b.epk) || a.box.contentEquals(b.box))
        assertArrayEquals(secret, E2EE.unwrap(a, recipient, aad))
        assertOpenFails { E2EE.unwrap(a, SoftwareDeviceKey.generate(), aad) }
    }

    private fun assertOpenFails(block: () -> Unit) = assertFailure(E2EE.Failure.OPEN, block)

    private fun assertFailure(failure: E2EE.Failure, block: () -> Unit) {
        try {
            block()
            fail("expected $failure")
        } catch (e: E2EE.Error) {
            assertEquals(failure, e.failure)
        }
    }
}
