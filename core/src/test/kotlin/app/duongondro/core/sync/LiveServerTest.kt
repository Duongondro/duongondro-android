package app.duongondro.core.sync

import app.duongondro.core.Session
import app.duongondro.core.api.Api
import app.duongondro.core.api.UrlConnectionHttp
import app.duongondro.core.crypto.MemoryDeviceKeyStore
import app.duongondro.core.uuidV7
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Two phones against a running DEV server (`make serve` in duongondro-api):
 * set up keys on one, log, sync; restore the other from the recovery code and
 * find the session. Skipped unless DUONGONDRO_API_URL is set, e.g.
 * `DUONGONDRO_API_URL=http://127.0.0.1:8080 ./gradlew :core:test`.
 */
class LiveServerTest {
    private val base = System.getenv("DUONGONDRO_API_URL")

    /** The DEV-only POST /api/dev/session: a new account, or a second session for `user`. */
    private fun devSession(user: UUID? = null): Pair<String, UUID> {
        val url = "$base/api/dev/session" + (user?.let { "?user=$it" } ?: "")
        val r = UrlConnectionHttp().send("POST", url, mapOf("Accept" to "application/json"), null)
        check(r.status == 200) { "dev session: ${r.status}" }
        val o = Api.json.parseToJsonElement(r.body.decodeToString()).jsonObject
        return o["token"]!!.jsonPrimitive.content to UUID.fromString(o["userId"]!!.jsonPrimitive.content)
    }

    /** An invite made by a phone with keys checks out with its secret and not with another (the MAC). */
    @Test
    fun inviteChecksOnlyWithItsSecret() = runBlocking {
        assumeTrue("set DUONGONDRO_API_URL to run against a DEV server", base != null)
        val (token, user) = devSession()
        val phone = Account(Api(base!!, token), MemorySecretStore(), MemoryDeviceKeyStore(), MemorySyncDatabase())
        phone.setUpFirstDevice()
        val identity = phone.identity()
        val id = Crockford.encode(app.duongondro.core.crypto.E2EE.randomBytes(5))
        val secret = app.duongondro.core.crypto.E2EE.randomBytes(10)
        val keys = app.duongondro.core.crypto.E2EE.inviteKeys(secret)
        val expires = Instant.ofEpochMilli(Instant.now().plusSeconds(86_400).toEpochMilli())
        val payload = Statements.invite(id, user, identity.publicKey, expires)
        val st = SignedStatement.sign(app.duongondro.core.crypto.StatementTypes.INVITE, payload, identity)
        phone.api.createInvite(id, keys.auth, SyncTime.rfc3339(expires), app.duongondro.core.api.SignedStatementDto(st.payload, st.signature),
            app.duongondro.core.crypto.E2EE.inviteMAC(keys.pin, identity.publicKey))
        val checked = Invitation.check(Api(base), Invitation.parse(id + Crockford.encode(secret)) as Invitation.Invite)
        assertEquals(user, checked.inviter)
        // For a walk on the emulator: the link this invite makes.
        println("INVITE https://duongondro.app/i/$id#${Crockford.encode(secret)}")
        try {
            Invitation.check(Api(base), Invitation.Invite(id, app.duongondro.core.crypto.E2EE.randomBytes(10)))
            fail("another secret checked out")
        } catch (e: Invitation.Error) {
            assertEquals(Invitation.Failure.NOT_AUTHENTIC, e.failure)
        }
    }

    /**
     * The whole invitation as two phones live it: A makes one with Invites.create
     * and lists it; B, with keys of its own, checks the code and redeems it, and
     * each is the other's friend; once A revokes it, it is gone.
     */
    @Test
    fun inviteMakesFriendsUntilRevoked() = runBlocking {
        assumeTrue("set DUONGONDRO_API_URL to run against a DEV server", base != null)
        val (tokenA, userA) = devSession()
        val phoneA = Account(Api(base!!, tokenA), MemorySecretStore(), MemoryDeviceKeyStore(), MemorySyncDatabase())
        phoneA.setUpFirstDevice()
        val made = Invites.create(phoneA.api, userA, phoneA.identity())
        // The id only: the link and code would let anyone reading the log befriend A.
        println("INVITE ${made.id}")
        val listed = phoneA.api.invites().single { it.id == made.id }
        assertEquals(made.expiresAt, SyncTime.parse(listed.expiresAt))
        assertEquals(null, listed.revokedAt)

        val (tokenB, userB) = devSession()
        val phoneB = Account(Api(base, tokenB), MemorySecretStore(), MemoryDeviceKeyStore(), MemorySyncDatabase())
        phoneB.setUpFirstDevice()
        val checked = Invitation.check(Api(base), Invitation.parse(made.code) as Invitation.Invite)
        assertEquals(userA, checked.inviter)
        Invites.redeem(phoneB.api, checked, userB, phoneB.identity())
        assertTrue(phoneA.api.friends().any { it.userId == userB })
        assertTrue(phoneB.api.friends().any { it.userId == userA })

        phoneA.api.revokeInvite(made.id)
        try {
            Invitation.check(Api(base), made.invite)
            fail("a revoked invite checked out")
        } catch (_: app.duongondro.core.api.ApiError.NotFound) {
        }
        assertTrue(phoneA.api.invites().single { it.id == made.id }.revokedAt != null)
        // Friends made before the revocation stay.
        assertTrue(phoneA.api.friends().any { it.userId == userB })
    }

    @Test
    fun setUpSyncAndRestore() = runBlocking {
        assumeTrue("set DUONGONDRO_API_URL to run against a DEV server", base != null)
        val (tokenA, user) = devSession()
        val dbA = MemorySyncDatabase()
        val phoneA = Account(Api(base!!, tokenA), MemorySecretStore(), MemoryDeviceKeyStore(), dbA)
        assertEquals(Account.Standing.SET_UP, phoneA.standing())
        val code = phoneA.setUpFirstDevice()
        assertEquals(Account.Standing.READY, phoneA.standing())

        val at = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        val session = Session(uuidV7(at), "dorje-sempa", 108, at, false, "Europe/Warsaw", null, at)
        dbA.insert(SyncRecord(session, at))
        val first = SyncEngine(phoneA).sync()
        assertEquals(1, first.pushed)
        assertTrue(dbA.dirtySessions().isEmpty())

        val (tokenB, _) = devSession(user)
        val dbB = MemorySyncDatabase()
        val phoneB = Account(Api(base, tokenB), MemorySecretStore(), MemoryDeviceKeyStore(), dbB)
        assertEquals(Account.Standing.RESTORE, phoneB.standing())
        try {
            phoneB.restore("0000-0000-0000-0000-0000-0000-00")
            fail("a wrong code opened the boxes")
        } catch (e: AccountKeys.Error) {
            assertEquals(AccountKeys.Failure.BAD_RECOVERY_CODE, e.failure)
        }
        phoneB.restore(code.lowercase().replace("-", " "))
        val pulled = SyncEngine(phoneB).sync()
        assertEquals(1, pulled.pulled)
        assertEquals(session, dbB.rows[session.id]!!.record.session)

        // A change on B wins on A by its later time.
        val changed = SyncRecord(session.copy(amount = 216), at.plusMillis(5))
        dbB.rows[session.id] = MemorySyncDatabase.Row(changed, dirty = true)
        assertEquals(1, SyncEngine(phoneB).sync().pushed)
        assertEquals(1, SyncEngine(phoneA).sync().pulled)
        assertEquals(216, dbA.rows[session.id]!!.record.session.amount)
    }

    /**
     * Two people, as iOS's LiveSocialTests: an invitation and its redemption, a
     * public streak verified against the pinned key, a poke (twice: the second is
     * the server's 409, counted as sent), private again, and a block.
     */
    @Test
    fun inviteRedeemPublicStreakPokeAndBlock() = runBlocking {
        assumeTrue("set DUONGONDRO_API_URL to run against a DEV server", base != null)
        suspend fun person(name: String, gender: String?): Triple<Account, MemorySyncDatabase, Social> {
            val (token, _) = devSession()
            val db = MemorySyncDatabase()
            val account = Account(Api(base!!, token, JdkHttp), MemorySecretStore(), MemoryDeviceKeyStore(), db)
            account.setUpFirstDevice()
            account.api.updateMe(displayName = name, gender = gender)
            return Triple(account, db, Social(account, db))
        }
        val (a, dbA, socialA) = person("Tomasz", "male")
        val (b, dbB, socialB) = person("Ania", null)
        val userA = dbA.syncState()!!.user
        val userB = dbB.syncState()!!.user

        val made = Invites.create(a.api, userA, a.identity())
        val checked = Invitation.check(Api(base!!), made.invite)
        Invites.redeem(b.api, checked, userB, b.identity())
        dbB.repin(checked.inviter, checked.inviterIdentityPk, "", Instant.now())

        // A logs Dorje Sempa yesterday and today, and makes it public.
        for (d in listOf(86_400L, 0L)) {
            val t = Instant.ofEpochMilli(Instant.now().minusSeconds(d + 60).toEpochMilli())
            dbA.insert(SyncRecord(Session(uuidV7(t), "dorje-sempa", 108, t, true, java.time.ZoneId.systemDefault().id, null, t), t))
        }
        socialA.setPublic("dorje-sempa", true)
        assertEquals("nothing changed, nothing sent", 0, socialA.publishStreaks())

        val ofB = socialB.friends().single()
        assertEquals("Tomasz", ofB.displayName)
        assertEquals("male", ofB.gender)
        assertEquals(false, ofB.keyChanged)
        assertEquals("dorje-sempa", ofB.streaks.single().practice)
        assertEquals(2, ofB.streaks.single().current)
        assertEquals(1, FriendsBoard.leaders(listOf(ofB), Instant.now()).size)

        val ofA = socialA.friends().single()
        assertEquals("Ania", ofA.displayName)
        assertEquals("a gender nobody gave", null, ofA.gender)
        assertEquals(false, ofA.keyChanged)
        val day = socialA.poke(userB)
        assertEquals(day, socialA.poke(userB))
        socialB.setNotifyDone(userA, true)
        assertEquals(true, socialB.friends().single().notifyDone)

        // Private again: B sees no streak.
        socialA.setPublic("dorje-sempa", false)
        assertTrue(socialB.friends().single().streaks.isEmpty())

        // B reports and blocks A: no friends either way, and the pin is gone.
        socialB.report(userA, "test report from LiveServerTest")
        socialB.block(userA)
        assertTrue(socialB.friends().isEmpty())
        assertTrue(socialA.friends().isEmpty())
        assertTrue(dbB.pinnedFriends().isEmpty())
    }
}

/** The JDK's HTTP client, for PATCH, which the JVM's HttpURLConnection refuses (Android's implements it). */
private object JdkHttp : app.duongondro.core.api.Http {
    private val client = java.net.http.HttpClient.newHttpClient()

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): app.duongondro.core.api.Http.Response {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).apply {
            headers.forEach { (k, v) -> header(k, v) }
            method(method, body?.let { java.net.http.HttpRequest.BodyPublishers.ofByteArray(it) } ?: java.net.http.HttpRequest.BodyPublishers.noBody())
        }.build()
        val r = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        return app.duongondro.core.api.Http.Response(r.statusCode(), r.body())
    }
}
