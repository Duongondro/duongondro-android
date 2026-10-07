package app.duongondro.core.sync

import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.StatementTypes
import app.duongondro.core.crypto.Tier
import app.duongondro.core.crypto.hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

class StatementsTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResource("/vectors.json")!!.readText()).jsonObject
    private val identity = Identity(hex(vectors["inputs"]!!.jsonObject["identitySeed"]!!.jsonPrimitive.content))

    /** Every statement in the vectors is rebuilt from its parsed form byte for byte (iOS testDeviceListMatchesTheVector, for all types). */
    @Test fun canonicalPayloadsMatchTheVectors() {
        for (s in vectors["statements"] as JsonArray) {
            val o = s as JsonObject
            val type = o["type"]!!.jsonPrimitive.content
            val payload = o["payload"]!!.jsonPrimitive.content.toByteArray()
            val signed = SignedStatement(payload, hex(o["signature"]!!.jsonPrimitive.content))
            assertTrue(type, signed.verify(type, identity.publicKey))
            val rebuilt = when (type) {
                StatementTypes.DEVICE_LIST -> Statements.parseDeviceList(payload)!!.let {
                    Statements.deviceList(it.devices, it.issuedAt, it.user, it.version)
                }
                StatementTypes.STREAK -> Statements.streak(Statements.parseStreak(payload)!!)
                StatementTypes.INVITE -> Statements.parseInvite(payload)!!.let {
                    Statements.invite(it.id, it.inviter, it.inviterIdentityPk, it.expiresAt)
                }
                StatementTypes.ACCEPTANCE -> Statements.parseAcceptance(payload)!!.let {
                    Statements.acceptance(it.inviteId, it.invitee, it.inviteeIdentityPk)
                }
                else -> { fail("unhandled statement type '$type'"); return }
            }
            assertEquals(type, payload.decodeToString(), rebuilt.decodeToString())
            assertEquals(type, SignedStatement.sign(type, rebuilt, identity).signature.hex(), signed.signature.hex())
        }
    }

    @Test fun deviceListReadsTheVector() {
        val payload = (vectors["statements"] as JsonArray).map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == StatementTypes.DEVICE_LIST }["payload"]!!.jsonPrimitive.content
        val list = Statements.parseDeviceList(payload.toByteArray())!!
        assertEquals(1L, list.version)
        assertEquals(UUID.fromString("b67fe412-4108-71f1-b85c-4c0606f45a8a"), list.user)
        assertEquals(Tier.HARDWARE, list.devices.single().tier)
        assertEquals(vectors["inputs"]!!.jsonObject["recipientPublic"]!!.jsonPrimitive.content, list.devices.single().publicKey.hex())
    }

    @Test fun acceptanceIsCanonical() {
        val invitee = UUID.fromString("0192a3b4-c5d6-7e8f-9012-3456789abcde")
        val pk = ByteArray(32) { (it * 7).toByte() }
        val payload = Statements.acceptance("7K2MQ9XA", invitee, pk)
        assertEquals(
            """{"inviteId":"7K2MQ9XA","invitee":"0192a3b4-c5d6-7e8f-9012-3456789abcde","inviteeIdentityPk":"${Statements.base64url(pk)}"}""",
            payload.decodeToString(),
        )
        assertArrayEquals(pk, Statements.parseAcceptance(payload)!!.inviteeIdentityPk)
    }

    @Test fun timesAreRoundedToTheNearestMillisecond() {
        val user = UUID.randomUUID()
        // A hair below a millisecond names that millisecond, not the one before.
        val issued = Instant.ofEpochSecond(1_791_176_400, 999_999)
        val payload = Statements.deviceList(emptyList(), issued, user, 1).decodeToString()
        assertTrue(payload, payload.contains("\"issuedAt\":1791176400001"))
    }

    @Test fun malformedPayloadsDoNotParse() {
        assertNull(Statements.parseDeviceList("{}".toByteArray()))
        assertNull(Statements.parseDeviceList("""{"devices":[{"id":"x","pk":"AA","tier":"hardware"}],"issuedAt":1,"user":"${UUID.randomUUID()}","version":1}""".toByteArray()))
        assertNull(Statements.parseDeviceList("""{"devices":[{"id":"${UUID.randomUUID()}","pk":"AA","tier":"quantum"}],"issuedAt":1,"user":"${UUID.randomUUID()}","version":1}""".toByteArray()))
        assertNull(Statements.parseStreak("not json".toByteArray()))
    }

    @Test fun base64urlIsUnpadded() {
        assertEquals("-_8", Statements.base64url(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), Statements.fromBase64url("-_8"))
        assertEquals(E2EE.PUBLIC_KEY_SIZE, Statements.fromBase64url(Statements.base64url(ByteArray(65)))!!.size)
    }
}
