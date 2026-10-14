package app.duongondro.core.sync

import app.duongondro.core.Session
import app.duongondro.core.api.Api
import app.duongondro.core.api.UrlConnectionHttp
import app.duongondro.core.crypto.MemoryDeviceKeyStore
import app.duongondro.core.uuidV7
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/**
 * Not a test: the second person for a walk through the Friends tab on the
 * emulator, as iOS's SeedFriends. With DUONGONDRO_WALK set to a file path and
 * DUONGONDRO_API_URL to a DEV server, `setUp` makes Ania (Chenrezig yesterday
 * and the day before, public, so today is still open) and writes her invite
 * link and state to that file; `check` prints her friends as she sees them.
 *   DUONGONDRO_API_URL=http://127.0.0.1:8080 DUONGONDRO_WALK=/tmp/ania ./gradlew :core:test --tests '*WalkFriend.setUp'
 */
class WalkFriend {
    private val base = System.getenv("DUONGONDRO_API_URL")
    private val file = System.getenv("DUONGONDRO_WALK")?.let(::File)

    private fun ania(token: String, user: UUID, seed: ByteArray, db: MemorySyncDatabase): Social {
        val secrets = MemorySecretStore().apply { write(SecretName.IDENTITY_SEED, seed) }
        runBlocking { db.saveSyncState(SyncState(user, 1)) }
        return Social(Account(Api(base!!, token), secrets, MemoryDeviceKeyStore(), db), db)
    }

    @Test
    fun setUp() = runBlocking {
        assumeTrue("development data only", base != null && file != null)
        val r = UrlConnectionHttp().send("POST", "$base/api/dev/session", mapOf("Accept" to "application/json"), null)
        val o = Api.json.parseToJsonElement(r.body.decodeToString()).jsonObject
        val token = o["token"]!!.jsonPrimitive.content
        val db = MemorySyncDatabase()
        val account = Account(Api(base!!, token, JdkHttp), MemorySecretStore(), MemoryDeviceKeyStore(), db)
        account.setUpFirstDevice()
        account.api.updateMe(displayName = "Ania", gender = "female")
        val user = db.syncState()!!.user
        for (d in listOf(1L, 2L)) {
            val t = Instant.ofEpochMilli(Instant.now().minus(Duration.ofDays(d)).toEpochMilli())
            db.insert(SyncRecord(Session(uuidV7(t), "chenrezig", 108, t, true, ZoneId.systemDefault().id, null, t), t))
        }
        Social(account, db).setPublic("chenrezig", true)
        val made = Invites.create(account.api, user, account.identity())
        file!!.writeText("${made.link}\n$token $user ${Base64.getEncoder().encodeToString(account.identity().seed)}\n")
        println("WALK ${made.link}")
    }

    @Test
    fun check() = runBlocking {
        assumeTrue("development data only", base != null && file != null)
        val (token, user, seed) = file!!.readLines()[1].split(' ')
        val friends = ania(token, UUID.fromString(user), Base64.getDecoder().decode(seed), MemorySyncDatabase()).friends()
        println("WALK friends=${friends.size}")
        friends.forEach { f ->
            println("WALK ${f.displayName} keyChanged=${f.keyChanged} streaks=${f.streaks.map { "${it.practice}:${it.current}" }}")
        }
    }
}
