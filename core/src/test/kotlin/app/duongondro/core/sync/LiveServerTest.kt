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
}
