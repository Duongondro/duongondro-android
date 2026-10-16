package app.duongondro.core.sync

import app.duongondro.core.api.Api
import app.duongondro.core.api.Http
import app.duongondro.core.crypto.E2EE
import app.duongondro.core.crypto.Identity
import app.duongondro.core.crypto.MemoryDeviceKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

/**
 * Account's user-key steps against a server that keeps the rules of
 * duongondro-api#26: a conditional create (201, or 409 naming the stored U),
 * the same statement again 201, and a replacement only with `replace: true`.
 */
class UserKeyFlowTest {
    private val user = UUID.fromString("b67fe412-4108-71f1-b85c-4c0606f45a8a")
    private val identity = Identity.generate()
    private val b64 = Base64.getEncoder()

    private class Server : Http {
        var payload: ByteArray? = null
        var signature: ByteArray? = null
        var id: String? = null
        var version = 0L
        val wraps = mutableListOf<JsonObject>()
        val boxes = mutableMapOf<String, String>()
        var puts = 0
        var replaced = 0

        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Response {
            val path = url.removePrefix("http://server/")
            val json = body?.let { Api.json.parseToJsonElement(it.decodeToString()).jsonObject }
            fun ok(text: String = "") = Http.Response(if (text.isEmpty()) 204 else 200, text.toByteArray())
            return when {
                method == "GET" && path == "api/me/user-key" -> payload?.let {
                    ok(buildJsonObject {
                        put("id", id); put("version", version)
                        put("statement", buildJsonObject {
                            put("payload", Base64.getEncoder().encodeToString(it))
                            put("signature", Base64.getEncoder().encodeToString(signature))
                        })
                        put("wraps", buildJsonArray { wraps.forEach { add(it) } })
                    }.toString())
                } ?: Http.Response(404, """{"error":"none"}""".toByteArray())
                method == "PUT" && path == "api/me/user-key" -> {
                    puts++
                    val p = Base64.getDecoder().decode(json!!["payload"]!!.jsonPrimitive.content)
                    val st = Statements.parseUserKey(p)!!
                    val replace = json["replace"]?.jsonPrimitive?.booleanOrNull == true
                    when {
                        payload == null || payload.contentEquals(p) -> Unit
                        replace && st.version > version && st.id.toString() != id -> { replaced++; wraps.clear(); boxes.clear() }
                        else -> return Http.Response(409, """{"error":"exists","userKey":{"id":"$id","version":$version}}""".toByteArray())
                    }
                    payload = p
                    signature = Base64.getDecoder().decode(json["signature"]!!.jsonPrimitive.content)
                    id = st.id.toString(); version = st.version
                    Http.Response(201, ByteArray(0))
                }
                method == "POST" && path == "api/me/user-key/wraps" -> { wraps += json!!; ok() }
                method == "PUT" && path.endsWith("/unlock") -> { boxes[path] = json!!["userKeyId"]!!.jsonPrimitive.content; ok() }
                method == "GET" && path == "api/me/device-list" -> Http.Response(404, """{"error":"none"}""".toByteArray())
                else -> error("unexpected $method $path")
            }
        }
    }

    private fun phone(server: Server, secrets: SecretStore = MemorySecretStore()): Pair<Account, SecretStore> {
        val db = MemorySyncDatabase()
        runBlocking { db.saveSyncState(SyncState(user, 1)) }
        secrets.write(SecretName.IDENTITY_SEED, identity.seed)
        secrets.write(SecretName.practiceKey(1), E2EE.randomBytes(32))
        return Account(Api("http://server", "t", server), secrets, MemoryDeviceKeyStore(), db) to secrets
    }

    @Test fun theFirstPhonePublishesUAndWrapsBothSecrets() = runBlocking {
        val server = Server()
        val (a, secrets) = phone(server)
        val u = a.ensureUserKey()
        assertNotNull(u)
        assertEquals(u!!.id.toString(), server.id)
        assertEquals(setOf(1, 2), server.wraps.map { it["kind"]!!.jsonPrimitive.content.toInt() }.toSet())
        assertNotNull(secrets.read(SecretName.USER_KEY))
        // Again: nothing new is published or wrapped.
        a.ensureUserKey()
        assertEquals(1, server.puts)
        assertEquals(2, server.wraps.size)
    }

    @Test fun aSecondPhoneNeverReplacesU() = runBlocking {
        val server = Server()
        phone(server).first.ensureUserKey()
        val first = server.id
        // Another phone, which made a U of its own before it heard of the first (a lost race).
        val other = MemorySecretStore().also { it.write(SecretName.USER_KEY, UserKeys.create().serialised()) }
        val (b, secrets) = phone(server, other)
        assertNull("it waits for U as a kind 4 wrap", runCatching { b.ensureUserKey() }.getOrNull())
        assertEquals(first, server.id)
        assertNull("its own U is dropped", secrets.read(SecretName.USER_KEY))
        assertEquals(0, server.replaced)
    }

    @Test fun aRetryPublishesTheSameU() = runBlocking {
        val server = Server()
        val pending = UserKeys.create()
        val secrets = MemorySecretStore().also { it.write(SecretName.USER_KEY, pending.serialised()) }
        val (a, _) = phone(server, secrets)
        assertEquals(pending.id, a.ensureUserKey()!!.id)
        assertEquals(pending.id.toString(), server.id)
    }

    @Test fun replacingTheUnlockKeyOnlyOnRequest() = runBlocking {
        val server = Server()
        val (a, secrets) = phone(server)
        val old = a.ensureUserKey()!!
        a.sealUserKeyForPasskey(E2EE.randomBytes(32), byteArrayOf(1, 2, 3))
        assertEquals(1, server.boxes.size)
        assertTrue(a.replaceUserKey())
        assertEquals(1, server.replaced)
        assertNotEquals(old.id.toString(), server.id)
        assertEquals(2L, server.version)
        assertTrue("the old boxes are gone; passkeys need new ones", server.boxes.isEmpty())
        assertEquals("both secrets wrapped to the new U", setOf(1, 2), server.wraps.map { it["kind"]!!.jsonPrimitive.content.toInt() }.toSet())
        assertEquals(server.id, UserKeys.Local.deserialised(secrets.read(SecretName.USER_KEY)!!)!!.id.toString())
        assertNull(secrets.read(SecretName.USER_KEY_NEXT))
    }

    @Test fun noUNoReplacement() = runBlocking {
        val server = Server()
        assertFalse(phone(server).first.replaceUserKey())
        assertEquals(0, server.puts)
    }
}
