package app.duongondro.core.sync

import app.duongondro.core.api.Api
import app.duongondro.core.api.ApiError
import app.duongondro.core.api.Http
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Making an invitation round-trips with the invitee's check, against a server that stores what it is sent. */
class InvitesTest {
    /** POST /api/invites (409 for an id it already has), GET /api/invites/{id}, DELETE /api/invites/{id}. */
    private class FakeServer : Http {
        val invites = mutableMapOf<String, JsonObject>()
        var posts = 0
        /** Stores the next POST, then fails as if its answer were lost on the way. */
        var loseNextAnswer = false

        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Response {
            val path = url.substringAfter("http://server/")
            return when {
                method == "POST" && path == "api/invites" -> {
                    posts++
                    val o = Api.json.parseToJsonElement(body!!.decodeToString()).jsonObject
                    val id = o["id"]!!.jsonPrimitive.content
                    if (id in invites) return Http.Response(409, """{"error":"taken"}""".toByteArray())
                    invites[id] = o
                    if (loseNextAnswer) { loseNextAnswer = false; throw java.io.IOException("connection reset") }
                    Http.Response(201, ByteArray(0))
                }
                method == "GET" && path == "api/invites" -> {
                    val list = invites.values.joinToString(",") { o ->
                        """{"id":"${o["id"]!!.jsonPrimitive.content}","expiresAt":"${o["expiresAt"]!!.jsonPrimitive.content}"}"""
                    }
                    Http.Response(200, """{"invites":[$list]}""".toByteArray())
                }
                method == "GET" && path.startsWith("api/invites/") -> {
                    val o = invites[path.removePrefix("api/invites/")] ?: return Http.Response(404, """{"error":"not found"}""".toByteArray())
                    val record = buildJsonObject {
                        put("id", o["id"]!!.jsonPrimitive.content)
                        put("payload", o["payload"]!!.jsonPrimitive.content)
                        put("signature", o["signature"]!!.jsonPrimitive.content)
                        put("mac", o["mac"]!!.jsonPrimitive.content)
                        put("expiresAt", o["expiresAt"]!!.jsonPrimitive.content)
                    }
                    Http.Response(200, record.toString().toByteArray())
                }
                method == "DELETE" && path.startsWith("api/invites/") ->
                    if (invites.remove(path.removePrefix("api/invites/")) != null) Http.Response(204, ByteArray(0))
                    else Http.Response(404, """{"error":"not found"}""".toByteArray())
                else -> Http.Response(500, ByteArray(0))
            }
        }
    }

    private val server = FakeServer()
    private val api = Api("http://server", "token", server)
    private val user = UUID.fromString("0192f3a4-5b6c-7d8e-9f00-112233445566")
    private val identity = Identity(ByteArray(32) { (it * 7).toByte() })
    private val now = Instant.parse("2026-10-07T08:00:00.123456Z")

    @Test
    fun aMadeInviteChecksOutWithItsCode() {
        val made = Invites.create(api, user, identity, now)
        assertEquals(24, made.code.length)
        assertEquals(now.plus(Duration.ofDays(7)).toSyncTime(), made.expiresAt)
        assertEquals("https://duongondro.app/i/${made.id}#${made.secretText}", made.link)
        assertEquals(made.link.uppercase(), made.qrText)

        // The invitee types the code (any case, in groups), as Android's sign-up does.
        val typed = made.code.lowercase().chunked(4).joinToString(" ")
        val checked = Invitation.check(Api("http://server", http = server), Invitation.parse(typed) as Invitation.Invite, now)
        assertEquals(user, checked.inviter)
        assertArrayEquals(identity.publicKey, checked.inviterIdentityPk)
        assertEquals(made.expiresAt, checked.expiresAt)
    }

    @Test
    fun anotherSecretDoesNotCheckOut() {
        val made = Invites.create(api, user, identity, now)
        try {
            Invitation.check(api, Invitation.Invite(made.id, E2EE.randomBytes(10)), now)
            fail("another secret checked out")
        } catch (e: Invitation.Error) {
            assertEquals(Invitation.Failure.NOT_AUTHENTIC, e.failure)
        }
    }

    @Test
    fun anExpiredInviteDoesNotCheckOut() {
        val made = Invites.create(api, user, identity, now)
        try {
            Invitation.check(api, made.invite, now.plus(Duration.ofDays(8)))
            fail("an expired invite checked out")
        } catch (e: Invitation.Error) {
            assertEquals(Invitation.Failure.EXPIRED, e.failure)
        }
    }

    @Test
    fun aTakenIdDrawsAnother() {
        // The first draw repeats an id the server holds; the second is free.
        val first = Invites.create(api, user, identity, now)
        val takenId = Crockford.decode(first.id)!!
        var draws = 0
        val made = Invites.create(api, user, identity, now, random = { n ->
            if (n == 5) { draws++; if (draws == 1) takenId else ByteArray(5) { 1 } } else E2EE.randomBytes(n)
        })
        assertEquals(Crockford.encode(ByteArray(5) { 1 }), made.id)
        assertEquals(3, server.posts)
    }

    @Test
    fun aStoredInviteWhoseAnswerWasLostIsAdopted() {
        server.loseNextAnswer = true
        val made = Invites.create(api, user, identity, now)
        assertEquals(1, server.posts)
        assertEquals(user, Invitation.check(api, made.invite, now).inviter)
    }

    @Test
    fun aRevokedInviteIsGone() {
        val made = Invites.create(api, user, identity, now)
        api.revokeInvite(made.id.lowercase())
        try {
            Invitation.check(api, made.invite, now)
            fail("a revoked invite checked out")
        } catch (_: ApiError.NotFound) {
        }
    }

    @Test
    fun theCacheRoundTrips() {
        val made = Invites.create(api, user, identity, now)
        val back = MadeInvite.deserialised(made.serialised(user), user)!!
        assertEquals(made.code, back.code)
        assertEquals(made.expiresAt, back.expiresAt)
        // Another account's cached invite is never shown.
        assertNull(MadeInvite.deserialised(made.serialised(user), UUID.randomUUID()))
        assertNull(MadeInvite.deserialised("nonsense".toByteArray(), user))
    }

    @Test
    fun ownInviteIsNotRedeemed() {
        val made = Invites.create(api, user, identity, now)
        val checked = Invitation.check(api, made.invite, now)
        try {
            Invites.redeem(api, checked, user, identity)
            fail("redeemed its own invite")
        } catch (_: Invites.OwnInvite) {
        }
        assertTrue(server.invites.containsKey(made.id))
    }
}
