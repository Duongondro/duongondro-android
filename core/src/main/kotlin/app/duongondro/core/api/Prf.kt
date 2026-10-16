package app.duongondro.core.api

import app.duongondro.core.crypto.E2EE
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * The WebAuthn PRF extension in Credential Manager's JSON (docs/crypto.md ›
 * Passkey unlock): every passkey ceremony asks with the fixed input
 * "duongondro/v1/prf", chosen here and never taken from the server; the output
 * stays on the phone, so it is cut from the response before the response goes
 * to the server. The Android spike (design: Keys) showed Google Password
 * Manager answers with a stable output, even for a passkey made without PRF.
 */
object Prf {
    const val RP_ID = "duongondro.app"

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** The input as WebAuthn JSON carries it: base64url of the label's 17 bytes. */
    val input: String = b64.encodeToString(E2EE.LABEL_PRF.toByteArray(Charsets.US_ASCII))

    private fun eval() = buildJsonObject { putJsonObject("eval") { put("first", input) } }

    /** The server's creation or request options with `extensions.prf.eval.first` added; other extensions stay. */
    fun withEval(publicKeyOptions: String): String {
        val root = Api.json.parseToJsonElement(publicKeyOptions).jsonObject
        val extensions = (root["extensions"] as? JsonObject).orEmpty() + ("prf" to eval())
        return JsonObject(root + ("extensions" to JsonObject(extensions))).toString()
    }

    /**
     * Options for a ceremony that proves nothing to the server and only reads
     * the PRF output: a random challenge made here, any passkey of the app when
     * [allow] is empty (the retrofit, one ceremony per passkey without
     * allowCredentials), or only those credential ids.
     */
    fun assertionOptions(challenge: ByteArray, allow: List<ByteArray> = emptyList()): String = buildJsonObject {
        put("challenge", b64.encodeToString(challenge))
        put("rpId", RP_ID)
        put("userVerification", "required")
        put("timeout", 120_000)
        if (allow.isNotEmpty()) put("allowCredentials", buildJsonArray {
            allow.forEach { id -> addJsonObject { put("type", "public-key"); put("id", b64.encodeToString(id)) } }
        })
        putJsonObject("extensions") { put("prf", eval()) }
    }.toString()

    /**
     * What a ceremony returned: the credential's raw id, `prf.enabled` (creation
     * only; null when absent) and the first PRF output (null when absent).
     */
    class Result(val credentialId: ByteArray, val enabled: Boolean?, val output: ByteArray?)

    /** Reads a registration or assertion response; null when it names no credential. */
    fun read(response: JsonElement): Result? {
        val root = response as? JsonObject ?: return null
        val id = ((root["rawId"] ?: root["id"]) as? JsonPrimitive)?.contentOrNull?.let(::decode) ?: return null
        if (id.isEmpty()) return null
        val prf = (root["clientExtensionResults"] as? JsonObject)?.get("prf") as? JsonObject
        val first = ((prf?.get("results") as? JsonObject)?.get("first") as? JsonPrimitive)?.contentOrNull
        val output = first?.let(::decode)?.takeIf { it.size == E2EE.KEY_SIZE }
        return Result(id, (prf?.get("enabled") as? JsonPrimitive)?.booleanOrNull, output)
    }

    /** The response without the PRF output, for the server: `prf.results` goes, `prf.enabled` stays. */
    fun stripped(response: JsonElement): JsonElement {
        val root = response as? JsonObject ?: return response
        val ext = root["clientExtensionResults"] as? JsonObject ?: return response
        val prf = ext["prf"] as? JsonObject ?: return response
        val kept = JsonObject(prf - "results")
        val newExt = if (kept.isEmpty()) JsonObject(ext - "prf") else JsonObject(ext + ("prf" to kept))
        return JsonObject(root + ("clientExtensionResults" to newExt))
    }

    /** A credential id as the API's path takes it: base64url without padding. */
    fun idText(id: ByteArray): String = b64.encodeToString(id)

    /** base64url with or without padding, as platforms differ; null when it is not. */
    fun decode(s: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(s.trimEnd('=')) }.getOrNull()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

}
