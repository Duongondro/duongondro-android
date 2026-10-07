package app.duongondro.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

/**
 * The phone's side of duongondro-api's api/openapi.yaml: the operations the
 * Android app uses, typed by hand as iOS's DuongondroAPI does. Byte strings
 * travel as standard base64, times as RFC 3339 strings (parsed with SyncTime).
 * Calls block: run them off the main thread.
 */
class Api(
    val baseUrl: String,
    val token: String? = null,
    private val http: Http = UrlConnectionHttp(),
) {
    fun withToken(token: String?) = Api(baseUrl, token, http)

    // Sign-in and sign-up (no session)

    /** The commit the server was built from. */
    fun version(): ServerVersion = get("api/version")

    /** Mails a link and an 8-character code; [proof] makes the redeemed link create the account. */
    fun requestMagicLink(email: String, proof: SignUpProof?) {
        val body = buildJsonObject {
            put("email", email)
            proof?.let { addProof(it) }
        }
        send<Unit>("POST", "api/auth/magic-links", body)
    }

    fun redeemMagicLink(token: String): SignInResult =
        send("POST", "api/auth/magic-links/redeem", buildJsonObject { put("token", token) })

    fun redeemMagicLinkCode(email: String, code: String): SignInResult =
        send("POST", "api/auth/magic-links/redeem", buildJsonObject { put("email", email); put("code", code) })

    /** Starts making an account with a passkey; the profile names the passkey and is set when the account is made. */
    fun beginPasskeySignUp(proof: SignUpProof, profile: Profile): Ceremony = send("POST", "api/auth/passkeys/sign-up", buildJsonObject {
        addProof(proof)
        profile.username?.let { put("username", it) }
        profile.displayName?.let { put("displayName", it) }
        profile.gender?.let { put("gender", it) }
    })

    fun beginPasskeySignIn(): Ceremony = send("POST", "api/auth/passkeys/sign-in", null)

    /** Completes a passkey sign-up or sign-in with the authenticator's response (WebAuthn JSON). */
    fun finishPasskey(ceremony: UUID, credential: JsonElement): SignInResult =
        send("POST", "api/auth/passkeys/${ceremony.lower}", buildJsonObject { put("credential", credential) })

    /** A live invite by its 8-character id; needs no session. */
    fun invite(id: String): InviteRecord = get("api/invites/${id.uppercase()}")

    /** Makes this account and the inviter friends: auth proves the link's secret, the acceptance is signed by this account's identity. */
    fun redeemInvite(id: String, auth: ByteArray, acceptance: SignedStatementDto) =
        send<Unit>("POST", "api/invites/${id.uppercase()}/redemptions", buildJsonObject {
            put("auth", Base64.getEncoder().encodeToString(auth))
            put("acceptance", encode(acceptance))
        })

    /** Stores an invitation this account signed; 409 when the id is taken. */
    fun createInvite(id: String, auth: ByteArray, expiresAt: String, statement: SignedStatementDto, mac: ByteArray) =
        send<Unit>("POST", "api/invites", buildJsonObject {
            put("id", id)
            put("auth", Base64.getEncoder().encodeToString(auth))
            put("expiresAt", expiresAt)
            put("payload", Base64.getEncoder().encodeToString(statement.payload))
            put("signature", Base64.getEncoder().encodeToString(statement.signature))
            put("mac", Base64.getEncoder().encodeToString(mac))
        })

    fun friends(): List<Friend> = get<FriendList>("api/friends").friends

    // The account

    fun me(): Me = get("api/me")

    /** Sets the fields given; an absent one stays as it is. 409: the username is taken. */
    fun updateMe(displayName: String? = null, username: String? = null, gender: String? = null) {
        send<Unit>("PATCH", "api/me", buildJsonObject {
            displayName?.let { put("displayName", it) }
            username?.let { put("username", it) }
            gender?.let { put("gender", it) }
        })
    }

    /** Deletes everything the server holds about this account (GDPR Article 17). */
    fun deleteMe() = send<Unit>("DELETE", "api/me", null)

    /** Ends this session; its token stops working. */
    fun signOut() = send<Unit>("DELETE", "api/me/session", null)

    fun beginPasskeyAdd(): Ceremony = send("POST", "api/me/passkeys", null)

    fun finishPasskeyAdd(ceremony: UUID, credential: JsonElement) =
        send<Unit>("POST", "api/me/passkeys/${ceremony.lower}", buildJsonObject { put("credential", credential) })

    /** A problem worth knowing about, without personal data (design: Keys, the fallback report). */
    fun reportClientError(message: String, appVersion: String, osVersion: String, context: Map<String, String> = emptyMap()) =
        send<Unit>("POST", "api/client-errors", encode(ClientErrorReport("error", message.take(2048), appVersion.take(64), osVersion.take(64), context)))

    // Keys

    fun setIdentity(publicKey: ByteArray) = send<Unit>("PUT", "api/me/identity", encode(IdentityKey(publicKey)))

    fun deviceList(): SignedStatementDto? = try { get("api/me/device-list") } catch (_: ApiError.NotFound) { null }

    fun putDeviceList(statement: SignedStatementDto) = send<Unit>("PUT", "api/me/device-list", encode(statement))

    /** Registers this device's key; the same key again returns the existing device. */
    fun registerDevice(publicKey: ByteArray, tier: String): Device = send("POST", "api/devices", encode(DeviceInput(publicKey, tier)))

    fun devices(): List<Device> = get<DeviceList>("api/devices").devices

    fun wraps(device: UUID): List<Wrap> = get<WrapList>("api/devices/${device.lower}/wraps").wraps

    fun putWrap(device: UUID, wrap: WrapInput) = send<Unit>("POST", "api/devices/${device.lower}/wraps", encode(wrap))

    fun recoveryBoxes(): List<RecoveryBox> = get<RecoveryBoxList>("api/me/recovery-boxes").boxes

    fun putRecoveryBox(kind: Int, box: RecoveryBoxInput) = send<Unit>("PUT", "api/me/recovery-boxes/$kind", encode(box))

    // Sync

    fun putLog(id: UUID, log: PracticeLogInput): PracticeLog = send("PUT", "api/practice-logs/${id.lower}", encode(log))

    fun sync(since: String?): SyncResponse =
        get(if (since == null) "api/sync" else "api/sync?since=" + URLEncoder.encode(since, "UTF-8"))

    // Plumbing

    private inline fun <reified T> get(path: String): T = decode(call("GET", path, null))

    private inline fun <reified T> send(method: String, path: String, body: JsonElement?): T = decode(call(method, path, body))

    private inline fun <reified T> decode(bytes: ByteArray): T {
        if (T::class == Unit::class) return Unit as T
        return json.decodeFromString(bytes.decodeToString())
    }

    private inline fun <reified T> encode(value: T): JsonElement = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), value)

    private fun call(method: String, path: String, body: JsonElement?): ByteArray {
        val headers = buildMap {
            put("Accept", "application/json")
            token?.let { put("Authorization", "Bearer $it") }
            if (body != null) put("Content-Type", "application/json")
        }
        val response = http.send(method, baseUrl.trimEnd('/') + "/" + path, headers, body?.toString()?.toByteArray())
        val status = response.status
        if (status in 200..299) return response.body
        val message = message(response.body)
        throw when (status) {
            401 -> ApiError.Unauthorized()
            404 -> ApiError.NotFound(message)
            409 -> ApiError.Conflict(message)
            403 -> ApiError.Forbidden(message)
            429 -> ApiError.TooManyRequests(message)
            422 -> runCatching { json.decodeFromString<OldKeyError>(response.body.decodeToString()) }.getOrNull()
                ?.let { ApiError.OldKey(it.currentKeyVersion) } ?: ApiError.Status(status, message)
            else -> ApiError.Status(status, message)
        }
    }

    private fun message(body: ByteArray): String =
        runCatching { json.decodeFromString<ErrorBody>(body.decodeToString()).error }.getOrNull()
            ?: body.decodeToString().take(200)

    private fun kotlinx.serialization.json.JsonObjectBuilder.addProof(p: SignUpProof) {
        when (p) {
            is SignUpProof.Invite -> put("invite", buildJsonObject {
                put("id", p.id)
                put("auth", Base64.getEncoder().encodeToString(p.auth))
            })
            is SignUpProof.Admission -> put("admissionCode", p.code)
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}

/** The invitation or admission code a sign-up presents. */
sealed interface SignUpProof {
    /** A friend's invitation: its id and auth = HKDF(secret, invite-auth) (docs/crypto.md, Invitations). */
    class Invite(val id: String, val auth: ByteArray) : SignUpProof

    /** A single-use admission code, 16 Crockford characters; the server reads it leniently. */
    class Admission(val code: String) : SignUpProof
}

/** What PATCH /api/me and a passkey sign-up take; null fields are left out. */
data class Profile(val displayName: String? = null, val username: String? = null, val gender: String? = null)

/** An HTTP status the API answered with; a failure to reach the server is an IOException. */
sealed class ApiError(message: String) : Exception(message) {
    class Unauthorized : ApiError("unauthorized")
    class NotFound(message: String) : ApiError(message)
    class Conflict(message: String) : ApiError(message)
    class Forbidden(message: String) : ApiError(message)
    class TooManyRequests(message: String) : ApiError(message)
    /** Another phone rotated the practice key; wraps of the new one are waiting. */
    class OldKey(val currentKeyVersion: Long) : ApiError("old key; current is $currentKeyVersion")
    class Status(val code: Int, message: String) : ApiError("$code $message")
}

/** One HTTP exchange; replaced in tests. */
fun interface Http {
    class Response(val status: Int, val body: ByteArray)

    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response
}

/** HttpURLConnection, which Android implements with PATCH (the JVM's does not). */
class UrlConnectionHttp(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : Http {
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.useCaches = false
            c.instanceFollowRedirects = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            return Http.Response(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            c.disconnect()
        }
    }
}

private val UUID.lower: String get() = toString().lowercase()

/** Standard base64, as the API (and Go's []byte) carries bytes. */
object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}

object UuidText : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.lower)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

// Models, as api/openapi.yaml names them. Times stay strings: SyncTime parses them.

@Serializable internal class ErrorBody(val error: String)
@Serializable internal class OldKeyError(val error: String = "", val currentKeyVersion: Long)

@Serializable class ServerVersion(val revision: String = "", val short: String = "", val modified: Boolean = false)

@Serializable
class SignInResult(val token: String, @Serializable(UuidText::class) val userId: UUID, val created: Boolean)

@Serializable
class Ceremony(@Serializable(UuidText::class) val sessionId: UUID, val options: JsonObject) {
    /** The WebAuthn options the platform authenticator takes: the `publicKey` member of what the server sends. */
    val publicKeyJson: String get() = (options["publicKey"] ?: options).jsonObject.toString()
}

@Serializable
class Me(
    @Serializable(UuidText::class) val id: UUID,
    @Serializable(Base64Bytes::class) val identityPublicKey: ByteArray? = null,
    val keyVersion: Long,
    val displayName: String = "",
    val username: String? = null,
    val gender: String? = null,
    val devices: List<Device> = emptyList(),
)

@Serializable class IdentityKey(@Serializable(Base64Bytes::class) val publicKey: ByteArray)

@Serializable
class SignedStatementDto(@Serializable(Base64Bytes::class) val payload: ByteArray, @Serializable(Base64Bytes::class) val signature: ByteArray)

@Serializable class DeviceInput(@Serializable(Base64Bytes::class) val publicKey: ByteArray, val tier: String)

@Serializable
class Device(
    @Serializable(UuidText::class) val id: UUID,
    @Serializable(Base64Bytes::class) val publicKey: ByteArray,
    val tier: String,
    val createdAt: String = "",
)

@Serializable class DeviceList(val devices: List<Device>)

@Serializable
class WrapInput(
    val kind: Int,
    val keyVersion: Long,
    @Serializable(Base64Bytes::class) val ephemeralKey: ByteArray,
    @Serializable(Base64Bytes::class) val box: ByteArray,
    val authType: String,
    @Serializable(Base64Bytes::class) val authenticator: ByteArray,
)

@Serializable
class Wrap(
    val kind: Int,
    val keyVersion: Long,
    @Serializable(Base64Bytes::class) val ephemeralKey: ByteArray,
    @Serializable(Base64Bytes::class) val box: ByteArray,
    val authType: String,
    @Serializable(Base64Bytes::class) val authenticator: ByteArray,
    @Serializable(UuidText::class) val deviceId: UUID? = null,
)

@Serializable class WrapList(val wraps: List<Wrap>)

@Serializable
class RecoveryBoxInput(@Serializable(Base64Bytes::class) val box: ByteArray, @Serializable(Base64Bytes::class) val signature: ByteArray)

@Serializable class RecoveryBox(val kind: Int, @Serializable(Base64Bytes::class) val box: ByteArray, val updatedAt: String = "")

@Serializable class RecoveryBoxList(val boxes: List<RecoveryBox>)

@Serializable
class PracticeLogInput(
    @Serializable(Base64Bytes::class) val sealed: ByteArray,
    val keyVersion: Long,
    val updatedAt: String,
    val deleted: Boolean? = null,
)

@Serializable
class PracticeLog(
    @Serializable(UuidText::class) val id: UUID,
    @Serializable(Base64Bytes::class) val sealed: ByteArray? = null,
    val keyVersion: Long,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
class Friend(@Serializable(UuidText::class) val userId: UUID, val displayName: String = "")

@Serializable class FriendList(val friends: List<Friend>)

@Serializable class SyncResponse(val cursor: String, val full: Boolean, val logs: List<PracticeLog>)

@Serializable
class InviteRecord(
    val id: String,
    @Serializable(Base64Bytes::class) val payload: ByteArray,
    @Serializable(Base64Bytes::class) val signature: ByteArray,
    @Serializable(Base64Bytes::class) val mac: ByteArray,
    val expiresAt: String,
)

@Serializable
internal class ClientErrorReport(
    val kind: String,
    val message: String,
    val appVersion: String,
    val osVersion: String,
    val context: Map<String, String>,
)

/** A JSON value from a string the platform authenticator returned, for [Api.finishPasskey]. */
fun parseCredentialJson(text: String): JsonElement = Api.json.parseToJsonElement(text)

